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
        val release = async(Dispatchers.Default) { engine.release() }
        finish.countDown()
        withTimeout(2_000) { release.await() }
        assertTrue(closed.get())
        assertFalse(engine.isBusy())

        val next = async { engine.transcribe(modelDir(), MoonshineEngine.TINY_ES, pcm()) }
        assertEquals("ok", next.await())
    }

    @Test fun closeCompletesBeforeSuccessfulResultAndCloseFailureDoesNotPoisonEngine() = runBlocking {
        val closedBeforeReturn = AtomicBoolean(false)
        val calls = AtomicInteger(0)
        val factory = FakeFactory {
            val call = calls.incrementAndGet()
            object : FakeAdapter() {
                override fun transcribe(samples: FloatArray, sampleRate: Int): String = "result-$call"
                override fun close() {
                    if (call == 1) throw IllegalStateException("close failed")
                    closedBeforeReturn.set(true)
                }
            }
        }
        val engine = engine(factory)
        try {
            engine.transcribe(modelDir(), MoonshineEngine.TINY_ES, pcm())
            error("expected close failure")
        } catch (expected: IllegalStateException) {
            assertEquals("close failed", expected.message)
        }
        assertFalse(engine.isBusy())
        assertEquals("result-2", engine.transcribe(modelDir(), MoonshineEngine.TINY_ES, pcm()))
        assertTrue(closedBeforeReturn.get())
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
