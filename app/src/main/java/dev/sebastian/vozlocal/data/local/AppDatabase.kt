package dev.sebastian.vozlocal.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.sebastian.vozlocal.data.model.DictationModel
import dev.sebastian.vozlocal.data.model.DictionaryWord
import dev.sebastian.vozlocal.data.model.TranscriptionHistory
import dev.sebastian.vozlocal.data.model.DictationStat

@Database(
    entities = [DictationModel::class, TranscriptionHistory::class, DictionaryWord::class, DictationStat::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
    abstract fun historyDao(): HistoryDao
    abstract fun dictionaryDao(): DictionaryDao
    abstract fun statsDao(): StatsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vozlocal_database"
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
