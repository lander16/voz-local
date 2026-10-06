package dev.sebastian.vozlocal.fastconformer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FastConformerModelDownloaderTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun downloadVerifiesBothPinnedAssetsAndMarker() = runBlocking {
        val bytes = fixture("model" to "weights".toByteArray(), "tokens" to "tokens".toByteArray())
        val spec = spec(bytes)
        val downloader = downloader(spec, bytes)
        clean()
        try {
            assertTrue(downloader.download(onProgress = {}))
            assertTrue(FastConformerModels.verify(FastConformerModels.directory(context), spec))
            bytes.forEach { (name, content) -> assertArrayEquals(content, File(FastConformerModels.directory(context), name).readBytes()) }
        } finally { clean() }
    }

    @Test fun hashFailureKeepsPreviouslyVerifiedBundle() = runBlocking {
        val oldBytes = fixture("model" to "old-model".toByteArray(), "tokens" to "old-tokens".toByteArray())
        val oldSpec = spec(oldBytes)
        val target = seed(oldSpec, oldBytes)
        val replacement = fixture("model" to "new-model".toByteArray(), "tokens" to "new-tokens".toByteArray())
        val badSpec = oldSpec.copy(assets = oldSpec.assets.map { asset ->
            asset.copy(sha256 = if (asset.name == "model.int8.onnx") "0".repeat(64) else asset.sha256)
        })
        try {
            assertFalse(downloader(badSpec, replacement).download(onProgress = {}))
            assertArrayEquals(oldBytes.getValue("model.int8.onnx"), File(target, "model.int8.onnx").readBytes())
            assertTrue(FastConformerModels.verify(target, oldSpec))
        } finally { clean() }
    }

    @Test fun recoveryRestoresOnlyVerifiedBackupAfterInterruptedPromotion() {
        val bytes = fixture("model" to "valid-model".toByteArray(), "tokens" to "valid-tokens".toByteArray())
        val spec = spec(bytes)
        val target = seed(spec, bytes)
        val backup = File(target.parentFile, ".${target.name}.interrupted.backup")
        assertTrue(target.renameTo(backup))
        assertTrue(target.mkdirs())
        File(target, "model.int8.onnx").writeText("partial")
        try {
            val recovered = downloader(spec, bytes).verifiedDirectory()
            assertTrue(recovered == target)
            assertTrue(FastConformerModels.verify(target, spec))
            assertArrayEquals(bytes.getValue("model.int8.onnx"), File(target, "model.int8.onnx").readBytes())
        } finally { clean() }
    }

    @Test fun failedStagePromotionRestoresOldVerifiedBundle() = runBlocking {
        val old = fixture("model" to "old-model".toByteArray(), "tokens" to "old-tokens".toByteArray())
        val spec = spec(old)
        val target = seed(spec, old)
        val replacement = fixture("model" to "new-model".toByteArray(), "tokens" to "new-tokens".toByteArray())
        val downloader = downloader(spec(replacement), replacement) { from, to ->
            if (from.name.endsWith(".part")) error("injected stage promotion failure")
            Files.move(from.toPath(), to.toPath())
        }
        try {
            assertFalse(downloader.download(onProgress = {}))
            assertArrayEquals(old.getValue("model.int8.onnx"), File(target, "model.int8.onnx").readBytes())
            assertTrue(FastConformerModels.verify(target, spec))
            assertTrue(target.parentFile?.listFiles().orEmpty().none { it.name.endsWith(".backup") })
        } finally { clean() }
    }

    @Test fun oversizedAndTruncatedResponsesAreRejectedWithoutReplacingOldBundle() = runBlocking {
        val old = fixture("model" to "old-model".toByteArray(), "tokens" to "old-tokens".toByteArray())
        val spec = spec(old)
        val target = seed(spec, old)
        val oversizeBytes = fixture("model" to "Xoversize".toByteArray(), "tokens" to old.getValue("tokens.txt"))
        val oversizeSpec = spec(oversizeBytes).copy(assets = spec(oversizeBytes).assets.map { asset ->
            if (asset.name == "model.int8.onnx") asset.copy(sizeBytes = 1, sha256 = sha256("X".toByteArray())) else asset
        })
        val truncatedExpected = fixture("model" to "expected-model".toByteArray(), "tokens" to "new-tokens".toByteArray())
        val truncatedSpec = spec(truncatedExpected)
        val truncatedResponse = fixture("model" to "short".toByteArray(), "tokens" to truncatedExpected.getValue("tokens.txt"))
        try {
            assertFalse(downloader(oversizeSpec, oversizeBytes).download(onProgress = {}))
            assertFalse(downloader(truncatedSpec, truncatedResponse).download(onProgress = {}))
            assertArrayEquals(old.getValue("model.int8.onnx"), File(target, "model.int8.onnx").readBytes())
            assertTrue(FastConformerModels.verify(target, spec))
        } finally { clean() }
    }

    @Test fun missingMarkerAndSameLengthCorruptionFailClosed() {
        val bytes = fixture("model" to "valid-model".toByteArray(), "tokens" to "valid-tokens".toByteArray())
        val spec = spec(bytes)
        val target = seed(spec, bytes)
        val downloader = downloader(spec, bytes)
        try {
            val model = File(target, "model.int8.onnx")
            val replacement = ByteArray(model.length().toInt()) { 0x58 }
            model.writeBytes(replacement)
            assertFalse(FastConformerModels.verify(target, spec))
            model.writeBytes(bytes.getValue("model.int8.onnx"))
            File(target, ".verified").delete()
            assertFalse(FastConformerModels.verify(target, spec))
            assertTrue(downloader.verifiedDirectory() == null)
        } finally { clean() }
    }

    @Test fun deleteRemovesOnlyFastConformerBundleAndItsTemporaryChildren() = runBlocking {
        val bytes = fixture("model" to "valid-model".toByteArray(), "tokens" to "valid-tokens".toByteArray())
        val spec = spec(bytes)
        val target = seed(spec, bytes)
        val sibling = File(requireNotNull(target.parentFile), "keep-unrelated.bin").apply { writeText("keep") }
        val backup = File(target.parentFile, ".${target.name}.old.backup").apply { mkdirs() }
        try {
            assertTrue(downloader(spec, bytes).delete())
            assertFalse(target.exists())
            assertFalse(backup.exists())
            assertTrue(sibling.readText() == "keep")
        } finally { sibling.delete(); clean() }
    }

    @Test fun cancellationBeforePromotionLeavesOldBundleAndCleansStage() = runBlocking {
        val old = fixture("model" to "old-model".toByteArray(), "tokens" to "old-tokens".toByteArray())
        val spec = spec(old)
        val target = seed(spec, old)
        val replacement = fixture("model" to "new-model".toByteArray(), "tokens" to "new-tokens".toByteArray())
        val reachedPromotion = CompletableDeferred<Unit>()
        val job = async(Dispatchers.IO) {
            downloader(spec(replacement), replacement).download(onProgress = {}, beforePromote = {
                reachedPromotion.complete(Unit)
                awaitCancellation()
            })
        }
        try {
            withTimeout(2_000) { reachedPromotion.await() }
            job.cancelAndJoin()
            assertArrayEquals(old.getValue("model.int8.onnx"), File(target, "model.int8.onnx").readBytes())
            assertTrue(target.parentFile?.listFiles().orEmpty().none { it.name.endsWith(".part") })
        } finally { job.cancel(); clean() }
    }

    private fun downloader(
        spec: FastConformerModelSpec,
        responses: Map<String, ByteArray>,
        moveOverride: ((File, File) -> Unit)? = null,
    ): FastConformerModelDownloader {
        val client = OkHttpClient.Builder().addInterceptor(FixtureInterceptor(responses)).build()
        return if (moveOverride == null) FastConformerModelDownloader(context, client, spec)
        else FastConformerModelDownloader(context, client, spec, moveOverride)
    }

    private fun spec(bytes: Map<String, ByteArray>): FastConformerModelSpec = FastConformerModelSpec(
        FastConformerModels.ID,
        bytes.map { (name, content) -> FastConformerAsset(name, content.size.toLong(), sha256(content), "https://fixture.invalid/$name") },
    )

    private fun fixture(model: Pair<String, ByteArray>, tokens: Pair<String, ByteArray>) = mapOf(
        "model.int8.onnx" to model.second,
        "tokens.txt" to tokens.second,
    )

    private fun seed(spec: FastConformerModelSpec, bytes: Map<String, ByteArray>): File {
        val target = FastConformerModels.directory(context)
        target.deleteRecursively()
        target.mkdirs()
        bytes.forEach { (name, content) -> File(target, name).writeBytes(content) }
        File(target, ".verified").writeText(FastConformerModels.marker(spec))
        return target
    }

    private fun clean() {
        val target = FastConformerModels.directory(context)
        target.deleteRecursively()
        target.parentFile?.listFiles().orEmpty().filter { it.name.startsWith(".${target.name}.") }
            .forEach { it.deleteRecursively() }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private class FixtureInterceptor(private val responses: Map<String, ByteArray>) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val name = chain.request().url.pathSegments.last()
            val bytes = responses[name] ?: error("No fixture for $name")
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(bytes.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }
    }
}
