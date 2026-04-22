// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.util.Base64
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
        private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        private const val STABLE_AUDIO_INSTRUCTION = "Process the attached audio input according to the system instructions. Return only the final answer."
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 90_000
        private const val MAX_ATTEMPTS = 3
        private val RETRYABLE_STATUSES = setOf(408, 425, 429, 500, 502, 503, 504)
        // Sanity cap to prevent pathological responses from consuming unbounded memory.
        // Real transcription responses are kilobytes; 1 MB is ~3 orders of magnitude of headroom.
        private const val MAX_RESPONSE_BYTES = 1_000_000L
        private const val AUDIO_PLACEHOLDER = "\u0000__AUDIO_B64__\u0000"
        // Must be a multiple of 3 so chunked base64 encoding is padding-free until the final chunk.
        private const val AUDIO_READ_CHUNK = 48 * 1024
    }

    /**
     * Performs one transcription request, retrying transient failures with exponential backoff.
     * Streams [audioFile] to the server via chunked transfer encoding so peak heap is bounded
     * regardless of recording length. Throws [OpenRouterException] on non-retryable failures
     * or after retries are exhausted. The caller owns the file and must delete it.
     */
    fun transcribe(audioFile: File): String {
        var lastError: Exception? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            try {
                return performRequest(audioFile)
            } catch (e: OpenRouterException) {
                if (e.statusCode !in RETRYABLE_STATUSES || attempt == MAX_ATTEMPTS - 1) throw e
                lastError = e
            } catch (e: SocketTimeoutException) {
                if (attempt == MAX_ATTEMPTS - 1) throw OpenRouterException("Request timed out")
                lastError = e
            } catch (e: InterruptedIOException) {
                throw InterruptedException()
            } catch (e: java.io.IOException) {
                if (attempt == MAX_ATTEMPTS - 1) throw OpenRouterException(sanitizeForLog(e.message ?: "Network error"))
                lastError = e
            }
            val delayMs = (500L shl attempt).coerceAtMost(4_000L)
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
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (BuildConfig.DEBUG) Log.e(TAG, "API error $responseCode: ${sanitizeForLog(errorBody)}")
                throw OpenRouterException("API error: $responseCode", responseCode)
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

    private fun sanitizeForLog(body: String): String {
        // Guard against the remote echoing our Authorization header or any api key field back to us.
        return body
            .replace(Regex("(?i)Bearer\\s+\\S+"), "Bearer ***")
            .replace(Regex("(?i)(\"?api[_-]?key\"?\\s*[:=]\\s*\"?)[^\"\\s,}]+"), "$1***")
    }
}

class OpenRouterException(message: String, val statusCode: Int = -1) : Exception(message)
