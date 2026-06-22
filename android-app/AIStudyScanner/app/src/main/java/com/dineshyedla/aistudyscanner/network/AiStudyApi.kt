package com.aistudyscanner.agent.network

import retrofit2.http.Body
import retrofit2.http.POST

data class SolveRequest(
    val question_text: String,
    val exam_mode: Boolean,
)

data class SolveResponse(
    val provider: String,
    val model: String,
    val answer: String,
    val latency_ms: Int,
)

data class AgentStepResponse(
    val name: String,
    val output: String,
    val latency_ms: Int,
)

data class AgenticSolveResponse(
    val provider: String,
    val model: String,
    val steps: List<AgentStepResponse>,
    val answer: String,
    val total_latency_ms: Int,
)

interface AiStudyApi {
    @POST("solve")
    suspend fun solve(@Body body: SolveRequest): SolveResponse

    @POST("solve/agent")
    suspend fun agentSolve(@Body body: SolveRequest): AgenticSolveResponse
}
