package com.example.data.repository

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.model.DictationModel
import com.example.data.model.DictionaryWord
import com.example.data.model.TranscriptionHistory
import com.example.data.model.DictationStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class DictationRepository(context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val modelDao = database.modelDao()
    private val historyDao = database.historyDao()
    private val dictionaryDao = database.dictionaryDao()
    private val statsDao = database.statsDao()

    private val prefs = context.getSharedPreferences("vozlocal_prefs", Context.MODE_PRIVATE)

    val allModels: Flow<List<DictationModel>> = modelDao.getAllModels()
    val allHistory: Flow<List<TranscriptionHistory>> = historyDao.getAllHistory()
    val allWords: Flow<List<DictionaryWord>> = dictionaryDao.getAllWords()
    val allStats: Flow<List<DictationStat>> = statsDao.getAllStats()

    fun getHistoryLimit(): Int {
        return prefs.getInt("history_limit", -1) // -1 means Unlimited
    }

    fun saveHistoryLimit(limit: Int) {
        prefs.edit().putInt("history_limit", limit).apply()
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
        // Prepopulate models asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val current = allModels.first()
                if (current.isEmpty()) {
                    val defaultModels = listOf(
                        DictationModel(
                            id = "whisper_tiny",
                            name = "Whisper Tiny (Multi-Language)",
                            sizeMb = 75f,
                            accuracySpanish = 72,
                            accuracyEnglish = 79,
                            speedMultiplier = 8.5f,
                            isDownloaded = true,
                            isSelected = true
                        ),
                        DictationModel(
                            id = "whisper_base",
                            name = "Whisper Base (Standard)",
                            sizeMb = 140f,
                            accuracySpanish = 83,
                            accuracyEnglish = 89,
                            speedMultiplier = 5.0f,
                            isDownloaded = false,
                            isSelected = false
                        ),
                        DictationModel(
                            id = "whisper_small",
                            name = "Whisper Small (High Precision)",
                            sizeMb = 460f,
                            accuracySpanish = 92,
                            accuracyEnglish = 95,
                            speedMultiplier = 2.5f,
                            isDownloaded = false,
                            isSelected = false
                        ),
                        DictationModel(
                            id = "whisper_medium",
                            name = "Whisper Medium (Ultra Quality)",
                            sizeMb = 1500f,
                            accuracySpanish = 97,
                            accuracyEnglish = 99,
                            speedMultiplier = 1.0f,
                            isDownloaded = false,
                            isSelected = false
                        ),
                        DictationModel(
                            id = "whisper_es_optimized",
                            name = "VozLocal Spanish-Specialized v2",
                            sizeMb = 210f,
                            accuracySpanish = 96,
                            accuracyEnglish = 68,
                            speedMultiplier = 4.2f,
                            isDownloaded = false,
                            isSelected = false
                        )
                    )
                    modelDao.insertModels(defaultModels)
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
                val model = models.find { it.id == modelId } ?: return@launch
                if (model.isDownloaded || model.isDownloading) return@launch

                // Set status to downloading
                modelDao.updateModel(model.copy(isDownloading = true, downloadProgress = 0.01f))

                var progress = 0.05f
                while (progress < 1.0f) {
                    delay(300) // update every 300ms
                    // simulate download speed based on size (larger takes a bit longer)
                    val step = when {
                        model.sizeMb > 1000f -> 0.04f
                        model.sizeMb > 400f -> 0.08f
                        else -> 0.15f
                    }
                    progress += step
                    if (progress > 1.0f) progress = 1.0f
                    val currentModel = allModels.first().find { it.id == modelId } ?: break
                    modelDao.updateModel(currentModel.copy(downloadProgress = progress))
                }

                val finalModel = allModels.first().find { it.id == modelId } ?: return@launch
                modelDao.updateModel(finalModel.copy(
                    isDownloading = false,
                    isDownloaded = true,
                    downloadProgress = 1.0f
                ))
            } catch (e: Exception) {
                e.printStackTrace()
                // Reset on error
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
        // Do not delete preloaded tiny model
        if (modelId == "whisper_tiny") return@withContext

        // If the model is selected, select "whisper_tiny" instead
        if (model.isSelected) {
            modelDao.selectModel("whisper_tiny")
        }

        modelDao.updateModel(model.copy(
            isDownloaded = false,
            downloadProgress = 0.0f,
            isDownloading = false
        ))
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

        // 2. Dictionary Replacements
        if (applyDict) {
            val words = getWordsList()
            for (dictWord in words) {
                // Word boundaries or direct matches, case insensitive
                val regex = Regex("(?i)\\b${Regex.escape(dictWord.word)}\\b")
                result = result.replace(regex, dictWord.replacement)
            }
        }

        // 3. Smart Punctuation & Formatting (Auto-adds pauses correction, periods, commas)
        if (smartPunctuation) {
            // Fix double punctuation, replace pauses markers if they exist
            result = result.replace(Regex("\\s*,\\s*,"), ",")
            result = result.replace(Regex("\\s*\\.\\s*\\."), "...")
            result = result.replace(" (pausa) ", ", ")
            result = result.replace(" (pause) ", ", ")

            // Ensure spacing around punctuation
            result = result.replace(Regex("([,.?!])([^\\s\\d])"), "$1 $2")
        }

        // 4. Auto-Capitalization
        if (autoCapitalize && result.isNotEmpty()) {
            // Capitalize first letter of string
            result = result.substring(0, 1).uppercase() + result.substring(1)

            // Capitalize after periods, question marks, exclamation marks
            val sentences = result.split(Regex("(?<=[.?!])\\s+"))
            result = sentences.joinToString(" ") { sentence ->
                if (sentence.isNotEmpty()) {
                    sentence.substring(0, 1).uppercase() + sentence.substring(1)
                } else {
                    ""
                }
            }
        }

        result
    }
}
