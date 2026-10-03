package com.aistudyscanner.agent.network

import com.aistudyscanner.agent.BuildConfig
import com.aistudyscanner.agent.auth.AuthManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    /**
     * Stable per-install id, sent as X-Device-Id so the backend can rate-limit per
     * device instead of per IP. Indian carriers NAT thousands of users behind one
     * address, so IP-keyed limits make real students throttle each other.
     * Set once from [AIStudyScannerApplication.onCreate].
     */
    @Volatile
    var deviceId: String? = null

    /** Active Pro purchase token, sent so the server can verify Pro with Play. */
    @Volatile
    var purchaseToken: () -> String? = { null }

    private fun withAuth(request: Request, idToken: String?): Request {
        val b = request.newBuilder()
        if (idToken != null) b.header("Authorization", "Bearer $idToken")
        purchaseToken()?.let { b.header("X-Play-Purchase-Token", it) }
        return b.build()
    }

    internal val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/') + "/"

    private val okHttp: OkHttpClient by lazy {
        // Render free tier spins down on inactivity; cold start can take ~50s,
        // and the agentic flow makes 2 sequential AI calls. Use generous timeouts.
        // callTimeout spans the whole call including the warm-up wait below, so it is
        // the 150s request budget plus ServerWarmup's bounded worst case: a hard cap
        // that guarantees no request hangs forever.
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150 + ServerWarmup.MAX_WAIT_S, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                // Wait for a sleeping Render instance before sending the real request.
                ServerWarmup.awaitAwake(chain.call())
                val response = chain.proceed(chain.request())
                if (response.code < 500) ServerWarmup.markWarm()
                response
            }
            .addInterceptor { chain ->
                // Firebase ID token (anonymous until the user registers) so the
                // backend can verify who is calling and meter their quota.
                val token = AuthManager.idTokenBlocking()
                val response = chain.proceed(withAuth(chain.request(), token))
                if (response.code != 401 || token == null) return@addInterceptor response
                // Expired or revoked: retry once with a freshly minted token.
                val fresh = AuthManager.idTokenBlocking(forceRefresh = true)
                    ?: return@addInterceptor response
                response.close()
                chain.proceed(withAuth(chain.request(), fresh))
            }
            .addInterceptor { chain ->
                val id = deviceId
                val req = if (id.isNullOrBlank()) {
                    chain.request()
                } else {
                    chain.request().newBuilder().header("X-Device-Id", id).build()
                }
                chain.proceed(req)
            }
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
