package com.aistudyscanner.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.aistudyscanner.agent.ads.RewardedAdManager
import com.aistudyscanner.agent.auth.AuthManager
import com.aistudyscanner.agent.billing.BillingManager
import com.aistudyscanner.agent.billing.ProPrefs
import com.aistudyscanner.agent.messaging.StudyMessagingService
import com.aistudyscanner.agent.network.ApiClient
import com.aistudyscanner.agent.network.ServerWarmup
import com.aistudyscanner.agent.usage.UserIdProvider
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import io.sentry.android.core.SentryAndroid

class AIStudyScannerApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        createNewsNotificationChannel()

        // Identify this install to the backend so rate limits are per device, not per
        // IP (carrier NAT would otherwise let one user's traffic throttle another's).
        ApiClient.deviceId = UserIdProvider.getOrCreateAnonymousId(this)
        ApiClient.purchaseToken = { ProPrefs.purchaseToken(this) }

        // Silent anonymous Firebase sign-in: no screen, no email. It gives the
        // backend a verifiable uid to meter, while a new user can scan at once.
        AuthManager.startAnonymousSignIn()

        // Start waking a sleeping Render instance now, in the background, so it is
        // usually up by the time the student has scanned and taps Solve.
        ServerWarmup.prewarm()

        // The Play target audience starts at 13, and the Families policy requires ads
        // suitable for minors wherever they are treated as children. Without this,
        // AdMob may serve up to mature content. PG keeps decent fill; G is stricter
        // but noticeably thins inventory.
        //
        // setMaxAdContentRating governs what the ad may SHOW; it does nothing about
        // how the viewer is TARGETED. Every user of this app is a school student, so
        // under India's DPDP Act 2023 every user is a child (under 18, not under 13)
        // and s.9(2) bans advertising targeted at children. Tagging the whole app as
        // under the age of consent forces non-personalised ads for everyone, which is
        // what that section actually requires. It costs eCPM; at this install count
        // that is a rounding error next to shipping targeted ads at minors.
        //
        // Deliberately NOT setTagForChildDirectedTreatment: that is COPPA's under-13
        // regime, and it would also suppress the rewarded ads the free quota depends on.
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_PG)
                .setTagForUnderAgeOfConsent(
                    RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_TRUE
                )
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
