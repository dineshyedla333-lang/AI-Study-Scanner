package com.dineshyedla.aistudyscanner

import android.app.Application
import io.sentry.android.core.SentryAndroid

class AIStudyScannerApplication : Application() {
    override fun onCreate() {
        super.onCreate()

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
}
