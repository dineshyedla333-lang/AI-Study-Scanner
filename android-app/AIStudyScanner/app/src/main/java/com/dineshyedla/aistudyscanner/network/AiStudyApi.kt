package com.aistudyscanner.agent.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.POST

data class SolveRequest(
    @SerializedName("question_text") val question_text: String,
    @SerializedName("exam_mode") val exam_mode: Boolean,
)

data class SolveResponse(
    @SerializedName("provider") val provider: String,
    @SerializedName("model") val model: String,
    @SerializedName("answer") val answer: String,
    @SerializedName("latency_ms") val latency_ms: Int,
)

data class AgentStepResponse(
    @SerializedName("name") val name: String,
    @SerializedName("output") val output: String,
    @SerializedName("latency_ms") val latency_ms: Int,
)

data class AgenticSolveResponse(
    @SerializedName("provider") val provider: String,
    @SerializedName("model") val model: String,
    @SerializedName("steps") val steps: List<AgentStepResponse>,
    @SerializedName("answer") val answer: String,
    @SerializedName("total_latency_ms") val total_latency_ms: Int,
)

interface AiStudyApi {
    @POST("solve")
    suspend fun solve(@Body body: SolveRequest): SolveResponse

    @POST("solve/agent")
    suspend fun agentSolve(@Body body: SolveRequest): AgenticSolveResponse
}
