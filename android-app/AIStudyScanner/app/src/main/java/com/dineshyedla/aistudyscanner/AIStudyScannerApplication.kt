package com.aistudyscanner.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.aistudyscanner.agent.ads.RewardedAdManager
import com.aistudyscanner.agent.billing.BillingManager
import com.aistudyscanner.agent.billing.ProPrefs
import com.aistudyscanner.agent.messaging.StudyMessagingService
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import io.sentry.android.core.SentryAndroid

class AIStudyScannerApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        createNewsNotificationChannel()

        // The Play target audience starts at 13, and the Families policy requires ads
        // suitable for minors wherever they are treated as children. Without this,
        // AdMob may serve up to mature content. PG keeps decent fill; G is stricter
        // but noticeably thins inventory.
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_PG)
                .build()
        )
        // Pro is sold as ad-free, so a subscriber never even initialises the ads SDK.
        // Skipping it also means no Advertising ID is collected for them, which keeps
        // the paywall's promise honest rather than merely hiding the ad button.
        // Read from ProPrefs: it is synchronous and already populated, whereas the
        // billing handshake has not run yet at this point.
        if (!ProPrefs.isPro(this)) {
            MobileAds.initialize(this) { RewardedAdManager.preload(this) }
        }

        // Connect to Play Billing early so a paying user is never metered while the
        // handshake completes, and so a lapsed subscription is revoked promptly.
        BillingManager.start(this)

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
