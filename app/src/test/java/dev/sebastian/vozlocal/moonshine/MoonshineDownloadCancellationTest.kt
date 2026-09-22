package dev.sebastian.vozlocal.moonshine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MoonshineDownloadCancellationTest {
    @Test
    fun cancellationDuringStalledBodyClosesCallAndDoesNotPromoteStage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelId = "moonshine_tiny_es"
        val headersSent = CountDownLatch(1)
        val bodyStarted = CountDownLatch(1)
        val bodyReadClosed = CountDownLatch(1)
        val server = ServerSocket(0, 1)
        server.soTimeout = 5_000
        val body = byteArrayOf('a'.code.toByte(), 'b'.code.toByte())
        val spec = MoonshineModelSpec(
            id = modelId,
            sizeBytes = body.size.toLong(),
            assets = listOf(
                MoonshineAsset(
                    name = "payload.bin",
                    sizeBytes = body.size.toLong(),
                    sha256 = sha256(body),
                    url = "http://127.0.0.1:${server.localPort}/payload"
                )
            )
        )
        val downloader = MoonshineModelDownloader(
            context,
            OkHttpClient(),
            mapOf(modelId to spec)
        )
        val serverJob = launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    readRequest(socket)
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                    headersSent.countDown()
                    output.write(body[0].toInt())
                    output.flush()
                    // Keep the response incomplete. Cancellation must interrupt this read.
                    try {
                        socket.getInputStream().read()
                    } finally {
                        bodyReadClosed.countDown()
                    }
                }
            } finally {
                server.close()
            }
        }

        try {
            val downloadJob = launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    downloader.download(modelId, onProgress = { bodyStarted.countDown() })
                } catch (_: CancellationException) {
                    throw CancellationException("download cancelled")
                }
            }
            assertTrue("server should send response headers", headersSent.await(2, TimeUnit.SECONDS))
            assertTrue("client should begin consuming the response body", bodyStarted.await(2, TimeUnit.SECONDS))

            withTimeout(2_000) { downloadJob.cancelAndJoin() }
            assertTrue("cancellation should close the stalled body read", bodyReadClosed.await(1, TimeUnit.SECONDS))

            val target = MoonshineModels.directory(context, modelId)
            assertFalse("incomplete download must not be promoted", target.exists())
            val parent = requireNotNull(target.parentFile)
            assertTrue(
                "staging directory must be cleaned after cancellation",
                parent.listFiles().orEmpty().none { it.name.startsWith(".${target.name}.") && it.name.endsWith(".part") }
            )
        } finally {
            server.close()
            serverJob.cancelAndJoin()
            MoonshineModels.directory(context, modelId).deleteRecursively()
        }
    }

    private fun readRequest(socket: Socket) {
        val input = socket.getInputStream()
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        val recent = ArrayDeque<Byte>(4)
        while (true) {
            val next = input.read()
            if (next < 0) return
            if (recent.size == terminator.size) recent.removeFirst()
            recent.addLast(next.toByte())
            if (recent.toList().toByteArray().contentEquals(terminator)) return
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
