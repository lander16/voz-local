package dev.sebastian.vozlocal.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Lifecycle regressions for the recorder's failure and asynchronous shutdown contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioRecorderSyncTest {

    @Test
    fun startFailure_rollsBackAndReleasesCandidate() {
        val handle = FakeHandle(startFailure = IllegalStateException("unavailable"))
        val recorder = AudioRecorder(AudioRecordFactory { _, _ -> handle })
        val scope = testScope()

        assertFalse(recorder.startRecording(scope))
        assertFalse(recorder.isRecording())
        assertEquals(1, handle.releaseCalls.get())
        scope.cancel()
    }

    @Test
    fun terminalReadError_releasesRecorderAndReportsFailure() = runBlocking {
        val handle = FakeHandle(reads = mutableListOf(-3))
        val errors = CopyOnWriteArrayList<AudioRecordingException>()
        val recorder = AudioRecorder(AudioRecordFactory { _, _ -> handle })
        val scope = testScope()

        assertTrue(recorder.startRecording(scope, onRecordingError = errors::add))
        withTimeout(2_000) { while (recorder.isRecording()) delay(10) }

        assertEquals(1, errors.size)
        assertTrue(errors.single().message!!.contains("-3"))
        assertEquals(1, handle.stopCalls.get())
        assertEquals(1, handle.releaseCalls.get())
        scope.cancel()
    }

    @Test
    fun stopRecording_keepsLastReadBlockAndReleasesWhenStopFails() = runBlocking {
        val readEntered = CountDownLatch(1)
        val allowRead = CountDownLatch(1)
        val handle = FakeHandle(
            reads = mutableListOf(1),
            readEntered = readEntered,
            allowRead = allowRead,
            stopFailure = IllegalStateException("stop failed")
        )
        val recorder = AudioRecorder(AudioRecordFactory { _, _ -> handle })
        val scope = testScope()

        assertTrue(recorder.startRecording(scope))
        assertTrue(readEntered.await(2, TimeUnit.SECONDS))
        allowRead.countDown()
        val samples = recorder.stopRecording()

        assertArrayEquals(floatArrayOf(0.5f), samples, 0.0001f)
        assertFalse(recorder.isRecording())
        assertEquals(1, handle.releaseCalls.get())
        scope.cancel()
    }

    @Test
    fun discardRecording_doesNotCopyAccumulatedPcm() = runBlocking {
        val handle = FakeHandle(reads = mutableListOf(1))
        val recorder = AudioRecorder(AudioRecordFactory { _, _ -> handle })
        val scope = testScope()

        assertTrue(recorder.startRecording(scope))
        withTimeout(2_000) { while (recorder.snapshotRecording().isEmpty()) delay(10) }
        recorder.discardRecording()

        assertTrue(recorder.snapshotRecording().isEmpty())
        assertFalse(recorder.isRecording())
        scope.cancel()
    }

    @Test
    fun cancelledOwnerScope_releasesRecorderAndReconcilesState() = runBlocking {
        val handle = FakeHandle(reads = mutableListOf(0))
        val recorder = AudioRecorder(AudioRecordFactory { _, _ -> handle })
        val scope = testScope()

        assertTrue(recorder.startRecording(scope))
        scope.cancel()
        withTimeout(2_000) { while (recorder.isRecording()) delay(10) }

        assertEquals(1, handle.releaseCalls.get())
    }

    @Test
    fun start_requiresMicrophonePermission() {
        assertThrows(SecurityException::class.java) {
            AudioRecorder(AudioRecordFactory { _, _ -> FakeHandle() })
                .startRecording(testScope(), hasRecordPermission = false)
        }
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class FakeHandle(
        override val isInitialized: Boolean = true,
        private val reads: MutableList<Int> = mutableListOf(0),
        private val startFailure: Exception? = null,
        private val stopFailure: Exception? = null,
        private val readEntered: CountDownLatch? = null,
        private val allowRead: CountDownLatch? = null
    ) : AudioRecordHandle {
        val stopCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()
        override fun startRecording() {
            startFailure?.let { throw it }
        }

        override fun read(buffer: ShortArray, offsetInShorts: Int, sizeInShorts: Int): Int {
            readEntered?.countDown()
            allowRead?.await(2, TimeUnit.SECONDS)
            val result = synchronized(reads) { if (reads.isNotEmpty()) reads.removeAt(0) else 0 }
            if (result > 0) buffer[offsetInShorts] = 16384
            return result
        }

        override fun stop() {
            stopCalls.incrementAndGet()
            stopFailure?.let { throw it }
        }

        override fun release() { releaseCalls.incrementAndGet() }
    }
}
