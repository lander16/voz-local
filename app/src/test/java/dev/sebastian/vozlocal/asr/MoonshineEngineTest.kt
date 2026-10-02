package dev.sebastian.vozlocal.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MoonshineEngineTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun architectureUsesStreamingTinyAndSmallVariants() {
        assertEquals(ai.moonshine.voice.JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING,
            MoonshineEngine.architectureFor(MoonshineEngine.TINY_ES))
        assertEquals(ai.moonshine.voice.JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING,
            MoonshineEngine.architectureFor(MoonshineEngine.SMALL_ES))
    }

    @Test fun cancellationLeavesBusyUntilNativeCloseThenReleaseIsReusable() = runBlocking {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val calls = AtomicInteger(0)
        val factory = FakeFactory {
            object : FakeAdapter() {
                override fun transcribe(samples: FloatArray, sampleRate: Int): String {
                    if (calls.incrementAndGet() > 1) return "ok"
                    started.countDown()
                    finish.await(2, TimeUnit.SECONDS)
                    return "cancelled"
                }
                override fun close() { closed.set(true) }
            }
        }
        val engine = engine(factory)
        val request = async(Dispatchers.Default) { engine.transcribe(modelDir(), MoonshineEngine.TINY_ES, pcm()) }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        request.cancelAndJoin()
        assertTrue(engine.isBusy())
        try {
            engine.transcribe(modelDir(), MoonshineEngine.TINY_ES, pcm())
            error("A retry must not enter native code before the cancelled request drains")
        } catch (_: MoonshineBusyException) { }
        val release = async(Dispatchers.Default) { engine.release() }
        finish.countDown()
        withTimeout(2_000) { release.await() }
        assertTrue(closed.get())
        assertFalse(engine.isBusy())

        val next = async { engine.transcribe(modelDir(), MoonshineEngine.TINY_ES, pcm()) }
        assertEquals("ok", next.await())
    }

    @Test fun residentAdapterIsLoadedOnceForRepeatedAndLongRequestsThenReleased() = runBlocking {
        val creates = AtomicInteger(0)
        val loads = AtomicInteger(0)
        val closes = AtomicInteger(0)
        val factory = FakeFactory {
            creates.incrementAndGet()
            object : FakeAdapter() {
                override fun load(directory: File, architecture: Int) { loads.incrementAndGet() }
                override fun transcribe(samples: FloatArray, sampleRate: Int) = "${samples.size}"
                override fun close() { closes.incrementAndGet() }
            }
        }
        val engine = engine(factory)
        try {
            repeat(12) { assertEquals("160", engine.transcribe(modelDir(), MoonshineEngine.SMALL_ES, FloatArray(160))) }
            // The adapter accepts long clips without introducing an artificial 30 s cap.
            assertEquals("496000", engine.transcribe(modelDir(), MoonshineEngine.SMALL_ES, FloatArray(31 * 16_000)))
            assertEquals(1, creates.get())
            assertEquals(1, loads.get())
            assertEquals(0, closes.get())
        } finally {
            engine.release()
        }
        assertEquals(1, closes.get())
    }

    @Test fun modelOrVerifiedDirectoryIdentityChangeClosesOldResidentBeforeLoadingNext() = runBlocking {
        val events = mutableListOf<String>()
        val factory = FakeFactory {
            val id = events.count { it.startsWith("create") } + 1
            events += "create$id"
            object : FakeAdapter() {
                override fun load(directory: File, architecture: Int) { events += "load$id:$architecture:${directory.name}" }
                override fun close() { events += "close$id" }
            }
        }
        val engine = engine(factory)
        val secondDir = File(context.cacheDir, "moonshine-second").apply { mkdirs() }
        try {
            engine.transcribe(modelDir(), MoonshineEngine.TINY_ES, pcm())
            engine.transcribe(modelDir(), MoonshineEngine.TINY_ES, pcm())
            engine.transcribe(secondDir, MoonshineEngine.SMALL_ES, pcm())
            assertEquals(listOf("create1", "load1:${MoonshineEngine.architectureFor(MoonshineEngine.TINY_ES)}:cache", "close1", "create2", "load2:${MoonshineEngine.architectureFor(MoonshineEngine.SMALL_ES)}:moonshine-second"), events)
        } finally {
            engine.release()
        }
        assertEquals("close2", events.last())
    }

    @Test fun failedModelLoadClosesPartialAdapterAndRetryCreatesFreshResident() = runBlocking {
        val creates = AtomicInteger(0)
        val closes = AtomicInteger(0)
        val factory = FakeFactory {
            val index = creates.incrementAndGet()
            object : FakeAdapter() {
                override fun load(directory: File, architecture: Int) {
                    if (index == 1) error("load failure")
                }
                override fun close() { closes.incrementAndGet() }
                override fun transcribe(samples: FloatArray, sampleRate: Int) = "retry-ok"
            }
        }
        val engine = engine(factory)
        try {
            try {
                engine.transcribe(modelDir(), MoonshineEngine.SMALL_ES, pcm())
                error("expected load failure")
            } catch (expected: IllegalStateException) {
                assertEquals("load failure", expected.message)
            }
            assertEquals(1, closes.get())
            assertEquals("retry-ok", engine.transcribe(modelDir(), MoonshineEngine.SMALL_ES, pcm()))
            assertEquals(2, creates.get())
        } finally {
            engine.release()
        }
        assertEquals(2, closes.get())
    }

    @Test fun closeFailureDoesNotPoisonEngineOrPreventFreshRetry() = runBlocking {
        val closes = AtomicInteger(0)
        val calls = AtomicInteger(0)
        val factory = FakeFactory {
            val call = calls.incrementAndGet()
            object : FakeAdapter() {
                override fun transcribe(samples: FloatArray, sampleRate: Int): String = "result-$call"
                override fun close() {
                    closes.incrementAndGet()
                    if (call == 1) throw IllegalStateException("close failed")
                }
            }
        }
        val engine = engine(factory)
        assertEquals("result-1", engine.transcribe(modelDir(), MoonshineEngine.TINY_ES, pcm()))
        assertEquals(0, closes.get())
        try {
            engine.release()
            error("expected close failure")
        } catch (expected: IllegalStateException) {
            assertEquals("close failed", expected.message)
        }
        assertFalse(engine.isBusy())
        assertEquals("result-2", engine.transcribe(modelDir(), MoonshineEngine.TINY_ES, pcm()))
        engine.release()
        assertEquals(2, closes.get())
    }

    @Test fun releaseIfIdleDoesNotCloseDuringNativeRequestAndClosesAfterDrain() = runBlocking {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val factory = FakeFactory {
            object : FakeAdapter() {
                override fun transcribe(samples: FloatArray, sampleRate: Int): String {
                    started.countDown()
                    finish.await(2, TimeUnit.SECONDS)
                    return "drained"
                }
                override fun close() { closed.set(true) }
            }
        }
        val engine = engine(factory)
        val request = async(Dispatchers.Default) { engine.transcribe(modelDir(), MoonshineEngine.TINY_ES, pcm()) }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertFalse(engine.releaseIfIdle())
        assertFalse(closed.get())
        finish.countDown()
        assertEquals("drained", request.await())
        assertTrue(engine.releaseIfIdle())
        assertTrue(closed.get())
    }

    @Test fun concurrentReleaseCallsShareDrainAndDoNotShutdownWorker() = runBlocking {
        val factory = FakeFactory { object : FakeAdapter() {} }
        val engine = engine(factory)
        val first = async { engine.release() }
        val second = async { engine.release() }
        first.await(); second.await()
        assertFalse(engine.isBusy())
        assertEquals("ok", engine.transcribe(modelDir(), MoonshineEngine.TINY_ES, pcm()))
    }

    @Test fun releaseBarrierCannotOvertakeAdmittedRequest() = runBlocking {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val factory = FakeFactory {
            object : FakeAdapter() {
                override fun transcribe(samples: FloatArray, sampleRate: Int): String {
                    started.countDown()
                    finish.await(2, TimeUnit.SECONDS)
                    return "ordered"
                }
            }
        }
        val engine = engine(factory)
        val request = async(Dispatchers.Default) {
            engine.transcribe(modelDir(), MoonshineEngine.TINY_ES, pcm())
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val releaseCompleted = AtomicBoolean(false)
        val release = async(Dispatchers.Default) {
            engine.release()
            releaseCompleted.set(true)
        }
        // The request is still executing on the native worker, so the barrier must
        // remain behind it and cannot complete early.
        Thread.sleep(20)
        assertFalse(releaseCompleted.get())
        finish.countDown()
        assertEquals("ordered", request.await())
        withTimeout(2_000) { release.await() }
    }

    private fun engine(factory: MoonshineAdapterFactory) =
        MoonshineEngine(context, factory, Dispatchers.Default)

    private fun modelDir() = context.cacheDir.apply { mkdirs() }
    private fun pcm() = floatArrayOf(0.1f)

    private open class FakeAdapter : MoonshineAdapter {
        override fun load(directory: File, architecture: Int) = Unit
        override fun transcribe(samples: FloatArray, sampleRate: Int) = "ok"
        override fun close() = Unit
    }

    private class FakeFactory(private val creator: () -> MoonshineAdapter) : MoonshineAdapterFactory {
        override fun create() = creator()
    }
}
