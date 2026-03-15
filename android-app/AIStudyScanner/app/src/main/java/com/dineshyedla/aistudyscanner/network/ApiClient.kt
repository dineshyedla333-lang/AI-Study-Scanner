package com.dineshyedla.aistudyscanner.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {
    /**
     * For Android Emulator -> host machine use:
     *   http://10.0.2.2:8000/
     *
     * For real device, replace with your PC IP on same WiFi:
     *   http://192.168.x.x:8000/
     */
    private const val BASE_URL = "http://10.0.2.2:8000/"

    private val okHttp: OkHttpClient by lazy {
        val logger = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        OkHttpClient.Builder()
            .addInterceptor(logger)
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val api: AiStudyApi by lazy {
        retrofit.create(AiStudyApi::class.java)
    }
}
