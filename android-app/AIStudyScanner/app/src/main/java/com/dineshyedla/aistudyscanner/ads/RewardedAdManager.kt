package com.aistudyscanner.agent.ads

import android.app.Activity
import android.content.Context
import android.util.Log
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
    private const val AD_UNIT_ID = "ca-app-pub-9538633548349202/2336087512" // daily_quota_bonus

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false

    val isReady: Boolean get() = rewardedAd != null

    fun preload(context: Context) {
        if (rewardedAd != null || isLoading) return
        isLoading = true
        RewardedAd.load(
            context.applicationContext,
            AD_UNIT_ID,
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
