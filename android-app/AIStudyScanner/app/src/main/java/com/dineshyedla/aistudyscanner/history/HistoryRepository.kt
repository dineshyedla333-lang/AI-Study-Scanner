package com.aistudyscanner.agent.history

import android.content.Context
import kotlinx.coroutines.flow.Flow

class HistoryRepository private constructor(context: Context) {
    private val dao = HistoryDatabase.getInstance(context).solvedQuestionDao()

    fun observeHistory(): Flow<List<SolvedQuestionEntity>> = dao.observeAll()

    suspend fun saveSolvedQuestion(
        questionText: String,
        answerText: String,
        examMode: Boolean,
    ) {
        dao.insert(
            SolvedQuestionEntity(
                questionText = questionText,
                answerText = answerText,
                examMode = examMode,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun clearHistory() {
        dao.clearAll()
    }

    companion object {
        @Volatile
        private var INSTANCE: HistoryRepository? = null

        fun getInstance(context: Context): HistoryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: HistoryRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
