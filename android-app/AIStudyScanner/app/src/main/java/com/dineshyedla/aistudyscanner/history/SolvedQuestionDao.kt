package com.aistudyscanner.agent.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SolvedQuestionDao {
    @Query("SELECT * FROM solved_questions ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<SolvedQuestionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SolvedQuestionEntity)

    @Query("DELETE FROM solved_questions")
    suspend fun clearAll()
}