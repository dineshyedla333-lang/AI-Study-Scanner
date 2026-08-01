package com.aistudyscanner.agent.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.aistudyscanner.agent.BuildConfig
import com.aistudyscanner.agent.billing.ProPrefs
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * Loads and shows a rewarded ad that grants bonus daily quota.
 */
object RewardedAdManager {
    private const val TAG = "RewardedAdManager"
    /** Google's always-fill test unit. Never request the live unit from a debug
     *  build — self-served impressions count as invalid traffic and are a common
     *  cause of AdMob account suspension. */
    private const val TEST_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
    private const val LIVE_AD_UNIT_ID = "ca-app-pub-9538633548349202/2336087512" // daily_quota_bonus

    private val adUnitId: String
        get() = if (BuildConfig.DEBUG) TEST_AD_UNIT_ID else LIVE_AD_UNIT_ID

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false

    val isReady: Boolean get() = rewardedAd != null

    fun preload(context: Context) {
        // Pro is sold partly as "no ads", so never fetch one for a subscriber. The
        // paywall claim has to be true even in the places a user cannot see —
        // requesting an ad still touches the Advertising ID.
        if (ProPrefs.isPro(context)) return
        if (rewardedAd != null || isLoading) return
        isLoading = true
        RewardedAd.load(
            context.applicationContext,
            adUnitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    isLoading = false
                    rewardedAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    rewardedAd = null
                    Log.w(TAG, "Rewarded ad failed to load: ${error.message}")
                }
            },
        )
    }

    fun show(activity: Activity, onEarned: () -> Unit, onNotReady: () -> Unit = {}) {
        val ad = rewardedAd
        if (ad == null) {
            onNotReady()
            preload(activity)
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                preload(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                rewardedAd = null
                preload(activity)
            }
        }

        ad.show(activity) { _ -> onEarned() }
    }
}
