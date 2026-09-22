package dev.sebastian.vozlocal.moonshine

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
class MoonshineModelDownloaderTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun validFixtureDownloadsAndIsVerified() = runBlocking {
        val bytes = mapOf("one.bin" to "one".toByteArray(), "two.bin" to "two".toByteArray())
        val spec = spec(bytes)
        val downloader = downloader(spec, bytes)
        clean(spec.id)

        try {
            assertTrue(downloader.download(spec.id, onProgress = {}))
            val directory = requireNotNull(downloader.verifiedDirectory(spec.id))
            bytes.forEach { (name, content) -> assertArrayEquals(content, File(directory, name).readBytes()) }
        } finally {
            clean(spec.id)
        }
    }

    @Test fun corruptHashAndOversizeAreRejectedWithoutReplacingVerifiedBundle() = runBlocking {
        val old = mapOf("one.bin" to "old".toByteArray())
        val targetSpec = spec(old, id = "moonshine_fixture_corrupt")
        val target = seedVerified(targetSpec, old)
        val badBytes = mapOf("one.bin" to "new".toByteArray())
        val badHashSpec = targetSpec.copy(assets = listOf(asset("one.bin", badBytes["one.bin"]!!, "0".repeat(64))))
        try {
            assertFalse(downloader(badHashSpec, badBytes).download(badHashSpec.id, onProgress = {}))
            assertArrayEquals(old.getValue("one.bin"), File(target, "one.bin").readBytes())

            val oversize = "too-large".toByteArray()
            val oversizeSpec = targetSpec.copy(assets = listOf(
                MoonshineAsset("one.bin", 1, sha256(oversize), "https://fixture.invalid/one.bin")
            ))
            assertFalse(downloader(oversizeSpec, mapOf("one.bin" to oversize)).download(oversizeSpec.id, onProgress = {}))
            assertArrayEquals(old.getValue("one.bin"), File(target, "one.bin").readBytes())
            assertTrue(downloader(targetSpec, old).verifiedDirectory(targetSpec.id) != null)
        } finally {
            clean(targetSpec.id)
        }
    }

    @Test fun cancellationBeforePromotionKeepsOldBundleAndCleansStage() = runBlocking {
        val old = mapOf("one.bin" to "old".toByteArray())
        val spec = spec(old, id = "moonshine_fixture_cancel")
        val target = seedVerified(spec, old)
        val reachedPromotion = CompletableDeferred<Unit>()
        val newBytes = mapOf("one.bin" to "new".toByteArray())
        val replacementSpec = spec(newBytes, id = spec.id)
        val job = async(Dispatchers.IO) {
            downloader(replacementSpec, newBytes).download(replacementSpec.id, onProgress = {}, beforePromote = {
                reachedPromotion.complete(Unit)
                awaitCancellation()
            })
        }
        try {
            withTimeout(2_000) { reachedPromotion.await() }
            job.cancelAndJoin()
            assertArrayEquals(old.getValue("one.bin"), File(target, "one.bin").readBytes())
            val parent = requireNotNull(target.parentFile)
            assertTrue(parent.listFiles().orEmpty().none { it.name.contains(".part") })
        } finally {
            job.cancel()
            clean(spec.id)
        }
    }

    @Test fun deleteRemovesInstalledBundleAndBackupsWithoutResurrection() = runBlocking {
        val bytes = mapOf("one.bin" to "one".toByteArray())
        val spec = spec(bytes, id = "moonshine_tiny_es")
        val target = seedVerified(spec, bytes)
        val backup = File(target.parentFile, ".${spec.id}.stale.backup").apply {
            mkdirs()
            File(this, "one.bin").writeBytes(bytes["one.bin"]!!)
        }
        val downloader = downloader(spec, bytes)
        try {
            assertTrue(downloader.delete(spec.id))
            assertFalse(target.exists())
            assertFalse(backup.exists())
            assertTrue(downloader.verifiedDirectory(spec.id) == null)
        } finally {
            clean(spec.id)
        }
    }

    @Test fun failureMovingOldBundleToBackupPreservesOldBundle() = runBlocking {
        val old = mapOf("one.bin" to "old".toByteArray())
        val replacement = mapOf("one.bin" to "new".toByteArray())
        val spec = spec(old, id = "moonshine_fixture_move_old")
        val replacementSpec = spec(replacement, id = spec.id)
        val target = seedVerified(spec, old)
        val downloader = downloader(replacementSpec, replacement) { from, _ ->
            if (from == target) error("injected old move failure")
            error("unexpected move")
        }
        try {
            assertFalse(downloader.download(replacementSpec.id, onProgress = {}))
            assertArrayEquals(old.getValue("one.bin"), File(target, "one.bin").readBytes())
            assertTrue(target.parentFile?.listFiles().orEmpty().none { it.name.endsWith(".backup") })
        } finally { clean(spec.id) }
    }

    @Test fun failureMovingStageToTargetRestoresOldBundle() = runBlocking {
        val old = mapOf("one.bin" to "old".toByteArray())
        val replacement = mapOf("one.bin" to "new".toByteArray())
        val spec = spec(old, id = "moonshine_fixture_move_stage")
        val replacementSpec = spec(replacement, id = spec.id)
        val target = seedVerified(spec, old)
        val downloader = downloader(replacementSpec, replacement) { from, to ->
            if (from.name.endsWith(".part")) error("injected stage move failure")
            Files.move(from.toPath(), to.toPath())
        }
        try {
            assertFalse(downloader.download(replacementSpec.id, onProgress = {}))
            assertArrayEquals(old.getValue("one.bin"), File(target, "one.bin").readBytes())
            assertTrue(target.parentFile?.listFiles().orEmpty().none { it.name.endsWith(".backup") })
        } finally { clean(spec.id) }
    }

    @Test fun failedRestoreLeavesBackupAndNextVerificationRecoversIt() = runBlocking {
        val old = mapOf("one.bin" to "old".toByteArray())
        val replacement = mapOf("one.bin" to "new".toByteArray())
        val spec = spec(old, id = "moonshine_fixture_restore")
        val replacementSpec = spec(replacement, id = spec.id)
        val target = seedVerified(spec, old)
        var restoreFailureInjected = false
        val downloader = downloader(replacementSpec, replacement) { from, to ->
            if (from.name.endsWith(".part")) error("injected stage move failure")
            if (from.name.endsWith(".backup") && !restoreFailureInjected) {
                restoreFailureInjected = true
                error("injected restore failure")
            }
            Files.move(from.toPath(), to.toPath())
        }
        try {
            assertFalse(downloader.download(replacementSpec.id, onProgress = {}))
            assertFalse(target.exists())
            assertTrue(target.parentFile?.listFiles().orEmpty().any { it.name.endsWith(".backup") })
            // The failed replacement was pinned to the new bytes; recovery must
            // validate the old bundle against its original pinned specification.
            val recovery = downloader(spec, old) { from, to -> Files.move(from.toPath(), to.toPath()) }
            assertTrue(recovery.verifiedDirectory(spec.id) != null)
            assertArrayEquals(old.getValue("one.bin"), File(target, "one.bin").readBytes())
        } finally { clean(spec.id) }
    }

    private fun downloader(
        spec: MoonshineModelSpec,
        responses: Map<String, ByteArray>,
        moveOverride: ((File, File) -> Unit)? = null,
    ): MoonshineModelDownloader {
        val client = OkHttpClient.Builder().addInterceptor(FixtureInterceptor(responses)).build()
        return if (moveOverride == null) {
            MoonshineModelDownloader(context, client, mapOf(spec.id to spec))
        } else {
            MoonshineModelDownloader(context, client, mapOf(spec.id to spec), moveOverride)
        }
    }

    private fun spec(bytes: Map<String, ByteArray>, id: String = "moonshine_fixture") =
        MoonshineModelSpec(id, bytes.values.sumOf { it.size.toLong() }, bytes.map { (name, content) -> asset(name, content) })

    private fun asset(name: String, content: ByteArray, hash: String = sha256(content)) =
        MoonshineAsset(name, content.size.toLong(), hash, "https://fixture.invalid/$name")

    private fun seedVerified(spec: MoonshineModelSpec, bytes: Map<String, ByteArray>): File {
        val target = MoonshineModels.directory(context, spec.id)
        target.deleteRecursively()
        target.mkdirs()
        bytes.forEach { (name, content) -> File(target, name).writeBytes(content) }
        File(target, ".verified").writeText(spec.assets.joinToString("\n") { "${it.name}:${it.sizeBytes}:${it.sha256}" })
        return target
    }

    private fun clean(id: String) {
        val target = MoonshineModels.directory(context, id)
        target.deleteRecursively()
        target.parentFile?.listFiles().orEmpty().filter {
            it.name.startsWith(".$id.")
        }.forEach { it.deleteRecursively() }
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
