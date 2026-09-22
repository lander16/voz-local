package dev.sebastian.vozlocal.moonshine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext

class MoonshineModelDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES)
        .build()

    internal constructor(context: Context, client: OkHttpClient) : this(context) { this.clientOverride = client }
    internal constructor(context: Context, client: OkHttpClient, specs: Map<String, MoonshineModelSpec>) : this(context, client) { this.specOverride = specs }
    internal constructor(
        context: Context,
        client: OkHttpClient,
        specs: Map<String, MoonshineModelSpec>,
        moveOverride: (File, File) -> Unit,
    ) : this(context, client, specs) { this.moveOverride = moveOverride }
    private var clientOverride: OkHttpClient? = null
    private var specOverride: Map<String, MoonshineModelSpec>? = null
    private var moveOverride: ((File, File) -> Unit)? = null
    private val httpClient get() = clientOverride ?: client
    private fun spec(id: String) = specOverride?.get(id) ?: MoonshineModels.model(id)

    fun verifiedDirectory(id: String): File? {
        val spec = spec(id) ?: return null
        val dir = MoonshineModels.directory(context, id)
        recoverOrphanBackup(target = dir)
        if (!dir.isDirectory) return null
        if (File(dir, VERIFIED_MARKER).readTextOrNull() != marker(spec)) return null
        if (!spec.assets.all { asset ->
                val f = File(dir, asset.name)
                f.isFile && f.length() == asset.sizeBytes && sha256(f) == asset.sha256
            }) return null
        return dir
    }

    suspend fun download(id: String, onProgress: suspend (Float) -> Unit, beforePromote: suspend () -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            val spec = spec(id) ?: return@withContext false
            val target = MoonshineModels.directory(context, id)
            val parent = requireNotNull(target.parentFile)
            parent.mkdirs()
            val stage = File(parent, ".${id}.${UUID.randomUUID()}.part")
            try {
                stage.mkdirs()
                var completed = 0L
                for (asset in spec.assets) {
                    coroutineContext.ensureActive()
                    val out = File(stage, asset.name)
                    val call = httpClient.newCall(Request.Builder().url(asset.url).build())
                    val assetVerified = withCancellableCall(call) { response ->
                            response.use {
                            if (!it.isSuccessful || it.body == null) return@withCancellableCall false
                            val body = it.body!!
                            FileOutputStream(out).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var read: Int
                                var fileBytes = 0L
                                body.byteStream().use { input ->
                                    while (input.read(buffer).also { read = it } >= 0) {
                                        coroutineContext.ensureActive()
                                        if (read == 0) continue
                                        // Check before writing so a malicious or changed server cannot
                                        // make the staging file exceed the pinned per-asset bound.
                                        if (read.toLong() > asset.sizeBytes - fileBytes) return@withCancellableCall false
                                        output.write(buffer, 0, read)
                                        fileBytes += read
                                        completed += read
                                        onProgress((completed.toFloat() / spec.sizeBytes).coerceIn(0f, 1f))
                                    }
                                }
                                output.flush()
                                if (fileBytes != asset.sizeBytes || sha256(out) != asset.sha256) return@withCancellableCall false
                            }
                        }
                        true
                    }
                    if (!assetVerified) return@withContext false
                }
                File(stage, VERIFIED_MARKER).writeText(marker(spec))
                coroutineContext.ensureActive()
                beforePromote()
                coroutineContext.ensureActive()
                promote(stage, target)
                onProgress(1f)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // OkHttp reports call.cancel() as an IOException.  Preserve coroutine
                // cancellation semantics instead of converting that into a failed
                // (but apparently normal) download.
                coroutineContext.ensureActive()
                false
            } finally {
                if (stage.exists()) stage.deleteRecursively()
            }
        }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        if (!MoonshineModels.isMoonshine(id)) return@withContext false
        val target = MoonshineModels.directory(context, id)
        val parent = target.parentFile
        val targetDeleted = !target.exists() || target.deleteRecursively()
        val backupsDeleted = parent?.listFiles { f ->
            f.name.startsWith(".${target.name}.") && f.name.endsWith(".backup")
        }?.all { !it.exists() || it.deleteRecursively() } ?: true
        targetDeleted && backupsDeleted
    }

    /** Keeps call.cancel() wired until the response body has been fully consumed. */
    private suspend fun <T> withCancellableCall(call: okhttp3.Call, block: suspend (okhttp3.Response) -> T): T =
        coroutineScope {
            val cancellationWatcher = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                val response = call.execute()
                try { block(response) } finally { response.close() }
            } finally {
                cancellationWatcher.cancel()
            }
        }

    private fun promote(stage: File, target: File) {
        val backup = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.backup")
        var movedOld = false
        try {
            if (target.exists()) {
                // Never replace or remove the old bundle until this move succeeds.
                move(target, backup)
                movedOld = true
            }
            move(stage, target)
            // A failed cleanup must not turn a successful install into data loss.  The
            // backup is deliberately recoverable and startup recovery can clean it up.
            if (movedOld) backup.deleteRecursively()
        } catch (t: Exception) {
            if (movedOld && backup.exists()) {
                try {
                    // A failed stage move normally leaves no target.  If a filesystem
                    // reports a partial replacement, preserve both trees rather than
                    // deleting one to make the restore appear to succeed.
                    if (!target.exists()) move(backup, target)
                } catch (_: Exception) { /* retain backup for startup recovery */ }
            }
            throw t
        }
    }

    private fun recoverOrphanBackup(target: File) {
        if (target.exists()) return
        val parent = target.parentFile ?: return
        val candidates = parent.listFiles { f -> f.name.startsWith(".${target.name}.") && f.name.endsWith(".backup") }
            ?.sortedByDescending { it.lastModified() } ?: return
        for (candidate in candidates) {
            try {
                move(candidate, target)
                if (verifiedDirectoryWithoutRecovery(target) != null) return
                move(target, candidate)
            } catch (_: Exception) { /* preserve candidate for a later recovery attempt */ }
        }
    }

    private fun verifiedDirectoryWithoutRecovery(dir: File): File? {
        val model = spec(dir.name) ?: return null
        if (File(dir, VERIFIED_MARKER).readTextOrNull() != marker(model)) return null
        return if (model.assets.all { a -> File(dir, a.name).isFile && File(dir, a.name).length() == a.sizeBytes && sha256(File(dir, a.name)) == a.sha256 }) dir else null
    }

    private fun move(from: File, to: File) {
        moveOverride?.let {
            it(from, to)
            return
        }
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE) }
        catch (_: Exception) {
            // Never make the non-atomic fallback destructive.  In particular, a
            // surprising destination must not overwrite the only remaining bundle.
            Files.move(from.toPath(), to.toPath())
        }
    }

    private fun marker(spec: MoonshineModelSpec) = spec.assets.joinToString("\n") { "${it.name}:${it.sizeBytes}:${it.sha256}" }
    private fun File.readTextOrNull(): String? {
        if (!isFile) return null
        return try { readText() } catch (_: Exception) { null }
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buffer).also { n = it } >= 0) if (n > 0) digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object { const val VERIFIED_MARKER = ".verified" }
}
