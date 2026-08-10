package dev.sebastian.vozlocal.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Regression guard for the audit finding that [AudioRecorder] wasn't actually
 * synchronized: two callers (ViewModel + Accessibility Service) could both check
 * `isRecording` and open the mic.
 *
 * The recorder uses Android's native `AudioRecord`, which is exercised through
 * Robolectric's `ShadowAudioRecord` so `getMinBufferSize()`, the constructor and
 * the read path are stubbed without touching the audio HAL.
 *
 * LIMITATION: native/device audio behavior (real PCM capture, RMS values) is not
 * covered here — that belongs in an instrumented/device test. This test covers the
 * state-transition contract that the audit flagged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioRecorderSyncTest {

    @Test
    fun isRecording_falseOnFreshInstance() {
        assertFalse(AudioRecorder().isRecording())
    }

    @Test
    fun release_isIdempotent() {
        val recorder = AudioRecorder()
        recorder.release()
        recorder.release() // must not throw
        assertFalse(recorder.isRecording())
    }

    @Test
    fun stopRecording_emptyArrayWhenNeverStarted() {
        val samples = AudioRecorder().stopRecording()
        assertTrue(samples.isEmpty())
    }

    @Test
    fun snapshotRecording_emptyArrayWhenNeverStarted() {
        val recorder = AudioRecorder()

        assertTrue(recorder.snapshotRecording().isEmpty())
        assertFalse(recorder.isRecording())
    }

    @Test
    fun snapshotRecording_returnsIndependentNonConsumingCopy() {
        val recorder = AudioRecorder()
        appendSamplesForTest(recorder, shortArrayOf(0, 8192, -16384, 32767))

        val first = recorder.snapshotRecording()
        first[1] = -1f
        val second = recorder.snapshotRecording()

        assertArrayEquals(
            floatArrayOf(0f, 0.25f, -0.5f, 32767 / 32768f),
            second,
            0.000001f
        )
        assertEquals("snapshot must not consume accumulated PCM", first.size, second.size)
    }

    @Test
    fun snapshotRecording_canLimitCopyToLatestSamples() {
        val recorder = AudioRecorder()
        appendSamplesForTest(recorder, shortArrayOf(0, 4096, 8192, 12288))

        assertArrayEquals(
            floatArrayOf(0.25f, 0.375f),
            recorder.snapshotRecording(maxSamples = 2),
            0.000001f
        )
        assertTrue(recorder.snapshotRecording(maxSamples = 0).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            recorder.snapshotRecording(maxSamples = -1)
        }
    }

    @Test
    fun stopRecording_waitsForCaptureReaderToExit() {
        val recorder = AudioRecorder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        assertTrue("Robolectric AudioRecord should initialize", recorder.startRecording(scope))
        val readerJob = AudioRecorder::class.java
            .getDeclaredField("recordingJob")
            .apply { isAccessible = true }
            .get(recorder) as Job

        recorder.stopRecording()

        // The snapshot/reset in stopRecording must happen only after this job has
        // completed; otherwise an in-flight AudioRecord.read() can append PCM after
        // the returned samples have already been copied.
        assertTrue("capture reader must be complete before stop returns", readerJob.isCompleted)
        scope.cancel()
    }

    @Test
    fun concurrentStartStop_leavesRecorderInCleanState() {
        val recorder = AudioRecorder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val startThreads = 8
        val stopThreads = 8

        // N threads try to start simultaneously — the synchronized(state) guard must
        // ensure exactly one wins (or at worst, a deterministic winner) with no torn state.
        val go = CountDownLatch(1)
        val startDone = CountDownLatch(startThreads)
        repeat(startThreads) {
            thread {
                go.await()
                try {
                    recorder.startRecording(scope)
                } catch (e: Exception) {
                    // Ignore: a losing caller may see isRecording already true and return.
                }
                startDone.countDown()
            }
        }
        go.countDown()
        assertTrue(startDone.await(10, TimeUnit.SECONDS))

        // M threads stop simultaneously. stopRecording is idempotent under the same lock.
        val stopGo = CountDownLatch(1)
        val stopDone = CountDownLatch(stopThreads)
        repeat(stopThreads) {
            thread {
                stopGo.await()
                try {
                    recorder.stopRecording()
                } catch (e: Exception) {
                    // Ignore: concurrent stop is expected to be safe, but we assert state below.
                }
                stopDone.countDown()
            }
        }
        stopGo.countDown()
        assertTrue(stopDone.await(10, TimeUnit.SECONDS))

        // Clean final state: not recording, no leaked recorder.
        assertFalse(recorder.isRecording())

        // Releasing afterwards must also be safe.
        recorder.release()
        scope.cancel()
    }

    private fun appendSamplesForTest(recorder: AudioRecorder, samples: ShortArray) {
        val buffer = AudioRecorder::class.java
            .getDeclaredField("floatBuffer")
            .apply { isAccessible = true }
            .get(recorder) ?: error("AudioRecorder.floatBuffer must be initialized")
        val appendPcm16 = buffer.javaClass
            .getDeclaredMethod("appendPCM16", ShortArray::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }

        synchronized(buffer) {
            appendPcm16.invoke(buffer, samples, samples.size)
        }
    }
}
