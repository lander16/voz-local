package com.example.data.local

import androidx.room.*
import com.example.data.model.DictationModel
import com.example.data.model.DictionaryWord
import com.example.data.model.TranscriptionHistory
import com.example.data.model.DictationStat
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM dictation_models")
    fun getAllModels(): Flow<List<DictationModel>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModels(models: List<DictationModel>)

    @Update
    suspend fun updateModel(model: DictationModel)

    @Query("UPDATE dictation_models SET isSelected = (id = :modelId)")
    suspend fun selectModel(modelId: String)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM transcription_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<TranscriptionHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: TranscriptionHistory)

    @Query("DELETE FROM transcription_history WHERE id = :id")
    suspend fun deleteHistoryById(id: Int)

    @Query("DELETE FROM transcription_history")
    suspend fun clearHistory()
}

@Dao
interface DictionaryDao {
    @Query("SELECT * FROM dictionary_words ORDER BY word ASC")
    fun getAllWords(): Flow<List<DictionaryWord>>

    @Query("SELECT * FROM dictionary_words")
    suspend fun getWordsList(): List<DictionaryWord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWord(word: DictionaryWord)

    @Query("DELETE FROM dictionary_words WHERE id = :id")
    suspend fun deleteWordById(id: Int)
}

@Dao
interface StatsDao {
    @Query("SELECT * FROM dictation_stats ORDER BY timestamp DESC")
    fun getAllStats(): Flow<List<DictationStat>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStat(stat: DictationStat)

    @Query("DELETE FROM dictation_stats")
    suspend fun clearStats()
}

