package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.audio.AudioDecoder
import com.example.data.local.AppDatabase
import com.example.data.model.DictationModel
import com.example.data.model.DictationStat
import com.example.data.model.DictionaryWord
import com.example.data.model.TranscriptionHistory
import com.example.whisper.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

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

    val allModels: Flow<List<DictationModel>> = modelDao.getAllModels().map { list ->
        list.sortedBy { model ->
            if (model.id.startsWith("qwen")) 99 else when (model.id) {
                "whisper_tiny" -> 1
                "whisper_base" -> 2
                "whisper_small" -> 3
                "whisper_medium" -> 4
                "whisper_large_v3_turbo" -> 5
                else -> 10
            }
        }
    }
    val allHistory: Flow<List<TranscriptionHistory>> = historyDao.getAllHistory()
    val allWords: Flow<List<DictionaryWord>> = dictionaryDao.getAllWords()
    val allStats: Flow<List<DictationStat>> = statsDao.getAllStats()

    fun getHistoryLimit(): Int {
        return prefs.getInt("history_limit", -1)
    }

    fun saveHistoryLimit(limit: Int) {
        prefs.edit().putInt("history_limit", limit).apply()
    }

    fun getShowOnlyOnInput(): Boolean {
        return prefs.getBoolean("show_only_on_input", true)
    }

    fun saveShowOnlyOnInput(value: Boolean) {
        prefs.edit().putBoolean("show_only_on_input", value).apply()
    }

    // Language preference — "es" by default, avoids expensive auto-detection (~200-500ms)
    fun getLanguage(): String {
        return prefs.getString("whisper_language", "es") ?: "es"
    }

    fun saveLanguage(language: String) {
        prefs.edit().putString("whisper_language", language).apply()
    }

    fun getUseAiPolisher(): Boolean {
        return prefs.getBoolean("use_ai_polisher", false)
    }

    fun saveUseAiPolisher(value: Boolean) {
        prefs.edit().putBoolean("use_ai_polisher", value).apply()
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
        CoroutineScope(Dispatchers.IO).launch {
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
                    ),
                    DictationModel(
                        id = "qwen2.5_0.5b",
                        name = "Qwen2.5 0.5B (AI Text Polisher)",
                        sizeMb = 398f,  // Q4_K_M quantized
                        accuracySpanish = 99,
                        accuracyEnglish = 99,
                        speedMultiplier = 12.0f,
                        isDownloaded = ModelUrls.isModelDownloaded(context, "qwen2.5_0.5b"),
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

                    // Update download status for existing models in database and reset stale downloading flags
                    val updatedList = allModels.first()
                    for (model in updatedList) {
                        val downloaded = ModelUrls.isModelDownloaded(context, model.id)
                        if (model.isDownloaded != downloaded || model.isDownloading) {
                            modelDao.updateModel(model.copy(
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
    }

    // Models Operations
    suspend fun selectModel(modelId: String) = withContext(Dispatchers.IO) {
        modelDao.selectModel(modelId)
    }

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
    }

    suspend fun deleteWordById(id: Int) = withContext(Dispatchers.IO) {
        dictionaryDao.deleteWordById(id)
    }

    suspend fun getWordsList(): List<DictionaryWord> = withContext(Dispatchers.IO) {
        dictionaryDao.getWordsList()
    }

    // Advanced Local Post-Processing Pipeline
    suspend fun postProcessText(
        text: String,
        smartPunctuation: Boolean,
        autoCapitalize: Boolean,
        applyDict: Boolean
    ): String = withContext(Dispatchers.Default) {
        var result = text

        // 1. Clean extra spaces
        result = result.replace(Regex("\\s+"), " ").trim()

        // 2. Dictionary Replacements & Misheard Vocabulary Biasing
        if (applyDict) {
            val words = getWordsList()
            for (dictWord in words) {
                if (dictWord.replacement.isNotBlank()) {
                    val variants = dictWord.replacement.split(",")
                    for (variant in variants) {
                        val trimmedVariant = variant.trim()
                        if (trimmedVariant.isNotEmpty()) {
                            val regex = Regex("(?i)\\b${Regex.escape(trimmedVariant)}\\b")
                            result = result.replace(regex, dictWord.word)
                        }
                    }
                }
                val directRegex = Regex("(?i)\\b${Regex.escape(dictWord.word)}\\b")
                result = result.replace(directRegex, dictWord.word)
            }
        }

        // 3. Smart Punctuation & Spoken Commands (Verbalized punctuation & Pause formatting)
        if (smartPunctuation) {
            // Verbalized Punctuation - Spanish
            result = result.replace(Regex("(?i)\\bpunto\\b"), ".")
            result = result.replace(Regex("(?i)\\bcoma\\b"), ",")
            result = result.replace(Regex("(?i)\\bdos puntos\\b"), ":")
            result = result.replace(Regex("(?i)\\bsigno de (interrogacion|interrogación)\\b"), "?")
            result = result.replace(Regex("(?i)\\bsigno de (exclamacion|exclamación)\\b"), "!")
            result = result.replace(Regex("(?i)\\bnueva l[ií]nea\\b"), "\n")
            result = result.replace(Regex("(?i)\\bnuevo p[aá]rrafo\\b"), "\n\n")

            // Verbalized Punctuation - English
            result = result.replace(Regex("(?i)\\bperiod\\b"), ".")
            result = result.replace(Regex("(?i)\\bfull stop\\b"), ".")
            result = result.replace(Regex("(?i)\\bcomma\\b"), ",")
            result = result.replace(Regex("(?i)\\bcolon\\b"), ":")
            result = result.replace(Regex("(?i)\\bquestion mark\\b"), "?")
            result = result.replace(Regex("(?i)\\bexclamation (mark|point)\\b"), "!")
            result = result.replace(Regex("(?i)\\bnew line\\b"), "\n")
            result = result.replace(Regex("(?i)\\bnew paragraph\\b"), "\n\n")

            // Clean spaces BEFORE punctuation: "hola ," -> "hola,"
            result = result.replace(Regex("\\s+([,.?!:])"), "$1")

            // Ensure single space AFTER punctuation if followed by a letter: "hola,mundo" -> "hola, mundo"
            result = result.replace(Regex("([,.?!:])([^\\s\\d,.?!:])"), "$1 $2")

            // Clean duplicate commas or periods
            result = result.replace(Regex(",\\s*,"), ",")
            result = result.replace(Regex("\\.\\s*\\.(?!\\.)"), ".")

            // 3.5. Grammatical Intent & Question Detection (Spanish + English)
            val rawSentences = result.split(Regex("(?<=[.\\n])\\s*")).toMutableList()
            for (i in rawSentences.indices) {
                val sentence = rawSentences[i].trim()
                if (sentence.isEmpty() || sentence.endsWith("?") || sentence.endsWith("!")) continue

                // Check Spanish Question Intent
                val isSpanishQuestion = Regex("(?i)^\\s*([¿]|qu[eé]|cu[aá]l|cu[aá]les|qui[eé]n|qui[eé]nes|d[oó]nde|cu[aá]ndo|por\\s*qu[eé]|c[oó]mo|cu[aá]nto|cu[aá]ntos|cu[aá]nta|cu[aá]ntas|sabes|sabes\\s+si|ser[aá]|te\\s+parece|puedes|podr[ií]as|quieres|tienes|crees|te\\s+gustar[ií]a)\\b").containsMatchIn(sentence) ||
                        Regex("(?i)\\b(verdad|cierto|no\\s+crees|o\\s+no)\\s*[.]?$").containsMatchIn(sentence)

                // Check English Question Intent
                val isEnglishQuestion = Regex("(?i)^\\s*(what|why|where|when|who|whom|whose|which|how|is|are|was|were|do|does|did|can|could|would|should|will|shall|have|has|had|am|isnt|arent|wasnt|werent|dont|doesnt|didnt|cant|couldnt|wouldnt|shouldnt|wont)\\s+(you|i|we|it|he|she|they|this|that|there)\\b").containsMatchIn(sentence) ||
                        Regex("(?i)\\b(right|correct|is\\s+it|don't\\s+you|don't\\s+you\\s+think)\\s*[.]?$").containsMatchIn(sentence)

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
            val pattern = Regex("([.!?¿\\n]\\s*)([a-zñáéíóú])")
            result = pattern.replace(result) { matchResult ->
                matchResult.groupValues[1] + matchResult.groupValues[2].uppercase()
            }
        }

        result.trim()
    }
}
