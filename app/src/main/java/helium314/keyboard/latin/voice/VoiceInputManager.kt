// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.io.File
import java.util.Locale

/**
 * Orchestrates voice recording, transcription via OpenRouter, and text insertion.
 * All state transitions happen on the main thread.
 */
class VoiceInputManager(
    private val context: Context,
    private val callbacks: Callbacks
) {
    companion object {
        private const val TAG = "VoiceInputManager"
        private const val MAX_TRANSCRIPTION_LENGTH = 10_000
        private const val AUDIO_CACHE_SUBDIR = "voice_audio"
    }

    enum class State { IDLE, RECORDING, TRANSCRIBING }

    /** Snapshot of text immediately adjacent to the cursor, used for spacing heuristics. */
    data class SpacingContext(val charBefore: Char?, val charAfter: Char?)

    interface Callbacks {
        fun onRecordingStarted()
        fun onTranscribing()
        fun onFinished()
        fun onTranscriptionResult(text: String)
        fun onError(message: String)
        fun onMaxDurationReached()
        /** Optional IME subtype locale; used as a hint to the transcription model. */
        fun getLocaleHint(): Locale? = null
        /** Optional surrounding-text snapshot; used to decide whether to insert spaces. */
        fun getSpacingContext(): SpacingContext? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioRecorder: AudioRecorder = newRecorder()
    private var state = State.IDLE
    private var currentAudioFile: File? = null
    @Volatile private var transcriptionThread: Thread? = null
    @Volatile private var transcriptionClient: OpenRouterClient? = null
    @Volatile private var activeTranscriptionToken = 0L

    fun getState() = state

    /** Exposed so UI can render a live amplitude meter. */
    fun getCurrentAmplitude(): Double = audioRecorder.currentAmplitude

    /** Exposed so UI can render an elapsed-time counter. */
    fun getCurrentDurationMs(): Long = audioRecorder.currentDurationMs

    @Synchronized
    fun startRecording() {
        VoiceDiagnosticLog.log(context, "VIM", "startRecording() state=$state")
        if (state != State.IDLE) return

        val prefs = context.prefs()

        if (!prefs.getBoolean(Settings.PREF_VOICE_INPUT_ENABLED, Defaults.PREF_VOICE_INPUT_ENABLED)) {
            Toast.makeText(context, R.string.voice_error_not_enabled, Toast.LENGTH_SHORT).show()
            return
        }

        if (!SecretStore.isSecureStorageAvailable(context)) {
            Toast.makeText(context, R.string.voice_error_secure_storage_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        val apiKey = SecretStore.getApiKey(context, Settings.PREF_OPENROUTER_API_KEY, Defaults.PREF_OPENROUTER_API_KEY)
        if (apiKey.isBlank()) {
            Toast.makeText(context, R.string.voice_error_no_api_key, Toast.LENGTH_SHORT).show()
            return
        }

        if (!PermissionsUtil.checkAllPermissionsGranted(context, Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(context, R.string.voice_error_no_permission, Toast.LENGTH_SHORT).show()
            return
        }

        if (!isNetworkAvailable()) {
            Toast.makeText(context, R.string.voice_error_no_network, Toast.LENGTH_SHORT).show()
            return
        }

        val maxDurationSec = prefs.getInt(Settings.PREF_VOICE_MAX_DURATION_SECONDS, Defaults.PREF_VOICE_MAX_DURATION_SECONDS)
            .coerceIn(15, 300)
        val autoStopEnabled = prefs.getBoolean(Settings.PREF_VOICE_AUTO_STOP_SILENCE, Defaults.PREF_VOICE_AUTO_STOP_SILENCE)
        val autoStopSec = prefs.getInt(Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS, Defaults.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS)
            .coerceIn(1, 10)

        // Fresh cache file per recording; older ones are swept away in newRecorder().
        val audioFile = File(cacheAudioDir(), "rec_${System.currentTimeMillis()}.wav")
        currentAudioFile = audioFile
        audioRecorder = AudioRecorder(
            outputFile = audioFile,
            maxDurationMs = maxDurationSec * 1000L,
            autoStopSilenceMs = if (autoStopEnabled) autoStopSec * 1000L else 0L,
        )
        audioRecorder.onMaxDurationReached = {
            mainHandler.post {
                callbacks.onMaxDurationReached()
                stopRecording()
            }
        }
        audioRecorder.onAutoStopSilence = {
            mainHandler.post { stopRecording() }
        }

        if (!audioRecorder.start()) {
            Toast.makeText(context, R.string.voice_error_transcription_failed, Toast.LENGTH_SHORT).show()
            return
        }

        state = State.RECORDING
        callbacks.onRecordingStarted()
    }

    @Synchronized
    fun stopRecording() {
        VoiceDiagnosticLog.log(context, "VIM", "stopRecording() state=$state caller=${VoiceDiagnosticLog.callerTrace()}")
        if (state != State.RECORDING) return

        val wavFile = audioRecorder.stop()
        VoiceDiagnosticLog.log(
            context,
            "VIM",
            "audioRecorder.stop() -> file=${wavFile?.absolutePath} exists=${wavFile?.exists()} size=${wavFile?.length()} durationMs=${audioRecorder.lastDurationMs} meanAmp=${audioRecorder.lastMeanAmplitude}"
        )
        if (wavFile == null || !wavFile.exists() || wavFile.length() <= 44L) {
            wavFile?.delete()
            currentAudioFile = null
            state = State.IDLE
            callbacks.onFinished()
            callbacks.onError(context.getString(R.string.voice_error_no_audio))
            VoiceDiagnosticLog.log(context, "VIM", "stopRecording -> NO_AUDIO error path")
            return
        }
        if (BuildConfig.DEBUG) {
            Log.i(
                TAG,
                "Uploading voice clip: durationMs=${audioRecorder.lastDurationMs}, meanAmplitude=${audioRecorder.lastMeanAmplitude}, bytes=${wavFile.length()}"
            )
        }

        state = State.TRANSCRIBING
        callbacks.onTranscribing()

        val prefs = context.prefs()
        val apiKey = SecretStore.getApiKey(context, Settings.PREF_OPENROUTER_API_KEY, Defaults.PREF_OPENROUTER_API_KEY)
        val selectedModel = prefs.getString(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL) ?: Defaults.PREF_VOICE_MODEL
        val customModel = prefs.getString(Settings.PREF_VOICE_MODEL_CUSTOM, Defaults.PREF_VOICE_MODEL_CUSTOM) ?: ""
        val savedPrompt = prefs.getString(
            Settings.PREF_VOICE_TRANSCRIPTION_PROMPT,
            Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT
        ) ?: Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT
        val transcriptionDictionary = prefs.getString(
            Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY,
            Defaults.PREF_VOICE_TRANSCRIPTION_DICTIONARY
        ) ?: Defaults.PREF_VOICE_TRANSCRIPTION_DICTIONARY
        val expectedLanguages = prefs.getString(
            Settings.PREF_VOICE_EXPECTED_LANGUAGES,
            Defaults.PREF_VOICE_EXPECTED_LANGUAGES
        ) ?: Defaults.PREF_VOICE_EXPECTED_LANGUAGES
        val languageHintEnabled = prefs.getBoolean(Settings.PREF_VOICE_LANGUAGE_HINT, Defaults.PREF_VOICE_LANGUAGE_HINT)
        val spaceHeuristicEnabled = prefs.getBoolean(Settings.PREF_VOICE_SPACE_HEURISTIC, Defaults.PREF_VOICE_SPACE_HEURISTIC)

        val model = resolveVoiceModel(selectedModel, customModel)
        if (model == null) {
            state = State.IDLE
            callbacks.onFinished()
            callbacks.onError(context.getString(R.string.voice_error_no_model))
            VoiceDiagnosticLog.log(context, "VIM", "stopRecording -> NO_MODEL error path")
            return
        }
        VoiceDiagnosticLog.log(context, "VIM", "stopRecording -> transitioning to TRANSCRIBING, model=$model, apiKeyLen=${apiKey.length}")
        val localeHint = if (languageHintEnabled) callbacks.getLocaleHint() else null
        val prompt = resolveVoicePrompt(savedPrompt, localeHint, transcriptionDictionary, expectedLanguages)
        val spacingContext = if (spaceHeuristicEnabled) callbacks.getSpacingContext() else null

        val client = OpenRouterClient(
            apiKey = apiKey,
            model = model,
            systemPrompt = prompt.systemPrompt,
            runtimeInstruction = prompt.runtimeInstruction,
        )
        val requestToken = activeTranscriptionToken + 1
        activeTranscriptionToken = requestToken
        transcriptionClient = client

        val crashHandler = Thread.UncaughtExceptionHandler { _, e ->
            Log.e(TAG, "Transcription thread crashed", e)
            finishTranscription(
                requestToken = requestToken,
                error = context.getString(R.string.voice_error_transcription_failed),
            )
        }
        val thread = Thread {
            VoiceDiagnosticLog.log(context, "VIM", "transcription thread running, token=$requestToken")
            try {
                VoiceDiagnosticLog.log(context, "VIM", "calling OpenRouterClient.transcribe()")
                val transcription = sanitizeTranscription(client.transcribe(wavFile))
                VoiceDiagnosticLog.log(context, "VIM", "transcribe() returned length=${transcription.length}")
                if (transcription.isBlank()) {
                    finishTranscription(
                        requestToken = requestToken,
                        error = context.getString(R.string.voice_error_transcription_failed),
                    )
                    return@Thread
                }
                val finalText = applySpacing(transcription, spacingContext)
                finishTranscription(requestToken = requestToken, result = finalText)
            } catch (e: InterruptedException) {
                if (BuildConfig.DEBUG) Log.i(TAG, "Transcription cancelled")
                VoiceDiagnosticLog.log(context, "VIM", "transcription thread InterruptedException (token=$requestToken)")
                finishTranscription(requestToken = requestToken)
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                VoiceDiagnosticLog.log(context, "VIM", "transcription thread Exception: ${e.javaClass.simpleName}: ${e.message}")
                finishTranscription(
                    requestToken = requestToken,
                    error = safeUserFacingError(e),
                )
            } finally {
                // Best-effort: delete the audio after the request, whether it succeeded or not.
                if (wavFile.exists()) wavFile.delete()
            }
        }.apply {
            name = "VoiceTranscription"
            uncaughtExceptionHandler = crashHandler
        }
        transcriptionThread = thread
        thread.start()
    }

    /** Cancel either a live recording or an in-flight upload. */
    @Synchronized
    fun cancelRecording() {
        VoiceDiagnosticLog.log(context, "VIM", "cancelRecording() state=$state caller=${VoiceDiagnosticLog.callerTrace(10)}")
        when (state) {
            State.RECORDING -> {
                audioRecorder.cancel()
                currentAudioFile?.delete()
                currentAudioFile = null
                state = State.IDLE
                callbacks.onFinished()
            }
            State.TRANSCRIBING -> {
                activeTranscriptionToken += 1
                transcriptionClient?.cancel()
                transcriptionThread?.interrupt()
                transcriptionThread = null
                transcriptionClient = null
                // The transcription thread's finally block will handle file deletion; only
                // reach in here if it couldn't start.
                currentAudioFile?.takeIf { it.exists() }?.delete()
                currentAudioFile = null
                state = State.IDLE
                callbacks.onFinished()
            }
            State.IDLE -> Unit
        }
    }

    private fun cacheAudioDir(): File {
        val dir = File(context.cacheDir, AUDIO_CACHE_SUBDIR)
        dir.mkdirs()
        return dir
    }

    private fun newRecorder(): AudioRecorder {
        // Sweep orphaned captures left over from process death before starting a new recording.
        runCatching {
            cacheAudioDir().listFiles()?.forEach { file ->
                if (file.name.startsWith("rec_") && file.extension.equals("wav", ignoreCase = true)) {
                    file.delete()
                }
            }
        }
        return AudioRecorder(outputFile = File(cacheAudioDir(), "rec_placeholder.wav"))
    }

    /**
     * Exception messages can occasionally echo request data (e.g. URLs, query strings).
     * For UI surfaces we prefer our own curated strings and only expose the exception
     * message when it comes from our own OpenRouterException, whose messages we control.
     */
    private fun safeUserFacingError(e: Throwable): String {
        if (e is OpenRouterException) {
            val raw = e.message
            if (!raw.isNullOrBlank()) {
                // Defense in depth: strip anything that looks like a bearer token or api key
                // in case the remote echoed one back into an error message we surface.
                return raw
                    .replace(Regex("(?i)Bearer\\s+\\S+"), "Bearer ***")
                    .replace(Regex("(?i)(\"?api[_-]?key\"?\\s*[:=]\\s*\"?)[^\"\\s,}]+"), "$1***")
            }
        }
        return context.getString(R.string.voice_error_transcription_failed)
    }

    private fun sanitizeTranscription(raw: String): String {
        // Strip control characters (category Cc) and bidi/format marks; the API occasionally
        // returns stray characters that corrupt the host editor when committed.
        val cleaned = raw.replace(Regex("[\\p{Cc}\\p{Cf}]"), "").trim()
        return if (cleaned.length > MAX_TRANSCRIPTION_LENGTH) cleaned.substring(0, MAX_TRANSCRIPTION_LENGTH) else cleaned
    }

    private fun finishTranscription(
        requestToken: Long,
        result: String? = null,
        error: String? = null,
    ) {
        mainHandler.post {
            if (activeTranscriptionToken != requestToken) {
                VoiceDiagnosticLog.log(context, "VIM", "finishTranscription token mismatch (requestToken=$requestToken active=$activeTranscriptionToken) - dropping")
                return@post
            }
            VoiceDiagnosticLog.log(
                context,
                "VIM",
                "finishTranscription token=$requestToken resultLen=${result?.length ?: -1} error=${error ?: "null"}"
            )
            transcriptionThread = null
            transcriptionClient = null
            state = State.IDLE
            callbacks.onFinished()
            if (!result.isNullOrEmpty()) {
                callbacks.onTranscriptionResult(result)
            } else if (!error.isNullOrEmpty()) {
                callbacks.onError(error)
            }
        }
    }

    /**
     * Prepends a space when the cursor sits immediately after a letter/digit, and appends one
     * when the next char is a letter/digit. Keeps `"hello".world` from becoming `"hello"world`.
     */
    private fun applySpacing(text: String, ctx: SpacingContext?): String {
        if (text.isEmpty() || ctx == null) return text
        val needsLeading = ctx.charBefore?.let { isWordChar(it) && !text.first().isWhitespace() } == true
        val needsTrailing = ctx.charAfter?.let { isWordChar(it) && !text.last().isWhitespace() } == true
        val prefix = if (needsLeading) " " else ""
        val suffix = if (needsTrailing) " " else ""
        return prefix + text + suffix
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit()

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        @Suppress("DEPRECATION")
        val activeNetwork = cm.activeNetworkInfo ?: return false
        @Suppress("DEPRECATION")
        return activeNetwork.isConnected
    }
}
