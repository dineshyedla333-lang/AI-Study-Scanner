package com.aistudyscanner.agent.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SolvedQuestionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun solvedQuestionDao(): SolvedQuestionDao

    companion object {
        @Volatile
        private var instance: HistoryDatabase? = null

        fun getInstance(context: Context): HistoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HistoryDatabase::class.java,
                    "history_database",
                )
                    // A phone that ran a newer build than this one has a higher
                    // schema version on disk, and Room refuses to open it at
                    // all — which took down solving, not just history. Losing
                    // saved answers on a downgrade is survivable; being unable
                    // to answer anything is not.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
