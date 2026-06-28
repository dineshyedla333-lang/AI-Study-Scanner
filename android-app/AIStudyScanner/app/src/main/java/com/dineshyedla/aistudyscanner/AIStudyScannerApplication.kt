package com.aistudyscanner.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.aistudyscanner.agent.messaging.StudyMessagingService
import io.sentry.android.core.SentryAndroid

class AIStudyScannerApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        createNewsNotificationChannel()

        // Sentry (enabled only if DSN is provided via BuildConfig.SENTRY_DSN)
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isNotBlank()) {
            SentryAndroid.init(this) { options ->
                options.dsn = dsn
                options.environment = BuildConfig.BUILD_TYPE
                // Conservative default; can be increased later:
                options.tracesSampleRate = 0.0
            }
        }
    }

    private fun createNewsNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            StudyMessagingService.CHANNEL_ID,
            "UPSC Live Agent",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Daily current-affairs questions for UPSC aspirants"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }
}
