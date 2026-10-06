package dev.sebastian.vozlocal.fastconformer

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Downloads only the two pinned Spanish assets and promotes a fully verified bundle atomically. */
class FastConformerModelDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES)
        .build()

    internal constructor(context: Context, client: OkHttpClient, spec: FastConformerModelSpec) : this(context) {
        clientOverride = client
        specOverride = spec
    }

    internal constructor(
        context: Context,
        client: OkHttpClient,
        spec: FastConformerModelSpec,
        moveOverride: (File, File) -> Unit,
    ) : this(context, client, spec) { moveOverrideField = moveOverride }

    private var clientOverride: OkHttpClient? = null
    private var specOverride: FastConformerModelSpec? = null
    private var moveOverrideField: ((File, File) -> Unit)? = null
    private val httpClient get() = clientOverride ?: client
    private val bundle get() = specOverride ?: FastConformerModels.spec

    fun verifiedDirectory(id: String = FastConformerModels.ID): File? {
        if (!FastConformerModels.isFastConformer(id)) return null
        val directory = FastConformerModels.directory(context)
        return try {
            if (recoverBackup(directory)) directory else directory.takeIf { FastConformerModels.verify(it, bundle) }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun download(
        id: String = FastConformerModels.ID,
        onProgress: suspend (Float) -> Unit,
        beforePromote: suspend () -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        if (!FastConformerModels.isFastConformer(id)) return@withContext false
        val spec = bundle
        val target = FastConformerModels.directory(context)
        val parent = requireNotNull(target.parentFile)
        if (!parent.exists() && !parent.mkdirs()) return@withContext false
        parent.listFiles { file -> file.name.startsWith(".${target.name}.") && file.name.endsWith(".part") }
            ?.forEach { it.deleteRecursively() }
        val stage = File(parent, ".${target.name}.${UUID.randomUUID()}.part")
        try {
            if (!stage.mkdirs()) return@withContext false
            var completed = 0L
            for (asset in spec.assets) {
                coroutineContext.ensureActive()
                var completedForFile = 0L
                val request = Request.Builder().url(asset.url).build()
                val call = httpClient.newCall(request)
                val verified = withCancellableCall(call) { response ->
                    if (!response.isSuccessful) return@withCancellableCall false
                    val body = response.body ?: return@withCancellableCall false
                    val outputFile = File(stage, asset.name)
                    FileOutputStream(outputFile).use { output ->
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        body.byteStream().use { input ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                require(read.toLong() <= asset.sizeBytes - completedForFile)
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                completedForFile += read
                                completed += read
                                onProgress((completed.toFloat() / spec.sizeBytes).coerceIn(0f, 1f))
                            }
                        }
                        output.fd.sync()
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        outputFile.length() == asset.sizeBytes && actual == asset.sha256
                    }
                }
                if (!verified) return@withContext false
            }
            File(stage, ".verified").writeText(FastConformerModels.marker(spec))
            coroutineContext.ensureActive()
            require(FastConformerModels.verify(stage, spec)) { "FastConformer staged bundle failed verification" }
            beforePromote()
            coroutineContext.ensureActive()
            promote(stage, target)
            onProgress(1f)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            coroutineContext.ensureActive()
            false
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }

    suspend fun delete(id: String = FastConformerModels.ID): Boolean = withContext(Dispatchers.IO) {
        if (!FastConformerModels.isFastConformer(id)) return@withContext false
        val target = FastConformerModels.directory(context)
        val parent = target.parentFile
        val targetDeleted = !target.exists() || target.deleteRecursively()
        val temporaryDeleted = parent?.listFiles { file ->
            file.name.startsWith(".${target.name}.") && (file.name.endsWith(".backup") || file.name.endsWith(".part"))
        }?.all { !it.exists() || it.deleteRecursively() } ?: true
        targetDeleted && temporaryDeleted
    }

    private suspend fun <T> withCancellableCall(
        call: okhttp3.Call,
        block: suspend (okhttp3.Response) -> T,
    ): T = coroutineScope {
        val watcher = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response -> block(response) }
        } finally {
            watcher.cancel()
        }
    }

    private fun promote(stage: File, target: File) {
        val backup = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.backup")
        var movedOld = false
        try {
            if (target.exists()) {
                move(target, backup)
                movedOld = true
            }
            move(stage, target)
            if (movedOld) backup.deleteRecursively()
        } catch (error: Throwable) {
            if (movedOld && backup.exists() && !target.exists()) runCatching { move(backup, target) }
            throw error
        }
    }

    private fun move(from: File, to: File) {
        moveOverrideField?.let { it(from, to); return }
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath())
        }
    }

    /** If the process died mid-promotion, restore only a checksum-verified backup. */
    private fun recoverBackup(target: File): Boolean {
        val parent = target.parentFile ?: return false
        if (FastConformerModels.verify(target, bundle)) {
            parent.listFiles { file -> file.name.startsWith(".${target.name}.") && file.name.endsWith(".backup") }
                ?.forEach { it.deleteRecursively() }
            return true
        }
        val backup = parent.listFiles { file ->
            file.name.startsWith(".${target.name}.") && file.name.endsWith(".backup") && FastConformerModels.verify(file, bundle)
        }?.maxByOrNull(File::lastModified) ?: return false
        val invalidTarget = if (target.exists()) File(parent, ".${target.name}.${UUID.randomUUID()}.invalid") else null
        try {
            if (invalidTarget != null) move(target, invalidTarget)
            move(backup, target)
            invalidTarget?.deleteRecursively()
            return true
        } catch (failure: Exception) {
            if (invalidTarget?.exists() == true && !target.exists()) runCatching { move(invalidTarget, target) }
            throw failure
        }
    }

}
