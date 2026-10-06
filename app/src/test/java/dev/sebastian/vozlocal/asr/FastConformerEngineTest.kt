package dev.sebastian.vozlocal.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
class FastConformerEngineTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val samples = floatArrayOf(0.1f, -0.1f, 0.2f)

    @Test fun nativeInstanceIsReusedAndReleasedOnce() = runBlocking {
        val created = AtomicInteger()
        val closed = AtomicInteger()
        val engine = engine(created, closed)
        val dir = File(context.cacheDir, "fc-engine-${System.nanoTime()}").apply { mkdirs() }
        try {
            assertEquals("hola.", engine.transcribe(dir, samples))
            assertEquals("hola.", engine.transcribe(dir, samples))
            assertEquals(1, created.get())
            engine.release()
            engine.release()
            assertEquals(1, closed.get())
        } finally { engine.release(); dir.deleteRecursively() }
    }

    @Test fun releaseIfIdleDoesNotWaitForActiveDecodeAndReleaseDrainsAfterCancellation() = runBlocking {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closed = AtomicInteger()
        val factory = object : FastConformerAdapterFactory {
            override fun create(directory: File): FastConformerAdapter = object : FastConformerAdapter {
                override fun transcribe(samples: FloatArray): String {
                    entered.countDown()
                    check(finish.await(3, TimeUnit.SECONDS))
                    return "hola."
                }
                override fun close() { closed.incrementAndGet() }
            }
        }
        val engine = FastConformerEngine(context, factory)
        val dir = File(context.cacheDir, "fc-drain-${System.nanoTime()}").apply { mkdirs() }
        val releaseAttempted = CountDownLatch(1)
        try {
            val inference = async(Dispatchers.IO) { engine.transcribe(dir, samples) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(withTimeout(500) { engine.releaseIfIdle() })
            inference.cancel()
            val release = async(Dispatchers.IO) { releaseAttempted.countDown(); engine.release() }
            assertTrue(releaseAttempted.await(1, TimeUnit.SECONDS))
            Thread.sleep(50)
            assertEquals(0, closed.get())
            finish.countDown()
            inference.cancelAndJoin()
            release.await()
            assertEquals(1, closed.get())
        } finally {
            finish.countDown()
            engine.release()
            dir.deleteRecursively()
        }
    }

    @Test fun inferenceFailureClosesResidentAndRetryCreatesFreshAdapter() = runBlocking {
        val created = AtomicInteger()
        val closed = AtomicInteger()
        val factory = object : FastConformerAdapterFactory {
            override fun create(directory: File): FastConformerAdapter {
                val index = created.incrementAndGet()
                return object : FastConformerAdapter {
                    override fun transcribe(samples: FloatArray): String {
                        if (index == 1) error("synthetic backend failure")
                        return "retry ok"
                    }
                    override fun close() { closed.incrementAndGet() }
                }
            }
        }
        val engine = FastConformerEngine(context, factory)
        val dir = File(context.cacheDir, "fc-retry-${System.nanoTime()}").apply { mkdirs() }
        try {
            try {
                engine.transcribe(dir, samples)
                throw AssertionError("expected backend failure")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("synthetic backend failure"))
            }
            assertEquals("retry ok", engine.transcribe(dir, samples))
            assertEquals(2, created.get())
            assertEquals(1, closed.get())
            engine.release()
            assertEquals(2, closed.get())
        } finally { engine.release(); dir.deleteRecursively() }
    }

    private fun engine(created: AtomicInteger, closed: AtomicInteger) = FastConformerEngine(
        context,
        object : FastConformerAdapterFactory {
            override fun create(directory: File): FastConformerAdapter {
                created.incrementAndGet()
                return object : FastConformerAdapter {
                    override fun transcribe(samples: FloatArray) = "hola."
                    override fun close() { closed.incrementAndGet() }
                }
            }
        },
    )
}
