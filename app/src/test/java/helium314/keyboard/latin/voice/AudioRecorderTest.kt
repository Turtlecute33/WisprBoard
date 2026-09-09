// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The recorder's arithmetic had no coverage at all, and one of these helpers gates whether a
 * dictation is uploaded or silently deleted as "no speech".
 */
@RunWith(RobolectricTestRunner::class)
class AudioRecorderTest {

    private fun recorder(gain: Float = 1f) =
        AudioRecorder(outputFile = File("unused-in-these-tests.wav"), inputGain = gain)

    /** Little-endian PCM16 buffer from signed sample values. */
    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = (s and 0xff).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun samplesOf(buf: ByteArray): List<Int> =
        (buf.indices step 2).map { i ->
            (((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort()).toInt()
        }

    @Test
    fun chunkMeanIsTheMeanOfAbsoluteSampleValues() {
        assertEquals(300.0, recorder().chunkMeanAmplitude(pcm(-300, 300, -300, 300), 8), 1e-9)
        assertEquals(150.0, recorder().chunkMeanAmplitude(pcm(0, 300), 4), 1e-9)
    }

    @Test
    fun chunkMeanIgnoresAnOddTrailingByte() {
        // AudioRecord.read can return an odd byte count; the last half-sample must be dropped
        // rather than read past the end or paired with uninitialised memory.
        val buf = pcm(-1000, 1000)
        assertEquals(1000.0, recorder().chunkMeanAmplitude(buf, 3), 1e-9)
    }

    @Test
    fun chunkMeanOfTooShortABufferIsZero() {
        assertEquals(0.0, recorder().chunkMeanAmplitude(pcm(500), 1), 1e-9)
        assertEquals(0.0, recorder().chunkMeanAmplitude(ByteArray(0), 0), 1e-9)
    }

    @Test
    fun peakIsNeverBelowTheMeanSoTheSilenceGateOnlyRelaxes() {
        // The whole justification for switching the post-stop gate from mean to peak: a clip that
        // passes the mean test provably passes the peak test, so no dictation that works today
        // can start being rejected.
        val speechThenSilence = pcm(900, -900, 0, 0, 0, 0, 0, 0)
        val mean = recorder().chunkMeanAmplitude(speechThenSilence, speechThenSilence.size)
        val loudChunkOnly = pcm(900, -900)
        val peak = recorder().chunkMeanAmplitude(loudChunkOnly, loudChunkOnly.size)
        assertEquals(225.0, mean, 1e-9)
        assertEquals(900.0, peak, 1e-9)
        assertTrue(peak >= mean)
        // The real-world case from the audit: 3 s of speech inside a 60 s clip. The mean falls
        // under the 80 threshold and the recording used to be deleted; the peak does not.
        assertTrue(mean * (3.0 / 60.0) < 80.0)
        assertTrue(peak >= 80.0)
    }

    @Test
    fun gainScalesSamplesAndClipsToThePcm16Range() {
        val buf = pcm(1000, -1000, 20000, -20000)
        recorder().applyGain(buf, buf.size, 2f)
        assertEquals(listOf(2000, -2000, 32767, -32768), samplesOf(buf))
    }

    @Test
    fun unityGainLeavesEveryBoundarySampleUntouched() {
        val buf = pcm(32767, -32768, 0, 1, -1)
        val before = samplesOf(buf)
        recorder().applyGain(buf, buf.size, 1f)
        assertEquals(before, samplesOf(buf))
    }

    @Test
    fun gainStopsAtTheReportedLengthAndNotTheArrayEnd() {
        val buf = pcm(100, 100, 100)
        recorder().applyGain(buf, 2, 4f)
        assertEquals(listOf(400, 100, 100), samplesOf(buf))
    }

    @Test
    fun readChunkIsOneShortSliceAndNeverExceedsTheRingBuffer() {
        // The 3-arg AudioRecord.read blocks until it fills the array, so reading a whole
        // bufferSize means every read waits for the ring buffer to be 100% full and the
        // headroom at the allocation site buys nothing.
        val r = recorder()
        assertEquals(1280, r.readChunkBytes(8192))
        assertTrue(r.readChunkBytes(8192) < 8192)
        // A device whose minimum buffer is tiny must not be asked for more than it has.
        assertEquals(640, r.readChunkBytes(640))
        // And never a zero-length or odd read, which would spin or split a sample.
        assertTrue(r.readChunkBytes(1) >= 2)
        assertEquals(0, r.readChunkBytes(8192) % 2)
    }
}
