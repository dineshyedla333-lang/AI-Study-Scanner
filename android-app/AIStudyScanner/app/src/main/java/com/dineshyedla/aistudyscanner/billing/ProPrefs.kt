package com.aistudyscanner.agent.billing

import android.content.Context

/**
 * Local cache of the Pro entitlement.
 *
 * Purpose is responsiveness, not security: it means a paying user isn't blocked
 * behind the billing handshake on a cold start. [BillingManager.queryPurchases]
 * overwrites it in both directions once Play answers, so a cancelled or expired
 * subscription loses access rather than sticking around.
 */
object ProPrefs {
    private const val FILE = "pro_prefs"
    private const val KEY_IS_PRO = "is_pro"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isPro(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_PRO, false)

    fun setPro(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_IS_PRO, value).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_IS_PRO).apply()
    }
}
