// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
        private const val WAV_HEADER_SIZE = 44
        private const val MIN_RECORDING_DURATION_MS = 400L
        // Mean absolute amplitude threshold for 16-bit PCM. Typical silence ~0-80; quiet speech ~300+.
        private const val SILENCE_AMPLITUDE_THRESHOLD = 180.0
        private const val MAX_TRANSCRIPTION_LENGTH = 10_000
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
    private var audioRecorder: AudioRecorder = AudioRecorder()
    private var state = State.IDLE
    @Volatile private var transcriptionThread: Thread? = null

    fun getState() = state

    /** Exposed so UI can render a live amplitude meter. */
    fun getCurrentAmplitude(): Double = audioRecorder.currentAmplitude

    /** Exposed so UI can render an elapsed-time counter. */
    fun getCurrentDurationMs(): Long = audioRecorder.currentDurationMs

    fun startRecording() {
        if (state != State.IDLE) return

        val prefs = context.prefs()

        if (!prefs.getBoolean(Settings.PREF_VOICE_INPUT_ENABLED, Defaults.PREF_VOICE_INPUT_ENABLED)) {
            Toast.makeText(context, R.string.voice_error_not_enabled, Toast.LENGTH_SHORT).show()
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

        audioRecorder = AudioRecorder(
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

    fun stopRecording() {
        if (state != State.RECORDING) return

        val wavData = audioRecorder.stop()
        if (wavData.size <= WAV_HEADER_SIZE) {
            state = State.IDLE
            callbacks.onFinished()
            callbacks.onError(context.getString(R.string.voice_error_no_audio))
            return
        }
        if (audioRecorder.lastDurationMs < MIN_RECORDING_DURATION_MS) {
            state = State.IDLE
            callbacks.onFinished()
            callbacks.onError(context.getString(R.string.voice_error_too_short))
            return
        }
        if (audioRecorder.lastMeanAmplitude < SILENCE_AMPLITUDE_THRESHOLD) {
            if (BuildConfig.DEBUG) Log.i(TAG, "Rejecting silent recording (amp=${audioRecorder.lastMeanAmplitude})")
            state = State.IDLE
            callbacks.onFinished()
            callbacks.onError(context.getString(R.string.voice_error_silent))
            return
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
            return
        }
        val localeHint = if (languageHintEnabled) callbacks.getLocaleHint() else null
        val prompt = resolveVoicePrompt(savedPrompt, localeHint, transcriptionDictionary, expectedLanguages)
        val spacingContext = if (spaceHeuristicEnabled) callbacks.getSpacingContext() else null

        val client = OpenRouterClient(
            apiKey = apiKey,
            model = model,
            systemPrompt = prompt.systemPrompt,
            runtimeInstruction = prompt.runtimeInstruction,
        )

        val crashHandler = Thread.UncaughtExceptionHandler { _, e ->
            Log.e(TAG, "Transcription thread crashed", e)
            mainHandler.post {
                transcriptionThread = null
                state = State.IDLE
                callbacks.onFinished()
                callbacks.onError(context.getString(R.string.voice_error_transcription_failed))
            }
        }
        val thread = Thread {
            try {
                val transcription = sanitizeTranscription(client.transcribe(wavData))
                val finalText = applySpacing(transcription, spacingContext)
                mainHandler.post {
                    transcriptionThread = null
                    state = State.IDLE
                    callbacks.onFinished()
                    if (finalText.isNotEmpty()) {
                        callbacks.onTranscriptionResult(finalText)
                    }
                }
            } catch (e: InterruptedException) {
                if (BuildConfig.DEBUG) Log.i(TAG, "Transcription cancelled")
                mainHandler.post {
                    transcriptionThread = null
                    state = State.IDLE
                    callbacks.onFinished()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                mainHandler.post {
                    transcriptionThread = null
                    state = State.IDLE
                    callbacks.onFinished()
                    callbacks.onError(e.message ?: context.getString(R.string.voice_error_transcription_failed))
                }
            }
        }.apply {
            name = "VoiceTranscription"
            uncaughtExceptionHandler = crashHandler
        }
        transcriptionThread = thread
        thread.start()
    }

    /** Cancel either a live recording or an in-flight upload. */
    fun cancelRecording() {
        when (state) {
            State.RECORDING -> {
                audioRecorder.cancel()
                state = State.IDLE
                callbacks.onFinished()
            }
            State.TRANSCRIBING -> {
                transcriptionThread?.interrupt()
                // State transition is handled by the thread's InterruptedException branch,
                // which posts back to the main thread.
            }
            State.IDLE -> Unit
        }
    }

    private fun sanitizeTranscription(raw: String): String {
        // Strip control characters (category Cc) and bidi/format marks; the API occasionally
        // returns stray characters that corrupt the host editor when committed.
        val cleaned = raw.replace(Regex("\\p{Cc}"), "").trim()
        return if (cleaned.length > MAX_TRANSCRIPTION_LENGTH) cleaned.substring(0, MAX_TRANSCRIPTION_LENGTH) else cleaned
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
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
