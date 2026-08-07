package dev.sebastian.vozlocal.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
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
}
