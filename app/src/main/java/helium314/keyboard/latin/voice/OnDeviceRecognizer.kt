// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import java.util.Locale

private const val TAG = "OnDeviceRecognizer"

// SpeechRecognizer error codes above 9 were only added in API 33. Referencing the constants would
// fail the NewApi lint gate on API 31/32 builds, so match the literals instead.
private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
private const val ERROR_LANGUAGE_UNAVAILABLE = 13

// onRmsChanged reports dB in roughly this band. Rescale it onto the 0..6000 mean-amplitude range
// AmplitudeMeterView expects so the recording meter behaves identically for both engines.
private const val RMS_DB_FLOOR = -2f
private const val RMS_DB_CEILING = 10f
private const val AMPLITUDE_FULL_SCALE = 6000.0

// A recognition service that binds but never answers would otherwise leave the overlay stuck on
// "transcribing" until the user cancels. The cloud path gets this for free from its HTTP timeouts.
private const val DECODE_TIMEOUT_MS = 15_000L

/**
 * Whether this device can recognise speech locally: API 31+ and a recognition service that actually
 * ships an on-device model. Cheap enough to call per recording.
 */
internal fun isOnDeviceRecognitionAvailable(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    return runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
}

/** Maps a [RecognitionListener.onRmsChanged] reading onto the meter's mean-amplitude scale. */
internal fun amplitudeFromRms(rmsdB: Float): Double {
    if (rmsdB.isNaN()) return 0.0
    val normalized = ((rmsdB - RMS_DB_FLOOR) / (RMS_DB_CEILING - RMS_DB_FLOOR)).coerceIn(0f, 1f)
    return normalized.toDouble() * AMPLITUDE_FULL_SCALE
}

/**
 * Joins the text of the segments of a segmented session (UX-004). Each segment is trimmed and
 * blank segments are dropped. A space goes between two segments, but not next to Chinese or
 * Japanese characters, because these scripts do not put spaces between sentences.
 */
internal fun joinSegments(segments: List<String>): String {
    val joined = StringBuilder()
    for (raw in segments) {
        val segment = raw.trim()
        if (segment.isEmpty()) continue
        if (joined.isNotEmpty()
            && !isSpacelessScript(joined.codePointBefore(joined.length))
            && !isSpacelessScript(segment.codePointAt(0))
        ) {
            joined.append(' ')
        }
        joined.append(segment)
    }
    return joined.toString()
}

/** CJK radicals to CJK ideographs (kana and CJK punctuation included), compatibility ideographs,
 *  full-width forms and the supplementary ideograph planes. */
private fun isSpacelessScript(codePoint: Int): Boolean =
    codePoint in 0x2E80..0x9FFF || codePoint in 0xF900..0xFAFF ||
        codePoint in 0xFF00..0xFFEF || codePoint in 0x20000..0x3FFFF

/** Maps a platform recognition error onto the user-facing message for it. */
@StringRes
internal fun onDeviceErrorMessageRes(error: Int): Int = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
        R.string.voice_error_silent
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
        R.string.voice_error_no_permission
    ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE ->
        R.string.voice_error_on_device_language
    else -> R.string.voice_error_on_device_failed
}

/**
 * Speech recognition that never leaves the device.
 *
 * Wraps [SpeechRecognizer.createOnDeviceSpeechRecognizer], which routes audio to the system
 * recognition service's local model. No API key, no network, and no audio file: the platform owns
 * the microphone for the whole session, so [AudioRecorder] and [OpenRouterClient] sit this one out.
 *
 * The platform recognizer is main-thread-only — every method here, and every callback it emits,
 * runs on the main looper. Callers must respect that.
 *
 * Only [start] needs API 31; the rest of the surface is either plain state or [SpeechRecognizer]
 * calls that have existed since API 8. Annotating the class instead would force every caller —
 * including the meter polling [currentAmplitude] 12 times a second — behind an SDK check.
 */
class OnDeviceRecognizer(private val context: Context) {

    interface Listener {
        /** The microphone is open and the recognizer is listening. */
        fun onReadyForSpeech()
        /** The recognizer stopped listening and is now decoding. */
        fun onEndOfSpeech()
        fun onResult(text: String)
        fun onError(@StringRes messageRes: Int)
        /** Fired instead of [onEndOfSpeech] when the session hit the configured duration cap. */
        fun onMaxDurationReached()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null
    private var startedAtMs: Long = 0L
    private var stoppedDurationMs: Long = 0L
    private var maxDurationRunnable: Runnable? = null
    private var decodeTimeoutRunnable: Runnable? = null
    /** Guards against the platform emitting a result or error after we tore the session down. */
    private var sessionActive = false
    /**
     * Android 13+: the session continues across pauses and only Stop or the duration cap ends it
     * (UX-004). The recognizer sends the text in segments, which are kept here until the end.
     */
    private var segmented = false
    private val segments = ArrayList<String>()

    /** Rolling amplitude on the same 0..32767 scale [AudioRecorder.currentAmplitude] uses. */
    @Volatile var currentAmplitude: Double = 0.0
        private set

    val currentDurationMs: Long
        get() = if (startedAtMs == 0L) stoppedDurationMs else SystemClock.elapsedRealtime() - startedAtMs

