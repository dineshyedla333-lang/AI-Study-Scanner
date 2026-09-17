package com.aistudyscanner.agent.network

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

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
    // The question after the server repaired OCR errors; null from older servers.
    @SerializedName("interpreted_question") val interpreted_question: String? = null,
)

/** Result of the server-side math OCR (Mathpix) used for Pro scans. */
data class OcrResponse(
    @SerializedName("provider") val provider: String,
    @SerializedName("text") val text: String,
    @SerializedName("confidence") val confidence: Double,
    @SerializedName("latency_ms") val latency_ms: Int,
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

// ---- AI Planner ----
data class PlannerRequest(
    @SerializedName("board") val board: String,
    @SerializedName("months") val months: Int,
    @SerializedName("hours_per_day") val hours_per_day: Float,
    @SerializedName("goal") val goal: String? = null,
)

data class PlannerMonth(
    @SerializedName("month") val month: Int,
    @SerializedName("title") val title: String,
    @SerializedName("topics") val topics: List<String>,
    @SerializedName("milestone") val milestone: String,
)

data class PlannerResponse(
    @SerializedName("provider") val provider: String,
    @SerializedName("model") val model: String,
    @SerializedName("board") val board: String,
    @SerializedName("months") val months: Int,
    @SerializedName("overview") val overview: String,
    @SerializedName("plan") val plan: List<PlannerMonth>,
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

    @POST("planner")
    suspend fun planner(@Body body: PlannerRequest): PlannerResponse

    @POST("news")
    suspend fun news(@Body body: NewsRequest): NewsResponse

    @POST("news/subscribe")
    suspend fun subscribe(@Body body: SubscribeRequest): SimpleStatus

    @POST("news/unsubscribe")
    suspend fun unsubscribe(@Body body: UnsubscribeRequest): SimpleStatus

    @Multipart
    @POST("ocr")
    suspend fun ocr(@Part image: MultipartBody.Part): OcrResponse
}
