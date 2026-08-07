package dev.sebastian.vozlocal.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.sebastian.vozlocal.audio.AudioDecoder
import dev.sebastian.vozlocal.data.local.AppDatabase
import dev.sebastian.vozlocal.data.model.DictationModel
import dev.sebastian.vozlocal.data.model.DictationStat
import dev.sebastian.vozlocal.data.model.DictionaryWord
import dev.sebastian.vozlocal.data.model.TranscriptionHistory
import dev.sebastian.vozlocal.polish.QwenEngine
import dev.sebastian.vozlocal.whisper.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

import androidx.core.content.edit

private const val TAG = "DictationRepository"

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
    val qwenEngine = QwenEngine()

    // Regex cache for the dictionary replacement pass, invalidated whenever words change.
    private var cachedWordRegexes: List<Regex> = emptyList()
    private var cachedWordReplacements: List<String> = emptyList()
    private var lastDictHash: Int = 0

    val allModels: Flow<List<DictationModel>> = modelDao.getAllModels().map { list ->
        list.sortedBy { model -> when (model.id) {
            "whisper_tiny" -> 1
            "whisper_base" -> 2
            "whisper_small" -> 3
            "whisper_medium" -> 4
            "whisper_large_v3_turbo" -> 5
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

    suspend fun shutdown() {
        whisperEngine.release()
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
        // Backward-compat: kick off the seed work on the repository's own IO scope
        // for callers that don't manage their own scope. The application also calls
        // initializeModels() from its own long-lived scope.
        CoroutineScope(Dispatchers.IO).launch { initializeModels() }
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
                    id = "whisper_tiny",
                    name = "Whisper Tiny (Multi-Language)",
                    sizeMb = 42f,  // q8_0 quantized
                    accuracySpanish = 72,
                    accuracyEnglish = 79,
                    speedMultiplier = 8.5f,
                    isDownloaded = ModelUrls.isModelDownloaded(context, "whisper_tiny"),
                    isSelected = true
                ),
                DictationModel(
                    id = "whisper_base",
                    name = "Whisper Base (Standard)",
                    sizeMb = 78f,  // q8_0 quantized
                    accuracySpanish = 83,
                    accuracyEnglish = 89,
                    speedMultiplier = 5.0f,
                    isDownloaded = ModelUrls.isModelDownloaded(context, "whisper_base"),
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
                    id = "whisper_medium",
                    name = "Whisper Medium (Ultra Quality)",
                    sizeMb = 823f,  // q8_0 quantized
                    accuracySpanish = 97,
                    accuracyEnglish = 99,
                    speedMultiplier = 1.0f,
                    isDownloaded = ModelUrls.isModelDownloaded(context, "whisper_medium"),
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing default models", e)
        }
    }

    // Models Operations
    suspend fun selectModel(modelId: String) = withContext(Dispatchers.IO) {
        modelDao.selectModel(modelId)
    }

    @Suppress("unused")
    suspend fun updateModel(model: DictationModel) = withContext(Dispatchers.IO) {
        modelDao.updateModel(model)
    }

    fun startModelDownload(modelId: String, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                val models = allModels.first()
                var model = models.find { it.id == modelId } ?: return@launch
                if (model.isDownloaded || model.isDownloading) return@launch

                model = model.copy(isDownloading = true, downloadProgress = 0.01f)
                modelDao.updateModel(model)

                val success = modelDownloader.downloadModel(modelId) { progress ->
                    model = model.copy(isDownloading = true, downloadProgress = progress)
                    modelDao.updateModel(model)
                }

                if (success) {
                    modelDao.updateModel(model.copy(
                        isDownloading = false,
                        isDownloaded = true,
                        downloadProgress = 1.0f
                    ))
                } else {
                    modelDao.updateModel(model.copy(
                        isDownloading = false,
                        isDownloaded = false,
                        downloadProgress = 0.0f
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading model $modelId", e)
                val models = allModels.first()
                val model = models.find { it.id == modelId }
                if (model != null) {
                    modelDao.updateModel(model.copy(isDownloading = false, downloadProgress = 0.0f))
                }
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

        // Ensure Whisper engine is loaded with target model
        val loaded = whisperEngine.loadModel(modelId)
        if (!loaded) {
            Log.e(TAG, "Could not load Whisper model $modelId for transcription")
            return@withContext "Model not downloaded. Please download $modelId in the Models tab first."
        }

        val language = getLanguage()
        whisperEngine.transcribe(samples, language)
    }

    suspend fun transcribeSharedFile(
        uri: Uri,
        modelId: String,
        onProgress: (Float, String) -> Unit
    ): String = withContext(Dispatchers.Default) {
        onProgress(0.10f, "Decoding audio file...")
        val samples = audioDecoder.decodeToPcm16k(uri) { prog ->
            onProgress(0.10f + prog * 0.40f, "Decoding audio file...")
        }

        if (samples.isEmpty()) {
            return@withContext "Error: Failed to decode audio file."
        }

        onProgress(0.55f, "Loading local Whisper model...")
        val loaded = whisperEngine.loadModel(modelId)
        if (!loaded) {
            return@withContext "Error: Local Whisper model $modelId is not downloaded yet. Please download it first."
        }

        onProgress(0.70f, "Running local Whisper inference...")
        val language = getLanguage()
        val rawResult = whisperEngine.transcribe(samples, language)
        onProgress(0.95f, "Applying local post-processing...")

        rawResult
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
        invalidateRegexCache()
    }

    suspend fun deleteWordById(id: Int) = withContext(Dispatchers.IO) {
        dictionaryDao.deleteWordById(id)
        invalidateRegexCache()
    }

    suspend fun getWordsList(): List<DictionaryWord> = withContext(Dispatchers.IO) {
        dictionaryDao.getWordsList()
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
        useAiPolisher: Boolean = false
    ): String = withContext(Dispatchers.Default) {
        var result = text

        // 1. Clean extra spaces
        result = result.replace(REGEX_SPACES, " ").trim()

        // 2. Dictionary Replacements & Misheard Vocabulary Biasing
        if (applyDict) {
            val words = getWordsList()
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

        // 3. Smart Punctuation & Spoken Commands (Verbalized punctuation & Pause formatting)
        if (smartPunctuation) {
            // Verbalized Punctuation - Spanish
            result = result.replace(REGEX_ES_PUNTO, ".")
            result = result.replace(REGEX_ES_COMA, ",")
            result = result.replace(REGEX_ES_DOS_PUNTOS, ":")
            result = result.replace(REGEX_ES_INTERROGACION, "?")
            result = result.replace(REGEX_ES_EXCLAMACION, "!")
            result = result.replace(REGEX_ES_NUEVA_LINEA, "\n")
            result = result.replace(REGEX_ES_NUEVO_PARRAFO, "\n\n")

            // Verbalized Punctuation - English
            result = result.replace(REGEX_EN_PERIOD, ".")
            result = result.replace(REGEX_EN_FULL_STOP, ".")
            result = result.replace(REGEX_EN_COMMA, ",")
            result = result.replace(REGEX_EN_COLON, ":")
            result = result.replace(REGEX_EN_QUESTION_MARK, "?")
            result = result.replace(REGEX_EN_EXCLAMATION_MARK, "!")
            result = result.replace(REGEX_EN_NEW_LINE, "\n")
            result = result.replace(REGEX_EN_NEW_PARAGRAPH, "\n\n")

            // Clean spaces BEFORE punctuation: "hola ," -> "hola,"
            result = result.replace(REGEX_SPACES_BEFORE_PUNCT, "$1")

            // Ensure single space AFTER punctuation if followed by a letter: "hola,mundo" -> "hola, mundo"
            result = result.replace(REGEX_SPACE_AFTER_PUNCT, "$1 $2")

            // Clean duplicate commas or periods
            result = result.replace(REGEX_DUP_COMMAS, ",")
            result = result.replace(REGEX_DUP_PERIODS, ".")

            // 3.5. Grammatical Intent & Question Detection (Spanish + English)
            val rawSentences = result.split(REGEX_SENTENCE_SPLIT).toMutableList()
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
            result = rawSentences.joinToString(" ")
        }

        // 4. Auto-Capitalization
        if (autoCapitalize && result.isNotEmpty()) {
            // Capitalize start of string
            result = result.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

            // Capitalize first letter after sentence ending punctuation (. ! ? ¿) or newlines
            result = REGEX_AUTO_CAPITALIZE.replace(result) { matchResult ->
                matchResult.groupValues[1] + matchResult.groupValues[2].uppercase()
            }
        }

        result = result.trim()

        // 5. Optional local rule-based "AI" polisher pass (filler removal, punctuation polish)
        if (useAiPolisher) {
            result = qwenEngine.polish(result, getLanguage())
        }

        result
    }

    companion object {
        private val REGEX_SPACES = Regex("\\s+")
        private val REGEX_ES_PUNTO = Regex("(?i)\\bpunto\\b")
        private val REGEX_ES_COMA = Regex("(?i)\\bcoma\\b")
        private val REGEX_ES_DOS_PUNTOS = Regex("(?i)\\bdos puntos\\b")
        private val REGEX_ES_INTERROGACION = Regex("(?i)\\bsigno de (interrogacion|interrogación)\\b")
        private val REGEX_ES_EXCLAMACION = Regex("(?i)\\bsigno de (exclamacion|exclamación)\\b")
        private val REGEX_ES_NUEVA_LINEA = Regex("(?i)\\bnueva l[ií]nea\\b")
        private val REGEX_ES_NUEVO_PARRAFO = Regex("(?i)\\bnuevo p[aá]rrafo\\b")

        private val REGEX_EN_PERIOD = Regex("(?i)\\bperiod\\b")
        private val REGEX_EN_FULL_STOP = Regex("(?i)\\bfull stop\\b")
        private val REGEX_EN_COMMA = Regex("(?i)\\bcomma\\b")
        private val REGEX_EN_COLON = Regex("(?i)\\bcolon\\b")
        private val REGEX_EN_QUESTION_MARK = Regex("(?i)\\bquestion mark\\b")
        private val REGEX_EN_EXCLAMATION_MARK = Regex("(?i)\\bexclamation (mark|point)\\b")
        private val REGEX_EN_NEW_LINE = Regex("(?i)\\bnew line\\b")
        private val REGEX_EN_NEW_PARAGRAPH = Regex("(?i)\\bnew paragraph\\b")

        private val REGEX_SPACES_BEFORE_PUNCT = Regex("\\s+([,.?!:])")
        private val REGEX_SPACE_AFTER_PUNCT = Regex("([,.?!:])([^\\s\\d,.?!:])")
        private val REGEX_DUP_COMMAS = Regex(",\\s*,")
        private val REGEX_DUP_PERIODS = Regex("\\.\\s*\\.(?!\\.)")
        private val REGEX_SENTENCE_SPLIT = Regex("(?<=[.\\n])\\s*")

        private val REGEX_ES_QUESTION_START = Regex("(?i)^\\s*([¿]|qu[eé]|cu[aá]l|cu[aá]les|qui[eé]n|qui[eé]nes|d[oó]nde|cu[aá]ndo|por\\s*qu[eé]|c[oó]mo|cu[aá]nto|cu[aá]ntos|cu[aá]nta|cu[aá]ntas|sabes|sabes\\s+si|ser[aá]|te\\s+parece|puedes|podr[ií]as|quieres|tienes|crees|te\\s+gustar[ií]a)\\b")
        private val REGEX_ES_QUESTION_END = Regex("(?i)\\b(verdad|cierto|no\\s+crees|o\\s+no)\\s*[.]?$")
        private val REGEX_EN_QUESTION_START = Regex("(?i)^\\s*(what|why|where|when|who|whom|whose|which|how|is|are|was|were|do|does|did|can|could|would|should|will|shall|have|has|had|am|isnt|arent|wasnt|werent|dont|doesnt|didnt|cant|couldnt|wouldnt|shouldnt|wont)\\s+(you|i|we|it|he|she|they|this|that|there)\\b")
        private val REGEX_EN_QUESTION_END = Regex("(?i)\\b(right|correct|is\\s+it|don't\\s+you|don't\\s+you\\s+think)\\s*[.]?$")
        private val REGEX_AUTO_CAPITALIZE = Regex("([.!?¿\\n]\\s*)([a-zñáéíóú])")
    }
}
