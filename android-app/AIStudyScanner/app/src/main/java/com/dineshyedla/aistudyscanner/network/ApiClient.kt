package com.aistudyscanner.agent.network

import com.aistudyscanner.agent.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/') + "/"

    private val okHttp: OkHttpClient by lazy {
        // Render free tier spins down on inactivity; cold start can take ~50s,
        // and the agentic flow makes 2 sequential AI calls. Use generous timeouts.
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
        }
        builder.build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val api: AiStudyApi by lazy {
        retrofit.create(AiStudyApi::class.java)
    }
}
