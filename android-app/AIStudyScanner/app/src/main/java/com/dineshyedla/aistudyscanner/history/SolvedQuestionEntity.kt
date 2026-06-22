package com.aistudyscanner.agent.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "solved_questions")
data class SolvedQuestionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val questionText: String,
    val answerText: String,
    val examMode: Boolean,
    val createdAt: Long,
)