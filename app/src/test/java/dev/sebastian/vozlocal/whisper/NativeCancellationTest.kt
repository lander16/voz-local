package dev.sebastian.vozlocal.whisper

import com.whispercpp.whisper.abortNativeOnCancellation
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeCancellationTest {
    @Test fun abortIsDeliveredBeforeBlockedWorkerFinishes() = runBlocking {
        val started = CountDownLatch(1)
        val aborted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val job = launch(Dispatchers.Default) {
            val handler = currentCoroutineContext()[Job]!!.abortNativeOnCancellation { aborted.countDown() }
            try {
                withContext(Dispatchers.IO) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            } finally { handler.dispose() }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancel()
            assertTrue(aborted.await(1, TimeUnit.SECONDS))
            assertFalse(job.isCompleted)
        } finally {
            release.countDown()
            job.cancelAndJoin()
        }
    }
}
