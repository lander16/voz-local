package dev.sebastian.vozlocal.fastconformervalidation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FastConformerAdapterTest {
    @Test
    fun preservesRecognizedPunctuationAndMakesCloseIdempotent() {
        val directory = validBundleDirectory()
        var closed = 0
        val adapter = FastConformerAdapter(
            directory,
            backendFactory = fakeFactory({ "Hola, mundo. ¿Vienes?" }, { closed++ }),
            bundleVerifier = { true },
        )
        try {
            assertEquals("Hola, mundo. ¿Vienes?", adapter.transcribe(floatArrayOf(0.1f, -0.1f)))
            adapter.close()
            adapter.close()
            assertEquals(1, closed)
            assertThrows(IllegalStateException::class.java) { adapter.transcribe(floatArrayOf(0f)) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun closeWaitsForAnAdmittedDecode() {
        val directory = validBundleDirectory()
        val enteredDecode = CountDownLatch(1)
        val finishDecode = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1)
        val adapter = FastConformerAdapter(
            directory,
            backendFactory = fakeFactory({
                enteredDecode.countDown()
                check(finishDecode.await(5, TimeUnit.SECONDS))
                "finished"
            }, { closed.countDown() }),
            bundleVerifier = { true },
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val decode = executor.submit<String> { adapter.transcribe(floatArrayOf(0f)) }
            assertEquals(true, enteredDecode.await(5, TimeUnit.SECONDS))
            val close = executor.submit {
                closeAttempted.countDown()
                adapter.close()
            }
            assertEquals(true, closeAttempted.await(5, TimeUnit.SECONDS))
            assertEquals("close must wait until the serialized native call drains", false, close.isDone)
            finishDecode.countDown()
            assertEquals("finished", decode.get(5, TimeUnit.SECONDS))
            close.get(5, TimeUnit.SECONDS)
            assertEquals(true, closed.await(1, TimeUnit.SECONDS))
        } finally {
            finishDecode.countDown()
            executor.shutdownNow()
            adapter.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun backendFailureStillAllowsExactlyOneClose() {
        val directory = validBundleDirectory()
        var closeCalls = 0
        val adapter = FastConformerAdapter(
            directory,
            backendFactory = fakeFactory({ error("synthetic backend failure") }, { closeCalls++ }),
            bundleVerifier = { true },
        )
        try {
            assertThrows(IllegalStateException::class.java) { adapter.transcribe(floatArrayOf(0f)) }
            adapter.close()
            adapter.close()
            assertEquals(1, closeCalls)
        } finally {
            adapter.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun invalidPcmNeverReachesBackend() {
        val directory = validBundleDirectory()
        var called = false
        val adapter = FastConformerAdapter(
            directory,
            backendFactory = fakeFactory({ called = true; "ok" }, {}),
            bundleVerifier = { true },
        )
        try {
            assertThrows(IllegalArgumentException::class.java) { adapter.transcribe(floatArrayOf(Float.NaN)) }
            assertEquals(false, called)
        } finally {
            adapter.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun invalidBundleAndThreadCountFailBeforeNativeFactory() {
        val directory = validBundleDirectory()
        var created = false
        val factory = object : FastConformerBackendFactory {
            override fun create(modelDirectory: File, numThreads: Int): FastConformerBackend {
                created = true
                error("must not instantiate native backend")
            }
        }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                FastConformerAdapter(directory, numThreads = 0, backendFactory = factory, bundleVerifier = { true })
            }
            assertThrows(IllegalArgumentException::class.java) {
                FastConformerAdapter(directory, backendFactory = factory, bundleVerifier = { false })
            }
            assertEquals(false, created)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun validBundleDirectory(): File {
        val dir = Files.createTempDirectory("fc-adapter").toFile()
        val model = "model bytes".toByteArray()
        val tokens = "token bytes".toByteArray()
        File(dir, "model.int8.onnx").writeBytes(model)
        File(dir, "tokens.txt").writeBytes(tokens)
        val artifacts = listOf(modelArtifact("model.int8.onnx", model), modelArtifact("tokens.txt", tokens))
        check(FastConformerModelBundle.verify(dir, artifacts))
        return dir
    }

    private fun modelArtifact(name: String, bytes: ByteArray) = ModelArtifact(
        name, bytes.size.toLong(), java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }, "https://example.invalid/$name",
    )

    private fun fakeFactory(text: () -> String, onClose: () -> Unit) = object : FastConformerBackendFactory {
        override fun create(modelDirectory: File, numThreads: Int) = object : FastConformerBackend {
            override fun transcribe(samples: FloatArray) = text()
            override fun close() = onClose()
        }
    }
}
