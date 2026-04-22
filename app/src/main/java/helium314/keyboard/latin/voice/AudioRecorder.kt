// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records audio from the microphone directly to a WAV file on disk.
 *
 * Bytes are appended to the output file chunk-by-chunk while recording, so peak
 * heap usage stays bounded regardless of the recording length. A placeholder
 * WAV header is written up-front and rewritten in [stop] once the sample count
 * is known.
 *
 * Additionally exposes live telemetry during recording:
 *  - [currentAmplitude]: rolling mean absolute sample amplitude (0..32767)
 *  - [currentDurationMs]: elapsed duration since start()
 * and, post-stop: [lastDurationMs], [lastMeanAmplitude].
 */
class AudioRecorder(
    private val outputFile: File,
    private val maxDurationMs: Long = 90_000L,
    /** If >0, stop after this many contiguous ms of silence once the user has spoken at least once. */
    private val autoStopSilenceMs: Long = 0L,
) {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // Amplitude threshold (0..32767) separating silence from speech for auto-stop heuristic.
        private const val SPEECH_AMPLITUDE_THRESHOLD = 300.0
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var pcmOutputFile: RandomAccessFile? = null
    @Volatile private var pcmBytesWritten: Long = 0L
    @Volatile private var amplitudeSum: Long = 0L
    @Volatile private var amplitudeCount: Long = 0L
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    @Volatile private var isRecording = false
    @Volatile private var recordingStartMs: Long = 0L

    /** Duration of the last completed recording, in milliseconds. Updated by [stop]. */
    @Volatile var lastDurationMs: Long = 0L
        private set

    /** Mean absolute sample amplitude of the last completed recording (0..32767). Updated by [stop]. */
    @Volatile var lastMeanAmplitude: Double = 0.0
        private set

    /** Rolling mean amplitude of the most recent audio chunk, read-safe from any thread. */
    @Volatile var currentAmplitude: Double = 0.0
        private set

    /** Live elapsed time since start(), 0 when idle. */
    val currentDurationMs: Long
        get() = if (isRecording && recordingStartMs > 0) System.currentTimeMillis() - recordingStartMs else 0L

    var onMaxDurationReached: (() -> Unit)? = null
    var onAutoStopSilence: (() -> Unit)? = null

    fun start(): Boolean {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return false
        }

        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            attachAudioEffects(audioRecord!!.audioSessionId)

            outputFile.parentFile?.mkdirs()
            val raf = RandomAccessFile(outputFile, "rw")
            raf.setLength(0L)
            // Write a 44-byte placeholder; stop() rewrites with accurate sizes.
            raf.write(ByteArray(WAV_HEADER_SIZE))
            pcmOutputFile = raf
            pcmBytesWritten = 0L
            amplitudeSum = 0L
            amplitudeCount = 0L
            currentAmplitude = 0.0
            isRecording = true
            recordingStartMs = System.currentTimeMillis()
            audioRecord?.startRecording()

            recordingThread = Thread({
                runRecordingLoop(bufferSize)
            }, "AudioRecorder")
            recordingThread?.start()
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission not granted", e)
            cleanupStartFailure()
            false
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "AudioRecord construction failed", e)
            cleanupStartFailure()
            false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioRecord failed to start", e)
            cleanupStartFailure()
            false
        } catch (e: java.io.IOException) {
            Log.e(TAG, "Failed to open recording output file", e)
            cleanupStartFailure()
            false
        }
    }

    private fun runRecordingLoop(bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        var hasSpoken = false
        var silenceRunStartMs = 0L
        while (isRecording) {
            if (System.currentTimeMillis() - recordingStartMs > maxDurationMs) {
                isRecording = false
                onMaxDurationReached?.invoke()
                break
            }
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: AudioRecord.ERROR_INVALID_OPERATION
            when {
                read > 0 -> {
                    try {
                        pcmOutputFile?.write(buffer, 0, read)
                        pcmBytesWritten += read
                    } catch (e: java.io.IOException) {
                        Log.e(TAG, "Failed to write PCM chunk", e)
                        isRecording = false
                        break
                    }
                    val amp = chunkMeanAmplitude(buffer, read)
                    currentAmplitude = amp
                    // Running mean for post-stop gate — avoids re-reading the whole file.
                    val samples = read / 2
                    amplitudeSum += (amp * samples).toLong()
                    amplitudeCount += samples
                    if (autoStopSilenceMs > 0L) {
                        val now = System.currentTimeMillis()
                        if (amp >= SPEECH_AMPLITUDE_THRESHOLD) {
                            hasSpoken = true
                            silenceRunStartMs = 0L
                        } else if (hasSpoken) {
                            if (silenceRunStartMs == 0L) silenceRunStartMs = now
                            else if (now - silenceRunStartMs >= autoStopSilenceMs) {
                                isRecording = false
                                onAutoStopSilence?.invoke()
                                break
                            }
                        }
                    }
                }
                read == 0 -> Unit
                else -> {
                    Log.e(TAG, "AudioRecord read error: $read")
                    isRecording = false
                }
            }
        }
    }

    /**
     * Finalizes the WAV header and returns the output file.
     * Callers are responsible for deleting the file after consuming it.
     * Returns null if no usable audio was captured.
     */
    fun stop(): File? {
        teardownRecorder()

        val pcmBytes = pcmBytesWritten
        lastDurationMs = if (pcmBytes >= 2) (pcmBytes * 1000L) / (SAMPLE_RATE.toLong() * 2L) else 0L
        lastMeanAmplitude = if (amplitudeCount > 0) amplitudeSum.toDouble() / amplitudeCount else 0.0
        currentAmplitude = 0.0

        val raf = pcmOutputFile
        pcmOutputFile = null
        if (raf == null || pcmBytes < 2) {
            try { raf?.close() } catch (_: Throwable) {}
            if (outputFile.exists()) outputFile.delete()
            return null
        }
        return try {
            writeWavHeader(raf, pcmBytes.toInt())
            raf.close()
            outputFile
        } catch (e: java.io.IOException) {
            Log.e(TAG, "Failed to finalize WAV header", e)
            try { raf.close() } catch (_: Throwable) {}
            if (outputFile.exists()) outputFile.delete()
            null
        }
    }

    fun cancel() {
        teardownRecorder()
        closeOutputSafely()
        if (outputFile.exists()) outputFile.delete()
        pcmBytesWritten = 0L
        amplitudeSum = 0L
        amplitudeCount = 0L
        lastDurationMs = 0L
        lastMeanAmplitude = 0.0
        currentAmplitude = 0.0
    }

    private fun teardownRecorder() {
        isRecording = false
        try { audioRecord?.stop() } catch (e: IllegalStateException) { Log.w(TAG, "AudioRecord.stop() failed", e) }
        recordingThread?.let { t ->
            t.interrupt()
            try { t.join(2000) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
        recordingThread = null
        releaseAudioEffects()
        audioRecord?.release()
        audioRecord = null
        recordingStartMs = 0L
    }

    private fun cleanupStartFailure() {
        isRecording = false
        recordingStartMs = 0L
        closeOutputSafely()
        releaseAudioEffects()
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
    }

    private fun closeOutputSafely() {
        try { pcmOutputFile?.close() } catch (_: Throwable) {}
        pcmOutputFile = null
    }

    private fun attachAudioEffects(sessionId: Int) {
        // Free perceptual quality boost when the device supports it. Silent no-op otherwise.
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Throwable) {
            if (BuildConfig.DEBUG) Log.w(TAG, "NoiseSuppressor unavailable", e)
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Throwable) {
            if (BuildConfig.DEBUG) Log.w(TAG, "AGC unavailable", e)
        }
    }

    private fun releaseAudioEffects() {
        try { noiseSuppressor?.release() } catch (_: Throwable) {}
        try { agc?.release() } catch (_: Throwable) {}
        noiseSuppressor = null
        agc = null
    }

    private fun chunkMeanAmplitude(buf: ByteArray, length: Int): Double {
        if (length < 2) return 0.0
        var sum = 0L
        var count = 0
        var i = 0
        val end = length - 1
        while (i < end) {
            val lo = buf[i].toInt() and 0xff
            val hi = buf[i + 1].toInt()
            val signed = ((hi shl 8) or lo).toShort().toInt()
            sum += if (signed < 0) -signed else signed
            count++
            i += 2
        }
        return if (count > 0) sum.toDouble() / count else 0.0
    }

    private fun writeWavHeader(raf: RandomAccessFile, pcmSize: Int) {
        val totalDataLen = pcmSize + 36
        val byteRate = SAMPLE_RATE * 1 * 16 / 8
        val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(totalDataLen)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(pcmSize)
        }
        raf.seek(0L)
        raf.write(header.array())
    }
}

private const val WAV_HEADER_SIZE = 44
