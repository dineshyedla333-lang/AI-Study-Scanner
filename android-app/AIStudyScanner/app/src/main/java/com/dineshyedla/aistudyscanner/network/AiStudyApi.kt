package com.dineshyedla.aistudyscanner.network

import retrofit2.http.Body
import retrofit2.http.POST

data class SolveRequest(
    val question: String,
    val mode: Boolean,
)

data class SolveResponse(
    val provider: String,
    val model: String,
    val answer: String,
    val latency_ms: Int,
)

interface AiStudyApi {
    @POST("solve")
    suspend fun solve(@Body body: SolveRequest): SolveResponse
}
