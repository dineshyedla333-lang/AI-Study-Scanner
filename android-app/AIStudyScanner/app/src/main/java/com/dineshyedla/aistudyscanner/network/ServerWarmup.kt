package com.aistudyscanner.agent.network

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Thrown when the backend did not wake up within every warm-up attempt. */
class ServerTooSlowException : IOException(ServerWarmup.TOO_SLOW_MESSAGE)

/**
 * Render's free tier sleeps after ~15 min idle and takes ~50s to wake. Rather than
 * letting a solve sit on a sleeping server (or retrying a costly AI call), every API
 * request first waits here until GET /health answers, probing with growing timeouts.
 * Only the cheap health probe is retried; the real request is sent once, to a server
 * known to be awake.
 */
object ServerWarmup {
    const val TOO_SLOW_MESSAGE = "Server is taking too long. Please try again in a moment."

    /** Per-attempt health probe timeouts; ~2 min in total covers a cold start. */
    private val ATTEMPT_TIMEOUTS_S = longArrayOf(20, 40, 60)

    /** Worst-case time [awaitAwake] can block, for sizing the API call timeout. */
    val MAX_WAIT_S: Long = ATTEMPT_TIMEOUTS_S.sum() + ATTEMPT_TIMEOUTS_S.size * 3

    /**
     * Trust a success for well under Render's ~15 min idle spin-down.
     *
     * Once this window lapses, the next solve pays for a full /health round trip
     * BEFORE its own request goes out — two serial calls for one answer, which is
     * a large part of why solving felt slow. An external cron now pings /health
     * every 10 minutes, so the instance does not actually sleep; 10 minutes still
     * leaves a 5-minute margin against the spin-down if that cron ever stops again.
     */
    private const val WARM_FOR_MS = 10 * 60_000L

    /** Warm servers answer /health in well under this, so the banner never flickers. */
    private const val BANNER_DELAY_MS = 1_500L

    private val _warmingUp = MutableStateFlow(false)

    /** True while a user-visible request is waiting for the server to wake. */
    val warmingUp: StateFlow<Boolean> = _warmingUp.asStateFlow()

    @Volatile
    private var warmUntil = 0L
    private val wakeLock = Any()
    private val bannerLock = Any()
    private val waiters = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val probe: OkHttpClient by lazy { OkHttpClient() }

    private val baseUrl: String
        get() = ApiClient.baseUrl

    fun markWarm() {
        warmUntil = SystemClock.elapsedRealtime() + WARM_FOR_MS
    }

    private fun isWarm() = SystemClock.elapsedRealtime() < warmUntil

    /** Fire-and-forget wake-up at app launch, so the server is up by the first solve. */
    fun prewarm() {
        if (isWarm()) return
        scope.launch { runCatching { wake(call = null) } }
    }

    /**
     * Blocks the calling OkHttp thread until the server answers /health, showing the
     * warming-up banner if that takes noticeably long.
     */
    fun awaitAwake(call: Call) {
        if (isWarm()) return
        waiters.incrementAndGet()
        val banner = scope.launch {
            delay(BANNER_DELAY_MS)
            synchronized(bannerLock) { if (waiters.get() > 0) _warmingUp.value = true }
        }
        try {
            wake(call)
        } finally {
            banner.cancel()
            synchronized(bannerLock) {
                if (waiters.decrementAndGet() == 0) _warmingUp.value = false
            }
        }
    }

    private fun wake(call: Call?) {
        // One probe at a time: a request arriving during the launch prewarm waits for
        // it rather than starting a second wake-up.
        synchronized(wakeLock) {
            if (isWarm()) return
            val request = Request.Builder().url(baseUrl + "health").get().build()
            for (seconds in ATTEMPT_TIMEOUTS_S) {
                if (call?.isCanceled() == true) throw IOException("Canceled")
                val client = probe.newBuilder()
                    .callTimeout(seconds, TimeUnit.SECONDS)
                    .readTimeout(seconds, TimeUnit.SECONDS)
                    .build()
                try {
                    val ok = client.newCall(request).execute().use { it.isSuccessful }
                    if (ok) {
                        markWarm()
                        return
                    }
                    // Render's proxy can answer 502/503 while the instance boots.
                    Thread.sleep(3_000)
                } catch (e: InterruptedIOException) {
                    // Timed out: the server is still waking. Try again, waiting longer.
                }
                // Any other IOException (offline, DNS, TLS) propagates unchanged so the
                // screens keep showing their "check your connection" message.
            }
            throw ServerTooSlowException()
        }
    }
}