    /**
     * Opens the microphone. Returns false if the recognizer could not be created or started at all,
     * in which case no callback fires and the caller owns the error message.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun start(listener: Listener, locale: Locale?, maxDurationMs: Long): Boolean {
        release()
        val speechRecognizer = runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }.getOrElse {
            Log.e(TAG, "Could not create the on-device recognizer", it)
            return false
        }
        this.listener = listener
        recognizer = speechRecognizer
        sessionActive = true
        startedAtMs = SystemClock.elapsedRealtime()
        stoppedDurationMs = 0L
        currentAmplitude = 0.0

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // Redundant for an on-device recognizer, but it makes the no-network contract explicit.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (locale != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxDurationMs > 0L) {
                // The silence that ends the session is as long as the session cap, so a pause does
                // not end it. A recognizer that ignores these extras ends at the first pause, as before.
                segmented = true
                putExtra(
                    RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS
                )
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, maxDurationMs)
            }
        }
        speechRecognizer.setRecognitionListener(recognitionListener)
        runCatching { speechRecognizer.startListening(intent) }.getOrElse {
            Log.e(TAG, "startListening failed", it)
            release()
            return false
        }

        if (maxDurationMs > 0L) {
            val runnable = Runnable {
                maxDurationRunnable = null
                if (!sessionActive) return@Runnable
                this.listener?.onMaxDurationReached()
                stop()
            }
            maxDurationRunnable = runnable
            mainHandler.postDelayed(runnable, maxDurationMs)
        }
        return true
    }

    /** Asks the recognizer to finish the utterance and decode what it has. */
    fun stop() {
        cancelMaxDurationTimer()
        freezeDuration()
        armDecodeTimeout()
        runCatching { recognizer?.stopListening() }.onFailure { Log.w(TAG, "stopListening failed", it) }
        // A segmented session ignores the end of speech at each pause, so the stop is the end of speech.
        if (segmented && sessionActive) {
            currentAmplitude = 0.0
            listener?.onEndOfSpeech()
        }
    }

    /** Abandons the session; no further callbacks are delivered. */
    fun cancel() {
        sessionActive = false
        runCatching { recognizer?.cancel() }.onFailure { Log.w(TAG, "cancel failed", it) }
        release()
    }

    /** Tears down the platform recognizer. Safe to call repeatedly. */
    fun release() {
        sessionActive = false
        cancelMaxDurationTimer()
        cancelDecodeTimeout()
        freezeDuration()
        listener = null
        currentAmplitude = 0.0
        segmented = false
        segments.clear()
        recognizer?.let { r ->
            runCatching {
                r.setRecognitionListener(null)
                r.destroy()
            }.onFailure { Log.w(TAG, "destroy failed", it) }
        }
        recognizer = null
    }

    private fun cancelMaxDurationTimer() {
        maxDurationRunnable?.let { mainHandler.removeCallbacks(it) }
        maxDurationRunnable = null
    }

    private fun armDecodeTimeout() {
        cancelDecodeTimeout()
        val runnable = Runnable {
            decodeTimeoutRunnable = null
            if (!sessionActive) return@Runnable
            Log.w(TAG, "On-device recognition produced no result within ${DECODE_TIMEOUT_MS}ms")
            finishWithSegmentsOr(R.string.voice_error_on_device_failed)
        }
        decodeTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, DECODE_TIMEOUT_MS)
    }

    private fun cancelDecodeTimeout() {
        decodeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        decodeTimeoutRunnable = null
    }

    private fun freezeDuration() {
        if (startedAtMs != 0L) {
            stoppedDurationMs = SystemClock.elapsedRealtime() - startedAtMs
            startedAtMs = 0L
        }
    }

    /**
     * Ends the session, then hands the terminal result to the caller.
     *
     * The teardown is posted rather than run inline because this is usually reached from inside a
     * [RecognitionListener] callback, and destroying a recognizer from its own callback is asking
     * an OEM implementation for trouble. Clearing the listener and the active flag first is what
     * actually matters: it stops any further callback, so the posted [release] only has to free the
     * binding and the microphone indicator.
     */
    private fun finishWith(deliver: (Listener) -> Unit) {
        val target = listener
        sessionActive = false
        listener = null
        cancelMaxDurationTimer()
        cancelDecodeTimeout()
        freezeDuration()
        currentAmplitude = 0.0
        mainHandler.post { release() }
        target?.let(deliver)
    }

    /** Delivers the segments that arrived. With no segments, delivers the error. */
    private fun finishWithSegmentsOr(@StringRes errorRes: Int) {
        val text = joinSegments(segments)
        finishWith { if (text.isEmpty()) it.onError(errorRes) else it.onResult(text) }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (sessionActive) listener?.onReadyForSpeech()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            if (!sessionActive) return // keep the no-callbacks-after-teardown invariant uniform
            currentAmplitude = amplitudeFromRms(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!sessionActive || segmented) return
            freezeDuration()
            cancelMaxDurationTimer()
            currentAmplitude = 0.0
            armDecodeTimeout()
            listener?.onEndOfSpeech()
        }

        override fun onError(error: Int) {
            if (!sessionActive) return
            // Only the numeric code is logged — nothing the user said reaches logcat, even here.
            Log.i(TAG, "On-device recognition error: $error")
            // Segments that arrived before the error are kept.
            finishWithSegmentsOr(onDeviceErrorMessageRes(error))
        }

        override fun onResults(results: Bundle?) {
            if (!sessionActive) return
            segments.add(firstResult(results))
            finishWithSegmentsOr(R.string.voice_error_silent)
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            if (sessionActive) segments.add(firstResult(segmentResults))
        }

        override fun onEndOfSegmentedSession() {
            if (sessionActive) finishWithSegmentsOr(R.string.voice_error_silent)
        }

        private fun firstResult(results: Bundle?): String =
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
