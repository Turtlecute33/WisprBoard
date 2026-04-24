// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.util.Base64
import androidx.annotation.VisibleForTesting
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.utils.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Sends audio to OpenRouter's chat completions API for transcription.
 * Must be called from a background thread.
 */
class OpenRouterClient(
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String,
    private val runtimeInstruction: String?,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {
    @Volatile private var activeConnection: HttpURLConnection? = null

    companion object {
        private const val TAG = "OpenRouterClient"
        const val API_BASE = "https://openrouter.ai/api/v1"
        private const val ENDPOINT = "$API_BASE/chat/completions"
        const val KEY_ENDPOINT = "$API_BASE/key"
        /** Template: call [modelEndpointUrl] to fill in the model id safely. */
        fun modelEndpointUrl(author: String, slug: String): String = "$API_BASE/models/$author/$slug/endpoints"
        private const val STABLE_AUDIO_INSTRUCTION = "Process the attached audio input according to the system instructions. Return only the final answer."
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 90_000
        private const val MAX_ATTEMPTS = 3
        private val RETRYABLE_STATUSES = setOf(408, 425, 429, 500, 502, 503, 504)
        // Sanity cap to prevent pathological responses from consuming unbounded memory.
        // Real transcription responses are kilobytes; 1 MB is ~3 orders of magnitude of headroom.
        private const val MAX_RESPONSE_BYTES = 1_000_000L
        // Error bodies are strictly for debug logging; cap them tightly so a misbehaving server
        // can't make us buffer megabytes of HTML just to log a prefix.
        private const val MAX_ERROR_BYTES = 64 * 1024L
        // Upper bound on how long we'll honor a server-supplied Retry-After, to stay responsive.
        private const val MAX_RETRY_AFTER_MS = 30_000L
        private val SENSITIVE_LOG_PATTERNS: List<Pair<Regex, String>> = listOf(
            Regex("(?i)Bearer\\s+\\S+") to "Bearer ***",
            Regex("(?i)Authorization\\s*[:=]\\s*\\S+") to "Authorization: ***",
            Regex("(?i)X-Api-Key\\s*[:=]\\s*\\S+") to "X-Api-Key: ***",
            Regex("(?i)(\"?api[_-]?key\"?\\s*[:=]\\s*\"?)[^\"\\s,}]+") to "$1***",
            Regex("(?i)(\"?token\"?\\s*[:=]\\s*[\"']?)[^\"'\\s,}]+") to "$1***",
            Regex("sk-[A-Za-z0-9_\\-]{10,}") to "sk-***",
        )
        // Plain ASCII sentinel: org.json escapes control characters (e.g. U+0000 -> literal "\u0000"),
        // which used to make the placeholder un-findable in the serialized body. Unlikely to collide
        // with real content.
        private const val AUDIO_PLACEHOLDER = "__WISPRBOARD_AUDIO_B64_PLACEHOLDER__"
        // Must be a multiple of 3 so chunked base64 encoding is padding-free until the final chunk.
        private const val AUDIO_READ_CHUNK = 48 * 1024
        private val HTTP_DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                    isLenient = false
                }
        }
    }

    /**
     * Performs one transcription request, retrying transient failures with exponential backoff.
     * Streams [audioFile] to the server via chunked transfer encoding so peak heap is bounded
     * regardless of recording length. Throws [OpenRouterException] on non-retryable failures
     * or after retries are exhausted. The caller owns the file and must delete it.
     */
    fun transcribe(audioFile: File): String {
        var lastError: Exception? = null
        var nextDelayOverrideMs: Long = -1L
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            try {
                return performRequest(audioFile)
            } catch (e: OpenRouterException) {
                if (e.statusCode !in RETRYABLE_STATUSES || attempt == MAX_ATTEMPTS - 1) throw e
                lastError = e
                nextDelayOverrideMs = if ((e.statusCode == 429 || e.statusCode == 503) && e.retryAfterMs > 0) e.retryAfterMs else -1L
            } catch (e: SocketTimeoutException) {
                if (attempt == MAX_ATTEMPTS - 1) throw OpenRouterException("Request timed out")
                lastError = e
                nextDelayOverrideMs = -1L
            } catch (e: InterruptedIOException) {
                throw InterruptedException()
            } catch (e: java.io.IOException) {
                if (attempt == MAX_ATTEMPTS - 1) throw OpenRouterException(sanitizeForLog(e.message ?: "Network error"))
                lastError = e
                nextDelayOverrideMs = -1L
            }
            val delayMs = if (nextDelayOverrideMs > 0) nextDelayOverrideMs else (500L shl attempt).coerceAtMost(4_000L)
            if (BuildConfig.DEBUG) Log.i(TAG, "Retrying after ${delayMs}ms (attempt ${attempt + 1})")
            try { Thread.sleep(delayMs) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
        }
        throw OpenRouterException(sanitizeForLog(lastError?.message ?: "Transcription failed"))
    }

    fun cancel() {
        activeConnection?.disconnect()
    }

    /**
     * Sends [userText] as a chat completion request (no audio) and returns the assistant's
     * reply. Uses [systemPrompt] as the system message. Retries transient failures with the
     * same policy as [transcribe].
     */
    fun fixText(userText: String): String {
        var lastError: Exception? = null
        var nextDelayOverrideMs: Long = -1L
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            try {
                return performTextRequest(userText)
            } catch (e: OpenRouterException) {
                if (e.statusCode !in RETRYABLE_STATUSES || attempt == MAX_ATTEMPTS - 1) throw e
                lastError = e
                nextDelayOverrideMs = if ((e.statusCode == 429 || e.statusCode == 503) && e.retryAfterMs > 0) e.retryAfterMs else -1L
            } catch (e: SocketTimeoutException) {
                if (attempt == MAX_ATTEMPTS - 1) throw OpenRouterException("Request timed out")
                lastError = e
                nextDelayOverrideMs = -1L
            } catch (e: InterruptedIOException) {
                throw InterruptedException()
            } catch (e: java.io.IOException) {
                if (attempt == MAX_ATTEMPTS - 1) throw OpenRouterException(sanitizeForLog(e.message ?: "Network error"))
                lastError = e
                nextDelayOverrideMs = -1L
            }
            val delayMs = if (nextDelayOverrideMs > 0) nextDelayOverrideMs else (500L shl attempt).coerceAtMost(4_000L)
            try { Thread.sleep(delayMs) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
        }
        throw OpenRouterException(sanitizeForLog(lastError?.message ?: "Request failed"))
    }

    private fun performTextRequest(userText: String): String {
        val messages = JSONArray().apply {
            put(buildSystemMessage())
            put(buildTextMessage(userText))
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
        }.toString().toByteArray(Charsets.UTF_8)

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setFixedLengthStreamingMode(body.size)
        }
        activeConnection = connection
        try {
            connection.outputStream.use { it.write(body) }
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = readErrorBodyCapped(connection.errorStream)
                if (BuildConfig.DEBUG) Log.e(TAG, "API error $responseCode: ${sanitizeForLog(errorBody)}")
                val retryAfterMs = parseRetryAfterMs(connection.getHeaderField("Retry-After"))
                throw OpenRouterException("API error: $responseCode", responseCode, retryAfterMs)
            }
            val responseBody = readCappedString(connection.inputStream, MAX_RESPONSE_BYTES)
            return parseContent(responseBody)
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }

    /**
     * Builds the full JSON request body except for the base64 audio payload, which is
     * streamed separately. The placeholder sentinel is split in half so both halves can be
     * emitted verbatim around the live base64 stream.
     */
    private fun buildRequestEnvelope(): Pair<String, String> {
        val messages = JSONArray().apply {
            put(buildSystemMessage())
            put(buildTextMessage(STABLE_AUDIO_INSTRUCTION))
            runtimeInstruction?.takeIf { it.isNotBlank() }?.let { put(buildTextMessage(it)) }
            put(buildAudioMessage(AUDIO_PLACEHOLDER))
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
        }.toString()
        val placeholderIndex = body.indexOf(AUDIO_PLACEHOLDER)
        check(placeholderIndex >= 0) { "Audio placeholder not found in request body" }
        return body.substring(0, placeholderIndex) to body.substring(placeholderIndex + AUDIO_PLACEHOLDER.length)
    }

    private fun buildSystemMessage(): JSONObject {
        val textContent = JSONObject().apply {
            put("type", "text")
            put("text", systemPrompt)
            if (shouldAttachPromptCacheHint(model)) {
                put("cache_control", JSONObject().apply { put("type", "ephemeral") })
            }
        }
        return JSONObject().apply {
            put("role", "system")
            put("content", JSONArray().apply { put(textContent) })
        }
    }

    private fun buildTextMessage(text: String): JSONObject {
        val textContent = JSONObject().apply {
            put("type", "text")
            put("text", text)
        }
        return JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply { put(textContent) })
        }
    }

    private fun buildAudioMessage(base64Audio: String): JSONObject {
        val audioContent = JSONObject().apply {
            put("type", "input_audio")
            put("input_audio", JSONObject().apply {
                put("data", base64Audio)
                put("format", "wav")
            })
        }
        return JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply { put(audioContent) })
        }
    }

    private fun performRequest(audioFile: File): String {
        val (prefix, suffix) = buildRequestEnvelope()
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            // Chunked streaming keeps the upload out of HttpURLConnection's internal buffer;
            // without it the entire body (including audio) would be buffered in memory before
            // the request is sent.
            setChunkedStreamingMode(0)
        }
        activeConnection = connection
        try {
            connection.outputStream.use { out ->
                val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
                out.write(prefixBytes)
                streamBase64Audio(audioFile, out)
                out.write(suffix.toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = readErrorBodyCapped(connection.errorStream)
                if (BuildConfig.DEBUG) Log.e(TAG, "API error $responseCode: ${sanitizeForLog(errorBody)}")
                val retryAfterMs = parseRetryAfterMs(connection.getHeaderField("Retry-After"))
                throw OpenRouterException("API error: $responseCode", responseCode, retryAfterMs)
            }

            val responseBody = readCappedString(connection.inputStream, MAX_RESPONSE_BYTES)
            return parseContent(responseBody)
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }

    private fun parseContent(responseBody: String): String {
        val json = try {
            JSONObject(responseBody)
        } catch (e: JSONException) {
            throw OpenRouterException("Malformed API response")
        }
        logCacheUsage(json)
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw OpenRouterException("API response missing choices")
        }
        val content = choices.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()
        if (content.isEmpty()) {
            throw OpenRouterException("API response missing content")
        }
        return content
    }

    private fun logCacheUsage(json: JSONObject) {
        if (!BuildConfig.DEBUG) return
        val details = json.optJSONObject("usage")
            ?.optJSONObject("prompt_tokens_details")
            ?: return
        val cachedTokens = details.optInt("cached_tokens", 0)
        val cacheWriteTokens = details.optInt("cache_write_tokens", 0)
        if (cachedTokens <= 0 && cacheWriteTokens <= 0) return
        Log.i(
            TAG,
            "Prompt cache stats for $model: cached_tokens=$cachedTokens, cache_write_tokens=$cacheWriteTokens"
        )
    }

    /**
     * Reads [audioFile] in 48 KiB chunks (multiple of 3 for padding-free base64) and
     * writes the encoded bytes straight to [out], avoiding any full-body buffer.
     */
    private fun streamBase64Audio(audioFile: File, out: OutputStream) {
        val buf = ByteArray(AUDIO_READ_CHUNK)
        FileInputStream(audioFile).use { fis ->
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val n = fis.read(buf)
                if (n == -1) break
                // AUDIO_READ_CHUNK is a multiple of 3, so intermediate reads produce padding-free
                // base64. The final (short) read may include padding, which is valid at the end.
                out.write(Base64.encode(buf, 0, n, Base64.NO_WRAP))
            }
        }
    }

    private fun readCappedString(input: java.io.InputStream, maxBytes: Long): String {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0L
        input.use { stream ->
            while (true) {
                val n = stream.read(chunk)
                if (n == -1) break
                total += n
                if (total > maxBytes) throw OpenRouterException("Response too large")
                buf.write(chunk, 0, n)
            }
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    @VisibleForTesting
    internal fun sanitizeForLog(body: String): String {
        // Guard against the remote echoing our Authorization header or any api key field back to us.
        // Patterns are ordered from most-specific to least so each substitution leaves subsequent
        // ones with cleaner input to match against.
        var sanitized = body
        for ((regex, replacement) in SENSITIVE_LOG_PATTERNS) {
            sanitized = sanitized.replace(regex, replacement)
        }
        return sanitized
    }

    /**
     * Reads up to [MAX_ERROR_BYTES] from [stream] for debug logging only. Unlike the success-path
     * reader, hitting the cap is not an error — we silently truncate so a misbehaving server can't
     * turn a 500 response into a second, bigger failure.
     */
    private fun readErrorBodyCapped(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4 * 1024)
        var total = 0L
        stream.use { s ->
            while (true) {
                val n = s.read(chunk)
                if (n == -1) break
                val writable = kotlin.math.min(n.toLong(), MAX_ERROR_BYTES - total).toInt()
                if (writable > 0) { buf.write(chunk, 0, writable); total += writable }
                if (total >= MAX_ERROR_BYTES) break
            }
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    /**
     * Parses an HTTP `Retry-After` header value. Accepts either a non-negative integer number of
     * seconds (RFC 7231 delta-seconds) or an HTTP-date. Returns -1 when absent/unparseable,
     * otherwise the delay in milliseconds clamped to `[0, MAX_RETRY_AFTER_MS]`.
     */
    @VisibleForTesting
    internal fun parseRetryAfterMs(header: String?): Long {
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty()) return -1L
        raw.toLongOrNull()?.let { seconds ->
            if (seconds < 0) return -1L
            return (seconds * 1000L).coerceIn(0L, MAX_RETRY_AFTER_MS)
        }
        return try {
            val epochMs = (HTTP_DATE_FORMAT.get() ?: return -1L).parse(raw)?.time ?: return -1L
            val deltaMs = epochMs - System.currentTimeMillis()
            deltaMs.coerceIn(0L, MAX_RETRY_AFTER_MS)
        } catch (_: Exception) {
            -1L
        }
    }
}

class OpenRouterException(
    message: String,
    val statusCode: Int = -1,
    val retryAfterMs: Long = -1L,
) : Exception(message)
