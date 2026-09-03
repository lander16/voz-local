package dev.sebastian.vozlocal.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dev.sebastian.vozlocal.audio.AudioDecoder
import dev.sebastian.vozlocal.audio.AudioSilenceTrimmer
import dev.sebastian.vozlocal.data.local.AppDatabase
import dev.sebastian.vozlocal.data.model.DictationModel
import dev.sebastian.vozlocal.data.model.DictationStat
import dev.sebastian.vozlocal.data.model.DictionaryWord
import dev.sebastian.vozlocal.data.model.TranscriptionHistory
import dev.sebastian.vozlocal.polish.TextPolishEngine
import dev.sebastian.vozlocal.polish.TextPolishEngine.CleanupMode
import dev.sebastian.vozlocal.whisper.WhisperEngine
import dev.sebastian.vozlocal.whisper.WhisperParams
import dev.sebastian.vozlocal.whisper.forLiveAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

import androidx.core.content.edit

private const val TAG = "DictationRepository"

enum class VadDownloadStatus {
    READY,
    DOWNLOADING,
    ERROR,
    NOT_DOWNLOADED
}

/** A launchable app that can be excluded from the floating accessibility overlay. */
data class SensitiveApp(
    val packageName: String,
    val label: String,
)

class DictationRepository(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val modelDao = database.modelDao()
    private val historyDao = database.historyDao()
    private val dictionaryDao = database.dictionaryDao()
    private val statsDao = database.statsDao()

    private val prefs = context.getSharedPreferences("vozlocal_prefs", Context.MODE_PRIVATE)

    val whisperEngine = WhisperEngine(context)
    val modelDownloader = ModelDownloader(context)
    val audioDecoder = AudioDecoder(context)
    val textPolishEngine = TextPolishEngine()
    val qwenEngine: TextPolishEngine get() = textPolishEngine

    // Regex cache for the dictionary replacement pass, invalidated whenever words change.
    private var cachedWordRegexes: List<Regex> = emptyList()
    private var cachedWordReplacements: List<String> = emptyList()
    private var lastDictHash: Int = 0
    @Volatile private var dictionarySnapshot: List<DictionaryWord> = emptyList()

    // Silero VAD model state: populated from disk or by an explicit user download.
    private val _vadModelReady = MutableStateFlow(false)
    val isVadModelReady: StateFlow<Boolean> = _vadModelReady.asStateFlow()

    private val _vadModelPath = MutableStateFlow<String?>(null)
    val vadModelPath: StateFlow<String?> = _vadModelPath.asStateFlow()

    private val _vadDownloadProgress = MutableStateFlow(0f)
    val vadDownloadProgress: StateFlow<Float> = _vadDownloadProgress.asStateFlow()

    private val _vadDownloadStatus = MutableStateFlow(VadDownloadStatus.NOT_DOWNLOADED)
    val vadDownloadStatus: StateFlow<VadDownloadStatus> = _vadDownloadStatus.asStateFlow()

    private val _vadStatusLabel = MutableStateFlow("Not downloaded")
    val vadStatusLabel: StateFlow<String> = _vadStatusLabel.asStateFlow()

    private val _vadStatusMessage = MutableStateFlow("Silero VAD model is not downloaded.")
    val vadStatusMessage: StateFlow<String> = _vadStatusMessage.asStateFlow()

    private val _vadModelSizeBytes = MutableStateFlow<Long?>(null)
    val vadModelSizeBytes: StateFlow<Long?> = _vadModelSizeBytes.asStateFlow()

    // True only while a Whisper model is actually loaded in the native engine.
    private val _modelLoaded = MutableStateFlow(false)
    val modelLoaded: StateFlow<Boolean> = _modelLoaded.asStateFlow()

    val allModels: Flow<List<DictationModel>> = modelDao.getAllModels().map { list ->
        list.sortedBy { model -> when (model.id) {
            "whisper_base" -> 1
            "whisper_tiny" -> 2
            "whisper_base_en" -> 3
            "whisper_small" -> 4
            "whisper_small_q5_1" -> 5
            "whisper_large_v3_turbo" -> 6
            "whisper_medium" -> 7
            else -> 10
        } }
    }
    val allHistory: Flow<List<TranscriptionHistory>> = historyDao.getAllHistory()
    val allWords: Flow<List<DictionaryWord>> = dictionaryDao.getAllWords()
    val allStats: Flow<List<DictationStat>> = statsDao.getAllStats()

    fun pagedHistory(limit: Int = 200): Flow<List<TranscriptionHistory>> =
        historyDao.getHistoryPaged(limit)

    fun getHistoryLimit(): Int {
        return prefs.getInt("history_limit", -1)
    }

    fun saveHistoryLimit(limit: Int) {
        prefs.edit { putInt("history_limit", limit) }
    }

    fun getSaveHistory(): Boolean {
        return prefs.getBoolean("save_history", true)
    }

    fun saveSaveHistory(value: Boolean) {
        prefs.edit { putBoolean("save_history", value) }
    }

    fun getShowOnlyOnInput(): Boolean {
        return prefs.getBoolean("show_only_on_input", true)
    }

    fun saveShowOnlyOnInput(value: Boolean) {
        prefs.edit { putBoolean("show_only_on_input", value) }
    }

    fun getDeniedPackages(): Set<String> =
        prefs.getStringSet("denied_accessibility_packages", emptySet())?.toSet() ?: emptySet()

    fun saveDeniedPackages(packages: Set<String>) {
        prefs.edit { putStringSet("denied_accessibility_packages", packages.toSet()) }
    }

    @Suppress("DEPRECATION")
    fun getLaunchableApps(): List<SensitiveApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0)
            .asSequence()
            .map { resolveInfo ->
                SensitiveApp(
                    packageName = resolveInfo.activityInfo.packageName,
                    label = resolveInfo.loadLabel(context.packageManager).toString()
                )
            }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }

    // Language preference — "es" by default, avoids expensive auto-detection (~200-500ms)
    fun getLanguage(): String {
        return prefs.getString("whisper_language", "es") ?: "es"
    }

    fun saveLanguage(language: String) {
        prefs.edit { putString("whisper_language", language) }
    }

    fun getUseAiPolisher(): Boolean {
        return prefs.getBoolean("use_ai_polisher", false)
    }

    fun saveUseAiPolisher(value: Boolean) {
        prefs.edit { putBoolean("use_ai_polisher", value) }
    }

    fun getCleanupMode(): CleanupMode {
        val raw = prefs.getString("cleanup_mode", CleanupMode.BALANCED.name) ?: CleanupMode.BALANCED.name
        return CleanupMode.values().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: CleanupMode.BALANCED
    }

    fun saveCleanupMode(value: CleanupMode) {
        prefs.edit { putString("cleanup_mode", value.name) }
    }

    fun getSmartPunctuation(): Boolean {
        return prefs.getBoolean("smart_punctuation", true)
    }

    fun saveSmartPunctuation(value: Boolean) {
        prefs.edit { putBoolean("smart_punctuation", value) }
    }

    fun getAutoCapitalization(): Boolean {
        return prefs.getBoolean("auto_capitalization", true)
    }

    fun saveAutoCapitalization(value: Boolean) {
        prefs.edit { putBoolean("auto_capitalization", value) }
    }

    fun getApplyDictionary(): Boolean {
        return prefs.getBoolean("apply_dictionary", true)
    }

    fun saveApplyDictionary(value: Boolean) {
        prefs.edit { putBoolean("apply_dictionary", value) }
    }

    fun getThemeMode(): String {
        return prefs.getString("theme_mode", "dark") ?: "dark"
    }

    fun saveThemeMode(value: String) {
        prefs.edit { putString("theme_mode", value) }
    }

    fun getNoSpeechThold(): Float {
        return prefs.getFloat("no_speech_thold", 0.6f)
    }

    fun saveNoSpeechThold(value: Float) {
        prefs.edit { putFloat("no_speech_thold", value) }
    }

    fun getLogprobThold(): Float {
        return prefs.getFloat("logprob_thold", -1.0f)
    }

    fun saveLogprobThold(value: Float) {
        prefs.edit { putFloat("logprob_thold", value) }
    }

    fun getEntropyThold(): Float {
        return prefs.getFloat("entropy_thold", 2.4f)
    }

    fun saveEntropyThold(value: Float) {
        prefs.edit { putFloat("entropy_thold", value) }
    }

    fun getInitialPrompt(): String? {
        val value = prefs.getString("initial_prompt", null) ?: return null
        return value.ifBlank { null }
    }

    fun saveInitialPrompt(value: String?) {
        prefs.edit { putString("initial_prompt", value?.ifBlank { null }) }
    }

    fun getUseVad(): Boolean = prefs.getBoolean("use_vad", true)

    fun saveUseVad(value: Boolean) {
        prefs.edit { putBoolean("use_vad", value) }
    }

    fun getSpokenPunctuationCommands(): Boolean = prefs.getBoolean("spoken_punctuation_commands", false)

    fun saveSpokenPunctuationCommands(value: Boolean) {
        prefs.edit { putBoolean("spoken_punctuation_commands", value) }
    }

    suspend fun shutdown() {
        whisperEngine.release()
        _modelLoaded.value = false
    }

    fun setModelLoaded(value: Boolean) {
        _modelLoaded.value = value
    }

    /**
     * Downloads the Silero VAD model if not already present and publishes its
     * absolute path + readiness. The VAD model is small (~2 MB), so this is
     * initiated explicitly from the UI and never automatically at app startup.
     */
    suspend fun ensureVadModel() {
        _vadDownloadStatus.value = VadDownloadStatus.DOWNLOADING
        _vadStatusLabel.value = "Downloading"
        _vadStatusMessage.value = "Downloading Silero VAD model..."
        _vadDownloadProgress.value = 0.01f
        val path = modelDownloader.downloadVadModel(
            onProgress = { progress -> _vadDownloadProgress.value = progress.coerceIn(0f, 1f) },
            onContentLength = { bytes -> _vadModelSizeBytes.value = bytes }
        )
        if (path != null) {
            _vadModelPath.value = path
            _vadModelReady.value = true
            _vadDownloadProgress.value = 1f
            _vadDownloadStatus.value = VadDownloadStatus.READY
            _vadStatusLabel.value = "Ready"
            _vadStatusMessage.value = "Silero VAD model is ready."
            _vadModelSizeBytes.value = modelDownloader.vadModelFile().length().takeIf { it > 0L } ?: _vadModelSizeBytes.value
        } else {
            _vadModelReady.value = false
            _vadModelPath.value = null
            _vadDownloadProgress.value = 0f
            _vadDownloadStatus.value = VadDownloadStatus.ERROR
            _vadStatusLabel.value = "Download failed"
            _vadStatusMessage.value = "Silero VAD model could not be downloaded."
        }
    }

    suspend fun deleteVadModel() = withContext(Dispatchers.IO) {
        modelDownloader.deleteVadModel()
        _vadModelReady.value = false
        _vadModelPath.value = null
        _vadDownloadProgress.value = 0f
        _vadDownloadStatus.value = VadDownloadStatus.NOT_DOWNLOADED
        _vadStatusLabel.value = "Not downloaded"
        _vadStatusMessage.value = "Silero VAD model is not downloaded."
        _vadModelSizeBytes.value = ModelUrls.minimumValidBytes("silero_vad")
    }

    /**
     * Waits until the model table has a selected model (initializeModels seeds it)
     * and preloads it into the engine so the first dictation has zero load latency.
     */
    suspend fun preloadModel() = withContext(Dispatchers.IO) {
        try {
            val models = allModels.first { it.isNotEmpty() }
            val selectedDownloaded = models.find { it.isSelected && it.isDownloaded }
                ?: models.firstOrNull { it.isDownloaded }
            val ok = selectedDownloaded?.let { whisperEngine.loadModel(it.id) } == true
            Log.i(TAG, "Preload of selected downloaded model '${selectedDownloaded?.id}' -> loaded=$ok")
            _modelLoaded.value = ok
        } catch (e: Exception) {
            Log.e(TAG, "Error preloading model", e)
            _modelLoaded.value = false
        }
    }

    suspend fun preloadModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        val ok = whisperEngine.loadModel(modelId)
        _modelLoaded.value = ok
        ok
    }

    fun updateModelLoadedState(loaded: Boolean) {
        _modelLoaded.value = loaded
    }

    suspend fun preloadSelectedDownloadedModel(): Boolean = withContext(Dispatchers.IO) {
        val selected = allModels.first { it.isNotEmpty() }.find { it.isSelected && it.isDownloaded }
            ?: allModels.first { it.isNotEmpty() }.firstOrNull { it.isDownloaded }
        selected?.let { preloadModel(it.id) } ?: false
    }

    suspend fun pruneHistory(limit: Int) = withContext(Dispatchers.IO) {
        if (limit > 0) {
            val currentHistory = historyDao.getAllHistory().first()
            if (currentHistory.size > limit) {
                val itemsToDelete = currentHistory.drop(limit)
                for (item in itemsToDelete) {
                    historyDao.deleteHistoryById(item.id)
                }
            }
        }
    }

    init {
        // If the VAD model is already on disk (e.g. from a previous run), publish it now.
        val vadFile = modelDownloader.vadModelFile()
        _vadModelSizeBytes.value = vadFile.length().takeIf { it > 0L } ?: ModelUrls.minimumValidBytes("silero_vad")
        if (ModelUrls.isValidDownloadedFile(vadFile, "silero_vad")) {
            _vadModelPath.value = vadFile.absolutePath
            _vadModelReady.value = true
            _vadDownloadProgress.value = 1f
            _vadDownloadStatus.value = VadDownloadStatus.READY
            _vadStatusLabel.value = "Ready"
            _vadStatusMessage.value = "Silero VAD model is ready."
        }

        // Model seeding/sync is owned by VozLocalApp.applicationScope so startup
        // does not duplicate database work from both the repository and app.
    }

    /**
     * Seeds the model table on first launch and reconciles the metadata + download
     * state on every launch. Suspends on Dispatchers.IO via the caller; safe to
     * invoke from a long-lived application scope.
     */
    suspend fun initializeModels() = withContext(Dispatchers.IO) {
        try {
            val defaultModels = listOf(
                DictationModel(
                    id = "whisper_base",
                    name = "Whisper Base (Recommended)",
                    sizeMb = 78f,  // q8_0 quantized
                    accuracySpanish = 83,
                    accuracyEnglish = 89,
                    speedMultiplier = 5.0f,
                    isDownloaded = ModelUrls.isModelDownloaded(context, "whisper_base"),
                    isSelected = true
                ),
                DictationModel(
                    id = "whisper_tiny",
                    name = "Whisper Tiny (Ultra Fast)",
                    sizeMb = 42f,  // q8_0 quantized
                    accuracySpanish = 72,
                    accuracyEnglish = 79,
                    speedMultiplier = 8.5f,
                    isDownloaded = ModelUrls.isModelDownloaded(context, "whisper_tiny"),
                    isSelected = false
                ),
                DictationModel(
                    id = "whisper_base_en",
                    name = "Whisper Base English (High Speed .en)",
                    sizeMb = 78f,  // q8_0 quantized
                    accuracySpanish = 0,
                    accuracyEnglish = 93,
                    speedMultiplier = 5.5f,
                    isDownloaded = ModelUrls.isModelDownloaded(context, "whisper_base_en"),
                    isSelected = false
                ),
                DictationModel(
                    id = "whisper_small",
                    name = "Whisper Small (High Precision)",
                    sizeMb = 252f,  // q8_0 quantized
                    accuracySpanish = 92,
                    accuracyEnglish = 95,
                    speedMultiplier = 2.5f,
                    isDownloaded = ModelUrls.isModelDownloaded(context, "whisper_small"),
                    isSelected = false
                ),
                DictationModel(
                    id = "whisper_small_q5_1",
                    name = "Whisper Small q5_1 (Mobile Sweet Spot)",
                    sizeMb = 175f,  // q5_1 quantized
                    accuracySpanish = 91,
                    accuracyEnglish = 94,
                    speedMultiplier = 3.2f,
                    isDownloaded = ModelUrls.isModelDownloaded(context, "whisper_small_q5_1"),
                    isSelected = false
                ),
                DictationModel(
                    id = "whisper_large_v3_turbo",
                    name = "Whisper Large v3 Turbo (SOTA Quality)",
                    sizeMb = 547f,  // q5_0 mobile optimized
                    accuracySpanish = 99,
                    accuracyEnglish = 99,
                    speedMultiplier = 3.5f,
                    isDownloaded = ModelUrls.isModelDownloaded(context, "whisper_large_v3_turbo"),
                    isSelected = false
                ),
                DictationModel(
                    id = "whisper_medium",
                    name = "Whisper Medium (Legacy Heavy)",
                    sizeMb = 823f,  // q8_0 quantized
                    accuracySpanish = 97,
                    accuracyEnglish = 99,
                    speedMultiplier = 1.0f,
                    isDownloaded = ModelUrls.isModelDownloaded(context, "whisper_medium"),
                    isSelected = false
                )
            )

            val current = allModels.first()
            if (current.isEmpty()) {
                modelDao.insertModels(defaultModels)
            } else {
                // Prune any deprecated placeholder models (e.g. whisper_es_optimized) from SQLite
                val validIds = defaultModels.map { it.id }
                modelDao.pruneStaleModels(validIds)

                // Insert any newly added default models (e.g. whisper_large_v3_turbo, qwen2.5_0.5b)
                val currentIds = current.map { it.id }.toSet()
                val missingModels = defaultModels.filter { it.id !in currentIds }
                if (missingModels.isNotEmpty()) {
                    modelDao.insertModels(missingModels)
                }

                // Update metadata and download status for existing models in database
                val updatedList = allModels.first()
                for (model in updatedList) {
                    val downloaded = ModelUrls.isModelDownloaded(context, model.id)
                    val defaultModel = defaultModels.find { it.id == model.id }
                    val targetSize = defaultModel?.sizeMb ?: model.sizeMb
                    val targetName = defaultModel?.name ?: model.name
                    val targetSpeed = defaultModel?.speedMultiplier ?: model.speedMultiplier

                    if (model.isDownloaded != downloaded || model.isDownloading || model.sizeMb != targetSize || model.name != targetName) {
                        modelDao.updateModel(model.copy(
                            name = targetName,
                            sizeMb = targetSize,
                            speedMultiplier = targetSpeed,
                            isDownloaded = downloaded,
                            isDownloading = false,
                            downloadProgress = if (downloaded) 1.0f else 0.0f
                        ))
                    }
                }

                // Ensure a downloaded model is selected if one is available and current selection is not downloaded
                val finalList = allModels.first()
                val activeSelected = finalList.find { it.isSelected }
                if (activeSelected == null || !activeSelected.isDownloaded) {
                    finalList.find { it.isDownloaded }?.let { modelDao.selectModel(it.id) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing default models", e)
        }
        refreshDictionarySnapshot()
    }

    // Models Operations
    suspend fun selectModel(modelId: String) = withContext(Dispatchers.IO) {
        modelDao.selectModel(modelId)
        allModels.first().find { it.id == modelId && it.isDownloaded }?.let { preloadModel(modelId) }
    }

    @Suppress("unused")
    suspend fun updateModel(model: DictationModel) = withContext(Dispatchers.IO) {
        modelDao.updateModel(model)
    }

    fun startModelDownload(
        modelId: String,
        scope: CoroutineScope,
        onProgress: (Float) -> Unit = {}
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val models = allModels.first()
                var model = models.find { it.id == modelId } ?: return@launch
                if (model.isDownloaded || model.isDownloading) return@launch

                model = model.copy(isDownloading = true, downloadProgress = 0.01f)
                modelDao.updateModel(model)
                onProgress(0.01f)

                val success = modelDownloader.downloadModel(modelId) { progress ->
                    // Keep frequent progress in memory only; Room is updated at
                    // start and on final success/failure to avoid write storms.
                    onProgress(progress)
                }

                if (success) {
                    modelDao.updateModel(model.copy(
                        isDownloading = false,
                        isDownloaded = true,
                        downloadProgress = 1.0f
                    ))
                    val currentSelected = allModels.first().find { it.isSelected }
                    if (currentSelected == null || !currentSelected.isDownloaded) {
                        modelDao.selectModel(modelId)
                    }
                    onProgress(1.0f)
                    preloadModel(modelId)
                } else {
                    modelDao.updateModel(model.copy(
                        isDownloading = false,
                        isDownloaded = false,
                        downloadProgress = 0.0f
                    ))
                    onProgress(0.0f)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading model $modelId", e)
                val models = allModels.first()
                val model = models.find { it.id == modelId }
                if (model != null) {
                    modelDao.updateModel(model.copy(isDownloading = false, downloadProgress = 0.0f))
                }
                onProgress(0.0f)
            }
        }
    }

    suspend fun deleteDownloadedModel(modelId: String) = withContext(Dispatchers.IO) {
        val models = allModels.first()
        val model = models.find { it.id == modelId } ?: return@withContext

        if (model.isSelected) {
            modelDao.selectModel("whisper_tiny")
        }

        val file = ModelUrls.getModelFile(context, modelId)
        if (file.exists()) {
            file.delete()
        }

        modelDao.updateModel(model.copy(
            isDownloaded = false,
            downloadProgress = 0.0f,
            isDownloading = false
        ))
    }

    // Inference & Transcription Operations
    suspend fun transcribeAudio(samples: FloatArray, modelId: String): String = withContext(Dispatchers.Default) {
        if (samples.isEmpty()) return@withContext ""

        // Trim leading and trailing silence to avoid processing dead audio frames
        val trimmed = AudioSilenceTrimmer.trim(samples)
        val activeSamples = if (trimmed.isNotEmpty()) trimmed else samples

        // Ensure Whisper engine is loaded with target model
        val loaded = whisperEngine.loadModel(modelId)
        _modelLoaded.value = loaded
        if (!loaded) {
            Log.e(TAG, "Could not load Whisper model $modelId for transcription")
            return@withContext ""
        }

        whisperEngine.transcribe(
            activeSamples,
            language = getLanguage(),
            params = currentWhisperParams()
                .forLiveAudio(activeSamples.size)
                .copy(
                    vadModelPath = vadPathFor(activeSamples.size, sharedFile = false),
                    modelIdHint = modelId
                )
        )
    }

    suspend fun transcribeSharedFile(
        uri: Uri,
        modelId: String,
        onProgress: (Float, String) -> Unit
    ): String = withContext(Dispatchers.Default) {
        onProgress(0.05f, "Decoding audio file...")
        val loadJob = async(Dispatchers.IO) {
            onProgress(0.28f, "Loading local Whisper model...")
            whisperEngine.loadModel(modelId)
        }
        val decodedSamples = audioDecoder.decodeToPcm16k(uri) { prog ->
            onProgress(0.05f + prog * 0.23f, "Decoding audio file...")
        }

        if (decodedSamples.isEmpty()) {
            loadJob.cancel()
            return@withContext "Error: Failed to decode audio file."
        }

        val trimmedSamples = AudioSilenceTrimmer.trim(decodedSamples)
        val samples = if (trimmedSamples.isNotEmpty()) trimmedSamples else decodedSamples
        val audioDurationSec = samples.size / 16000f

        onProgress(0.30f, "Preparing local Whisper model...")
        val loaded = loadJob.await()
        _modelLoaded.value = loaded
        if (!loaded) {
            return@withContext "Error: Local Whisper model $modelId is not downloaded yet. Please download it first."
        }

        // Fast decoding parameters for shared audio:
        // - temperatureInc = 0.0f eliminates multi-pass retry loops on conversational hesitations
        // - noTimestamps = true eliminates generating timestamp tokens for faster token generation
        val transcriptionParams = currentWhisperParams().copy(
            singleSegment = false,
            printTimestamps = false,
            noTimestamps = true,
            temperatureInc = 0.0f,
            noContext = false,
            vadModelPath = vadPathFor(samples.size, sharedFile = true),
            modelIdHint = modelId
        )

        // Estimated inference speed multiplier per model on modern mobile CPUs
        val realtimeFactor = when {
            modelId.contains("tiny", ignoreCase = true) -> 0.18f
            modelId.contains("base", ignoreCase = true) -> 0.32f
            modelId.contains("small", ignoreCase = true) -> 0.70f
            else -> 1.05f
        }
        val estimatedTotalSec = (audioDurationSec * realtimeFactor).coerceAtLeast(3f)
        val inferenceStartMs = System.currentTimeMillis()

        // Active ticker job updating smooth progress and elapsed time
        val tickerJob = launch {
            while (isActive) {
                val elapsedSec = (System.currentTimeMillis() - inferenceStartMs) / 1000f
                val fraction = (elapsedSec / estimatedTotalSec).coerceIn(0f, 0.96f)
                val currentProgress = 0.35f + fraction * 0.58f
                val elapsedMin = (elapsedSec / 60).toInt()
                val elapsedS = (elapsedSec % 60).toInt()
                val estTotalMin = (estimatedTotalSec / 60).toInt()
                val estTotalS = (estimatedTotalSec % 60).toInt()

                val timeMsg = if (audioDurationSec > 30f) {
                    String.format(
                        Locale.getDefault(),
                        "Running Whisper inference (%02d:%02d / ~%02d:%02d)",
                        elapsedMin, elapsedS, estTotalMin, estTotalS
                    )
                } else {
                    "Running local Whisper inference..."
                }
                onProgress(currentProgress, timeMsg)
                delay(350)
            }
        }

        val rawResult = try {
            whisperEngine.transcribe(
                samples,
                language = getLanguage(),
                params = transcriptionParams
            )
        } finally {
            tickerJob.cancel()
        }

        onProgress(0.95f, "Applying local post-processing...")

        rawResult
    }

    /**
     * Builds the transcription params from the persisted AI-engine settings.
     * Live dictation keeps single_segment=true; the shared-file path overrides
     * that (multi-segment + timestamps) for the timeline UI.
     */
    private fun currentWhisperParams(): WhisperParams {
        return WhisperParams(
            language = getLanguage(),
            initialPrompt = getInitialPrompt(),
            noSpeechThold = getNoSpeechThold(),
            logprobThold = getLogprobThold(),
            entropyThold = getEntropyThold()
        )
    }

    private fun vadPathFor(sampleCount: Int, sharedFile: Boolean): String? {
        if (!getUseVad()) return null
        return _vadModelPath.value
    }

    // History Operations
    suspend fun insertHistory(history: TranscriptionHistory) = withContext(Dispatchers.IO) {
        if (!getSaveHistory()) return@withContext
        historyDao.insertHistory(history)
        pruneHistory(getHistoryLimit())
    }

    suspend fun deleteHistoryById(id: Int) = withContext(Dispatchers.IO) {
        historyDao.deleteHistoryById(id)
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        historyDao.clearHistory()
    }

    // Stats Operations
    suspend fun insertStat(stat: DictationStat) = withContext(Dispatchers.IO) {
        statsDao.insertStat(stat)
    }

    suspend fun clearStats() = withContext(Dispatchers.IO) {
        statsDao.clearStats()
    }

    // Dictionary Operations
    suspend fun insertWord(word: DictionaryWord) = withContext(Dispatchers.IO) {
        dictionaryDao.insertWord(word)
        refreshDictionarySnapshot()
    }

    suspend fun deleteWordById(id: Int) = withContext(Dispatchers.IO) {
        dictionaryDao.deleteWordById(id)
        refreshDictionarySnapshot()
    }

    suspend fun getWordsList(): List<DictionaryWord> = withContext(Dispatchers.IO) {
        dictionaryDao.getWordsList()
    }

    private suspend fun refreshDictionarySnapshot() {
        dictionarySnapshot = dictionaryDao.getWordsList()
        invalidateRegexCache()
    }

    fun invalidateRegexCache() {
        lastDictHash = 0
        cachedWordRegexes = emptyList()
        cachedWordReplacements = emptyList()
    }

    // Advanced Local Post-Processing Pipeline
    suspend fun postProcessText(
        text: String,
        smartPunctuation: Boolean,
        autoCapitalize: Boolean,
        applyDict: Boolean,
        useAiPolisher: Boolean = false,
        cleanupMode: CleanupMode = getCleanupMode()
    ): String = withContext(Dispatchers.Default) {
        var result = text

        // 1. Clean extra spaces
        result = result.replace(REGEX_SPACES, " ").trim()

        // 2. Dictionary Replacements & Misheard Vocabulary Biasing
        if (applyDict) {
            val words = dictionarySnapshot
            val dictHash = words.fold(0) { acc, w -> 31 * acc + w.id + w.word.hashCode() }
            if (dictHash != lastDictHash) {
                val regexes = mutableListOf<Regex>()
                val replacements = mutableListOf<String>()
                for (dictWord in words) {
                    if (dictWord.replacement.isNotBlank()) {
                        val variants = dictWord.replacement.split(",")
                        for (variant in variants) {
                            val trimmedVariant = variant.trim()
                            if (trimmedVariant.isNotEmpty()) {
                                regexes.add(Regex("(?i)\\b${Regex.escape(trimmedVariant)}\\b"))
                                replacements.add(dictWord.word)
                            }
                        }
                    }
                    regexes.add(Regex("(?i)\\b${Regex.escape(dictWord.word)}\\b"))
                    replacements.add(dictWord.word)
                }
                cachedWordRegexes = regexes
                cachedWordReplacements = replacements
                lastDictHash = dictHash
            }
            for (i in cachedWordRegexes.indices) {
                result = result.replace(cachedWordRegexes[i], cachedWordReplacements[i])
            }
        }

        // 3. Smart Punctuation. Spoken punctuation commands are opt-in because
        // blanket word replacement can corrupt intended words (e.g. "coma").
        if (smartPunctuation) {
            if (getSpokenPunctuationCommands()) {
                result = applySpokenPunctuationCommands(result)
            }

            // Clean spaces BEFORE punctuation: "hola ," -> "hola,"
            result = result.replace(REGEX_SPACES_BEFORE_PUNCT, "$1")

            // Ensure single space AFTER punctuation if followed by a letter: "hola,mundo" -> "hola, mundo"
            result = result.replace(REGEX_SPACE_AFTER_PUNCT, "$1 $2")

            // Clean duplicate commas or periods
            result = result.replace(REGEX_DUP_COMMAS, ",")
            result = result.replace(REGEX_DUP_PERIODS_SPACED, ".")
            result = result.replace(REGEX_DUP_PERIODS_EXACT2, ".")
            result = result.replace(REGEX_DUP_PERIODS_4PLUS, "...")

            // 3.5. Grammatical Intent & Question Detection (Spanish + English)
            val lines = result.split("\n")
            val processedLines = lines.map { line ->
                val rawSentences = line.split(REGEX_SENTENCE_SPLIT).toMutableList()
                for (i in rawSentences.indices) {
                    val sentence = rawSentences[i].trim()
                    if (sentence.isEmpty() || sentence.endsWith("?") || sentence.endsWith("!")) continue

                    // Check Spanish Question Intent
                    val isSpanishQuestion = REGEX_ES_QUESTION_START.containsMatchIn(sentence) || REGEX_ES_QUESTION_END.containsMatchIn(sentence)

                    // Check English Question Intent
                    val isEnglishQuestion = REGEX_EN_QUESTION_START.containsMatchIn(sentence) || REGEX_EN_QUESTION_END.containsMatchIn(sentence)

                    if (isSpanishQuestion || isEnglishQuestion) {
                        var formatted = sentence.removeSuffix(".")
                        if (isSpanishQuestion && !formatted.startsWith("¿")) {
                            formatted = "¿$formatted"
                        }
                        rawSentences[i] = "$formatted?"
                    }
                }
                rawSentences.joinToString(" ")
            }
            result = processedLines.joinToString("\n")
        }

        // 4. Auto-Capitalization
        if (autoCapitalize && result.isNotEmpty()) {
            // Capitalize start of string
            result = result.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

            // Capitalize first letter after sentence ending punctuation (. ! ? ¿ ¡ " () or newlines
            result = REGEX_AUTO_CAPITALIZE.replace(result) { matchResult ->
                matchResult.groupValues[1] + matchResult.groupValues[2].uppercase()
            }
        }

        result = result.trim()

        // 5. Optional local rule-based "AI" polisher pass (filler removal, punctuation polish)
        if (useAiPolisher) {
            result = textPolishEngine.polish(result, getLanguage(), cleanupMode)
        }

        result
    }

    internal fun applySpokenPunctuationCommands(text: String): String {
        var result = text
        for ((regex, replacement) in SPOKEN_PUNCTUATION_COMMANDS) {
            result = result.replace(regex, replacement)
        }
        return formatPunctuationSpacing(result)
    }

    internal fun formatPunctuationSpacing(text: String): String {
        var result = text

        // 1. Closing punctuation attaches to preceding word without leading space:
        // (',', '.', ';', ':', '?', '!', ')', '"', '...')
        result = result.replace(REGEX_SPACES_BEFORE_CLOSING_PUNCT, "$1")
        result = result.replace(REGEX_RAW_CLOSING_QUOTE_SPACE, "$1\"")

        // 2. Opening punctuation attaches to following word:
        // ('¿', '¡', '(', '"')
        result = result.replace(REGEX_SPACES_AFTER_OPENING_PUNCT, "$1")
        result = result.replace(REGEX_RAW_OPENING_QUOTE_SPACE, "\"$1")

        // 3. Opening punctuation has a space before if preceded by a letter/word character:
        result = result.replace(REGEX_SPACE_BEFORE_OPENING_PUNCT, "$1 $2")
        result = result.replace(REGEX_RAW_OPENING_QUOTE_LETTER_BEFORE, "$1 \"")

        // 4. Ensure single space after closing punctuation if followed by a letter:
        result = result.replace(REGEX_SPACE_AFTER_CLOSING_PUNCT, "$1 $2")
        result = result.replace(REGEX_RAW_CLOSING_QUOTE_LETTER_AFTER, "$1 $2")

        // 5. Ensure single space between closing punctuation and opening punctuation:
        result = result.replace(REGEX_SPACE_CLOSING_TO_OPENING, "$1 $2")

        // 6. Convert quote tokens to standard quote marks:
        result = result.replace(TOKEN_OPEN_QUOTE, "\"").replace(TOKEN_CLOSE_QUOTE, "\"")

        // 7. Clean duplicate commas or periods (protecting ellipsis ...)
        result = result.replace(REGEX_DUP_COMMAS, ",")
        result = result.replace(REGEX_DUP_PERIODS_SPACED, ".")
        result = result.replace(REGEX_DUP_PERIODS_EXACT2, ".")
        result = result.replace(REGEX_DUP_PERIODS_4PLUS, "...")

        // 8. Newline formatting: clean line breaks without trailing or leading stray spaces on the new line:
        result = result.replace(REGEX_CLEAN_NEWLINES, "\n")
        result = result.replace(REGEX_COLLAPSE_NEWLINES, "\n\n")

        // 9. Clean duplicate spaces on horizontal lines:
        result = result.replace(REGEX_CLEAN_HORIZONTAL_SPACES, " ")

        return result.trim()
    }

    companion object {
        private val REGEX_SPACES = Regex("\\s+")

        internal const val TOKEN_OPEN_QUOTE = "\uE000"
        internal const val TOKEN_CLOSE_QUOTE = "\uE001"

        private val REGEX_SPACES_BEFORE_CLOSING_PUNCT = Regex("[ \\t]+([,.?!:;)\\uE001]|\\.\\.\\.)")
        private val REGEX_RAW_CLOSING_QUOTE_SPACE = Regex("([\\wñáéíóúüàâçèêëîïôùûäöß])[ \\t]+\"")
        private val REGEX_SPACES_AFTER_OPENING_PUNCT = Regex("([¿¡(\\uE000])[ \\t]+")
        private val REGEX_RAW_OPENING_QUOTE_SPACE = Regex("\"[ \\t]+([\\wñáéíóúüàâçèêëîïôùûäöß])")
        private val REGEX_SPACE_BEFORE_OPENING_PUNCT = Regex("([\\wñáéíóúüàâçèêëîïôùûäöß])([¿¡(\\uE000])")
        private val REGEX_RAW_OPENING_QUOTE_LETTER_BEFORE = Regex("([\\wñáéíóúüàâçèêëîïôùûäöß])\"(?=[\\wñáéíóúüàâçèêëîïôùûäöß])")
        private val REGEX_SPACE_AFTER_CLOSING_PUNCT = Regex("([,.?!:;)\\uE001]|\\.\\.\\.)([a-zA-Zñáéíóúüàâçèêëîïôùûäöß])")
        private val REGEX_RAW_CLOSING_QUOTE_LETTER_AFTER = Regex("([\\wñáéíóúüàâçèêëîïôùûäöß]\")([a-zA-Zñáéíóúüàâçèêëîïôùûäöß])")
        private val REGEX_SPACE_CLOSING_TO_OPENING = Regex("([,.?!:;)\\uE001\"]|\\.\\.\\.)([¿¡(\\uE000])")

        private val REGEX_CLEAN_NEWLINES = Regex("[ \\t]*\\r?\\n[ \\t]*")
        private val REGEX_COLLAPSE_NEWLINES = Regex("\\n{3,}")
        private val REGEX_CLEAN_HORIZONTAL_SPACES = Regex("[ \\t]{2,}")

        private val REGEX_SPACES_BEFORE_PUNCT = Regex("[ \\t]+([,.?!:;)])")
        private val REGEX_SPACE_AFTER_PUNCT = Regex("([,.?!:;)])([^\\s\\d,.?!:;)])")
        private val REGEX_DUP_COMMAS = Regex(",(?:[ \\t]*,)+")
        private val REGEX_DUP_PERIODS_EXACT2 = Regex("(?<!\\.)\\.{2}(?!\\.)")
        private val REGEX_DUP_PERIODS_SPACED = Regex("(?<!\\.)\\.[ \\t]+\\.(?!\\.)")
        private val REGEX_DUP_PERIODS_4PLUS = Regex("\\.{4,}")
        private val REGEX_SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+")

        private val REGEX_ES_QUESTION_START = Regex("(?i)^\\s*([¿]|qu[eé]|cu[aá]l|cu[aá]les|qui[eé]n|qui[eé]nes|d[oó]nde|cu[aá]ndo|por\\s*qu[eé]|c[oó]mo|cu[aá]nto|cu[aá]ntos|cu[aá]nta|cu[aá]ntas|sabes|sabes\\s+si|ser[aá]|te\\s+parece|puedes|podr[ií]as|quieres|tienes|crees|te\\s+gustar[ií]a)\\b")
        private val REGEX_ES_QUESTION_END = Regex("(?i)\\b(verdad|cierto|no\\s+crees|o\\s+no)\\s*[.]?$")
        private val REGEX_EN_QUESTION_START = Regex("(?i)^\\s*(what|why|where|when|who|whom|whose|which|how|is|are|was|were|do|does|did|can|could|would|should|will|shall|have|has|had|am|isnt|arent|wasnt|werent|dont|doesnt|didnt|cant|couldnt|wouldnt|shouldnt|wont)\\s+(you|i|we|it|he|she|they|this|that|there)\\b")
        private val REGEX_EN_QUESTION_END = Regex("(?i)\\b(right|correct|is\\s+it|don't\\s+you|don't\\s+you\\s+think)\\s*[.]?$")
        private val REGEX_AUTO_CAPITALIZE = Regex("([.!?¿¡\\n\"(]\\s*)([a-zñáéíóúüàâçèêëîïôùûäöß])")

        internal val SPOKEN_PUNCTUATION_COMMANDS = listOf(
            // Spanish
            Regex("(?iu)\\bpuntos\\s+suspensivos\\b") to "...",
            Regex("(?iu)\\bpunto\\s+y\\s+coma\\b") to ";",
            Regex("(?iu)\\bdos\\s+puntos\\b") to ":",
            Regex("(?iu)\\bpunto\\s+(?:final|y\\s+seguido|y\\s+aparte)\\b") to ".",
            Regex("(?iu)\\babrir\\s+(?:signo\\s+de\\s+)?interrogaci[oó]n\\b") to "¿",
            Regex("(?iu)\\b(?:cerrar\\s+(?:signo\\s+de\\s+)?interrogaci[oó]n|signo\\s+de\\s+interrogaci[oó]n)\\b") to "?",
            Regex("(?iu)\\babrir\\s+(?:signo\\s+de\\s+)?(?:exclamaci[oó]n|admiraci[oó]n)\\b") to "¡",
            Regex("(?iu)\\b(?:cerrar\\s+(?:signo\\s+de\\s+)?(?:exclamaci[oó]n|admiraci[oó]n)|signo\\s+de\\s+(?:exclamaci[oó]n|admiraci[oó]n))\\b") to "!",
            Regex("(?iu)\\babrir\\s+comillas\\b") to TOKEN_OPEN_QUOTE,
            Regex("(?iu)\\bcerrar\\s+comillas\\b") to TOKEN_CLOSE_QUOTE,
            Regex("(?iu)\\babrir\\s+par[eé]ntesis\\b") to "(",
            Regex("(?iu)\\bcerrar\\s+par[eé]ntesis\\b") to ")",
            Regex("(?iu)\\bnuevo\\s+p[aá]rrafo\\b") to "\n\n",
            Regex("(?iu)\\bnueva\\s+l[ií]nea\\b") to "\n",
            Regex("(?iu)\\bpunto\\b") to ".",
            Regex("(?iu)\\bcoma\\b") to ",",
            Regex("(?iu)\\bgu[ií]?[oó]n\\b") to "-",

            // English
            Regex("(?iu)\\bdot\\s+dot\\s+dot\\b") to "...",
            Regex("(?iu)\\bellipsis\\b") to "...",
            Regex("(?iu)\\bquestion\\s+mark\\b") to "?",
            Regex("(?iu)\\bexclamation\\s+(?:mark|point)\\b") to "!",
            Regex("(?iu)\\bsemicolon\\b") to ";",
            Regex("(?iu)\\bcolon\\b") to ":",
            Regex("(?iu)\\bopen\\s+quot(?:ation\\s+mark|e)\\b") to TOKEN_OPEN_QUOTE,
            Regex("(?iu)\\bclose\\s+quot(?:ation\\s+mark|e)\\b") to TOKEN_CLOSE_QUOTE,
            Regex("(?iu)\\bopen\\s+paren(?:thesis)?\\b") to "(",
            Regex("(?iu)\\bclose\\s+paren(?:thesis)?\\b") to ")",
            Regex("(?iu)\\bnew\\s+paragraph\\b") to "\n\n",
            Regex("(?iu)\\bnew\\s+line\\b") to "\n",
            Regex("(?iu)\\bfull\\s+stop\\b") to ".",
            Regex("(?iu)\\bperiod\\b") to ".",
            Regex("(?iu)\\bcomma\\b") to ",",
            Regex("(?iu)\\b(?:hyphen|dash)\\b") to "-",
        )
    }
}
