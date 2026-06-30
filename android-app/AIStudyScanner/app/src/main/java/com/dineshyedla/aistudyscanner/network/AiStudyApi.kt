package com.aistudyscanner.agent.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.POST

data class SolveRequest(
    @SerializedName("question_text") val question_text: String,
    @SerializedName("exam_mode") val exam_mode: Boolean,
    // Auto / CBSE / JEE / NEET / EAMCET — "Auto" lets the agent detect it.
    @SerializedName("board") val board: String = "Auto",
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

data class HomeworkRequest(
    @SerializedName("topic") val topic: String,
    @SerializedName("count") val count: Int,
    @SerializedName("exam_mode") val exam_mode: Boolean,
    @SerializedName("board") val board: String = "Auto",
)

data class HomeworkItem(
    @SerializedName("question") val question: String,
    @SerializedName("answer") val answer: String,
)

data class HomeworkResponse(
    @SerializedName("provider") val provider: String,
    @SerializedName("model") val model: String,
    @SerializedName("topic") val topic: String,
    @SerializedName("questions") val questions: List<HomeworkItem>,
    @SerializedName("latency_ms") val latency_ms: Int,
)

// ---- UPSC Live Agent ----
data class NewsRequest(
    @SerializedName("exam") val exam: String = "UPSC",
    @SerializedName("count") val count: Int = 5,
)

data class NewsResponse(
    @SerializedName("provider") val provider: String,
    @SerializedName("model") val model: String,
    @SerializedName("exam") val exam: String,
    @SerializedName("headlines_used") val headlines_used: Int,
    @SerializedName("questions") val questions: List<HomeworkItem>,
    @SerializedName("latency_ms") val latency_ms: Int,
)

data class SubscribeRequest(
    @SerializedName("token") val token: String,
    @SerializedName("user_id") val user_id: String? = null,
    @SerializedName("email") val email: String? = null,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("id_token") val id_token: String? = null,
    @SerializedName("exam") val exam: String = "UPSC",
    @SerializedName("times") val times: List<String>,
    @SerializedName("tz") val tz: String,
    @SerializedName("count") val count: Int = 5,
    @SerializedName("enabled") val enabled: Boolean = true,
)

data class UnsubscribeRequest(
    @SerializedName("token") val token: String,
)

data class SimpleStatus(
    @SerializedName("status") val status: String,
    @SerializedName("detail") val detail: String? = null,
)

interface AiStudyApi {
    @POST("solve")
    suspend fun solve(@Body body: SolveRequest): SolveResponse

    @POST("solve/agent")
    suspend fun agentSolve(@Body body: SolveRequest): AgenticSolveResponse

    @POST("homework")
    suspend fun homework(@Body body: HomeworkRequest): HomeworkResponse

    @POST("news")
    suspend fun news(@Body body: NewsRequest): NewsResponse

    @POST("news/subscribe")
    suspend fun subscribe(@Body body: SubscribeRequest): SimpleStatus

    @POST("news/unsubscribe")
    suspend fun unsubscribe(@Body body: UnsubscribeRequest): SimpleStatus
}
