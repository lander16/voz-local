package dev.sebastian.vozlocal.whisper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.sebastian.vozlocal.data.local.AppDatabase
import dev.sebastian.vozlocal.data.repository.DictationRepository
import dev.sebastian.vozlocal.data.repository.ModelDownloader
import dev.sebastian.vozlocal.data.repository.ModelUrls
import dev.sebastian.vozlocal.data.repository.VerifiedModelFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger

private class FakeWhisperContext(
    val modelPath: String,
    var isReleased: Boolean = false,
    var warmedUpWithThreads: Int? = null,
    var onTranscribe: (suspend (FloatArray, WhisperParams) -> String)? = null
) : WhisperContextAdapter {
    override suspend fun warmup(threadCount: Int): Boolean {
        warmedUpWithThreads = threadCount
        return true
    }

    override suspend fun transcribeData(data: FloatArray, params: WhisperParams): String {
        return onTranscribe?.invoke(data, params) ?: "transcribed"
    }

    override suspend fun release() {
        isReleased = true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelResidencyTest {

    private lateinit var context: Context
    private val createdFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        runBlocking(Dispatchers.IO) {
            AppDatabase.getDatabase(context).clearAllTables()
        }
    }

    @After
    fun tearDown() {
        createdFiles.forEach { it.delete() }
        createdFiles.clear()
        runBlocking(Dispatchers.IO) {
            AppDatabase.getDatabase(context).clearAllTables()
        }
    }

    private fun createDummyModelFile(modelId: String, sizeBytes: Long = 75_000_000L): File {
        val file = ModelUrls.getModelFile(context, modelId)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(sizeBytes)
        }
        createdFiles.add(file)
        return file
    }

    @Test
    fun loadingNonExistentOrInvalidModel_preservesExistingLoadedModelContext() = runTest {
        val tinyFile = createDummyModelFile("whisper_tiny")
        val loadedContexts = mutableListOf<FakeWhisperContext>()

        var shouldThrowOnCreate = false
        val engine = WhisperEngine(context) { path ->
            if (shouldThrowOnCreate) {
                throw IllegalStateException("Simulated corrupt model file")
            }
            val fake = FakeWhisperContext(path)
            loadedContexts.add(fake)
            fake
        }

        // 1. Initial successful load of whisper_tiny
        val tinyLoaded = engine.loadModel("whisper_tiny")
        assertTrue("Initial load of whisper_tiny should succeed", tinyLoaded)
        assertTrue(engine.isModelLoaded("whisper_tiny"))
        assertEquals("whisper_tiny", engine.getLoadedModelId())
        assertEquals(1, loadedContexts.size)
        assertFalse("Initial context should not be released", loadedContexts[0].isReleased)

        // 2. Attempt to load a model whose file does not exist
        val nonExistentLoaded = engine.loadModel("non_existent_model")
        assertFalse("Loading non-existent model must return false", nonExistentLoaded)
        assertTrue("whisper_tiny must remain loaded", engine.isModelLoaded("whisper_tiny"))
        assertEquals("whisper_tiny", engine.getLoadedModelId())
        assertFalse("Existing context must not be released on missing file", loadedContexts[0].isReleased)

        // 3. Attempt to load an empty/invalid file (0 bytes)
        val emptyFile = createDummyModelFile("whisper_base", sizeBytes = 0L)
        val emptyLoaded = engine.loadModel("whisper_base")
        assertFalse("Loading 0-byte model file must return false", emptyLoaded)
        assertTrue("whisper_tiny must still remain loaded", engine.isModelLoaded("whisper_tiny"))
        assertEquals("whisper_tiny", engine.getLoadedModelId())
        assertFalse("Existing context must not be released on invalid file", loadedContexts[0].isReleased)

        // 4. Attempt to load a model where context creation throws
        val baseFile = createDummyModelFile("whisper_base", sizeBytes = 145_000_000L)
        shouldThrowOnCreate = true
        val failedLoad = engine.loadModel("whisper_base")
        assertFalse("Loading throwing model must return false", failedLoad)
        assertTrue("whisper_tiny must STILL remain loaded after thrown exception", engine.isModelLoaded("whisper_tiny"))
        assertEquals("whisper_tiny", engine.getLoadedModelId())
        assertFalse("Existing context must not be released after exception", loadedContexts[0].isReleased)

        // 5. Successful replacement releases the previous context
        shouldThrowOnCreate = false
        val baseLoaded = engine.loadModel("whisper_base")
        assertTrue("Loading whisper_base should now succeed", baseLoaded)
        assertTrue(engine.isModelLoaded("whisper_base"))
        assertEquals("whisper_base", engine.getLoadedModelId())
        assertEquals(2, loadedContexts.size)
        assertTrue("Old whisper_tiny context must be released after replacement", loadedContexts[0].isReleased)
        assertFalse("New whisper_base context must be active", loadedContexts[1].isReleased)
    }

    @Test
    fun warmupPassesProperThreadCountHintForModel() = runTest {
        createDummyModelFile("whisper_tiny")
        var capturedContext: FakeWhisperContext? = null

        val engine = WhisperEngine(context) { path ->
            FakeWhisperContext(path).also { capturedContext = it }
        }

        assertTrue(engine.loadModel("whisper_tiny"))
        val ctx = capturedContext
        assertTrue(ctx != null)

        val tinyWarmupParams = WhisperParams(
            language = "auto",
            singleSegment = true,
            noTimestamps = true,
            noContext = true,
            modelIdHint = "whisper_tiny",
            audioCtx = 256
        )
        val expectedThreads = com.whispercpp.whisper.WhisperCpuConfig.threadCountFor(tinyWarmupParams)
        assertEquals(expectedThreads, ctx?.warmedUpWithThreads)
    }

    @Test
    fun isBusy_preventsEvictionDuringActiveInference() = runTest {
        createDummyModelFile("whisper_tiny")
        val inferenceGate = CompletableDeferred<Unit>()
        val inferenceStarted = CompletableDeferred<Unit>()

        val engine = WhisperEngine(context) { path ->
            FakeWhisperContext(path).apply {
                onTranscribe = { _, _ ->
                    inferenceStarted.complete(Unit)
                    inferenceGate.await()
                    "inference result"
                }
            }
        }

        assertTrue(engine.loadModel("whisper_tiny"))
        assertFalse("Engine should be idle initially", engine.isBusy())

        // Launch an active transcription in background
        val job = launch(Dispatchers.Default) {
            engine.transcribe(FloatArray(16000))
        }

        // Wait until inference starts and is inside the active transcription block
        inferenceStarted.await()
        assertTrue("Engine must be busy while transcription is running", engine.isBusy())

        // Attempt memory pressure release while busy: must be rejected!
        val releaseWhileBusy = engine.releaseIfIdle()
        assertFalse("releaseIfIdle must return false while busy", releaseWhileBusy)
        assertTrue("Model must NOT be evicted while busy", engine.isModelLoaded("whisper_tiny"))

        // Unblock inference and let it complete
        inferenceGate.complete(Unit)
        job.join()

        assertFalse("Engine should be idle after transcription finishes", engine.isBusy())
        val releaseWhenIdle = engine.releaseIfIdle()
        assertTrue("releaseIfIdle must succeed when idle", releaseWhenIdle)
        assertFalse("Model must be evicted after idle release", engine.isModelLoaded())
    }

    @Test
    fun deduplicationOfConcurrentPreloadRequests() = runTest {
        createDummyModelFile("whisper_tiny", sizeBytes = 75_000_000L)

        val loadCount = AtomicInteger(0)
        val engine = WhisperEngine(context) { path ->
            loadCount.incrementAndGet()
            delay(50) // Simulate loading time
            FakeWhisperContext(path)
        }

        val fakeDownloader = object : ModelDownloader(context) {
            override fun verifiedModelFile(modelId: String): VerifiedModelFile? {
                val f = ModelUrls.getModelFile(context, modelId)
                return if (f.exists() && f.length() > 0) VerifiedModelFile(modelId, f, f.length(), f.lastModified()) else null
            }
        }

        val repo = DictationRepository(
            context = context,
            whisperEngine = engine,
            modelDownloader = fakeDownloader
        )

        // Seed models catalog
        repo.initializeModels()

        // Select whisper_tiny and ensure it's marked downloaded and selected in DB
        val database = AppDatabase.getDatabase(context)
        val modelDao = database.modelDao()
        modelDao.setDownloadState("whisper_tiny", downloaded = true, downloading = false, progress = 1.0f)
        modelDao.selectModel("whisper_tiny")

        assertEquals(0, loadCount.get()) // Not yet loaded

        // 1. Concurrent calls to preloadSelectedDownloadedModel()
        val resultsSelected = (1..10).map {
            async(Dispatchers.IO) {
                repo.preloadSelectedDownloadedModel()
            }
        }.awaitAll()

        assertTrue("All concurrent preloadSelected requests should succeed", resultsSelected.all { it })
        assertEquals("loadModel should have been invoked exactly once for concurrent preloadSelected calls", 1, loadCount.get())

        // 2. Concurrent calls to preloadModel(modelId) for the already loaded model
        val resultsModelId = (1..10).map {
            async(Dispatchers.IO) {
                repo.preloadModel("whisper_tiny")
            }
        }.awaitAll()

        assertTrue("All concurrent preloadModel(modelId) calls should succeed", resultsModelId.all { it })
        assertEquals("loadModel should not have been called again since model is already loaded", 1, loadCount.get())

        // 3. Concurrent calls to parameterless preloadModel()
        val resultsNoArg = (1..10).map {
            async(Dispatchers.IO) {
                repo.preloadModel()
            }
        }.awaitAll()

        assertEquals("No extra loads should have run", 1, loadCount.get())
        assertTrue(repo.modelLoaded.value)
    }

    @Test
    fun downloadingUnselectedModel_doesNotPreloadOrEvictSelectedModel() = runTest {
        createDummyModelFile("whisper_tiny", sizeBytes = 75_000_000L)

        val loadedModelIds = mutableListOf<String>()
        val engine = WhisperEngine(context) { path ->
            val modelId = if (path.contains("tiny")) "whisper_tiny" else "whisper_base"
            loadedModelIds.add(modelId)
            FakeWhisperContext(path)
        }

        val fakeDownloader = object : ModelDownloader(context) {
            override fun verifiedModelFile(modelId: String): VerifiedModelFile? {
                val f = ModelUrls.getModelFile(context, modelId)
                return if (f.exists() && f.length() > 0) VerifiedModelFile(modelId, f, f.length(), f.lastModified()) else null
            }

            override suspend fun downloadModel(
                modelId: String,
                onProgress: suspend (Float) -> Unit,
                beforePromote: suspend () -> Unit
            ): Boolean {
                createDummyModelFile(modelId, sizeBytes = 145_000_000L)
                beforePromote()
                onProgress(1.0f)
                return true
            }
        }

        val repo = DictationRepository(
            context = context,
            whisperEngine = engine,
            modelDownloader = fakeDownloader
        )

        // Initialize catalog and select whisper_tiny
        repo.initializeModels()
        val database = AppDatabase.getDatabase(context)
        val modelDao = database.modelDao()
        modelDao.setDownloadState("whisper_tiny", downloaded = true, downloading = false, progress = 1.0f)
        modelDao.selectModel("whisper_tiny")

        // Preload selected model (whisper_tiny)
        assertTrue(repo.preloadSelectedDownloadedModel())
        assertTrue("whisper_tiny must be loaded", engine.isModelLoaded("whisper_tiny"))
        assertEquals(listOf("whisper_tiny"), loadedModelIds)

        // Now download the unselected model (whisper_base)
        val downloadJob = Job()
        val downloadScope = CoroutineScope(Dispatchers.IO + downloadJob)
        val downloadProgressDone = CompletableDeferred<Unit>()

        repo.startModelDownload(
            modelId = "whisper_base",
            scope = downloadScope,
            replaceExisting = false,
            onProgress = { progress ->
                if (progress >= 1.0f) {
                    downloadProgressDone.complete(Unit)
                }
            }
        )
        downloadProgressDone.await()
        downloadJob.children.forEach { it.join() }

        // Verify in database: whisper_base is downloaded, but whisper_tiny is still selected
        val modelsAfterDownload = modelDao.getModelsList()

        val baseModel = modelsAfterDownload.find { it.id == "whisper_base" }
        assertTrue("whisper_base should be marked downloaded", baseModel?.isDownloaded == true)
        assertFalse("whisper_base should remain unselected", baseModel?.isSelected == true)

        val tinyModel = modelsAfterDownload.find { it.id == "whisper_tiny" }
        assertTrue("whisper_tiny should remain selected", tinyModel?.isSelected == true)

        // CRITICAL CHECK:
        // downloading an unselected model must NOT preload it or evict the selected model!
        assertTrue("whisper_tiny must STILL be the loaded model", engine.isModelLoaded("whisper_tiny"))
        assertFalse("whisper_base must NOT have been loaded", engine.isModelLoaded("whisper_base"))
        assertEquals("loadedModelIds must still contain only whisper_tiny", listOf("whisper_tiny"), loadedModelIds)
    }
}
