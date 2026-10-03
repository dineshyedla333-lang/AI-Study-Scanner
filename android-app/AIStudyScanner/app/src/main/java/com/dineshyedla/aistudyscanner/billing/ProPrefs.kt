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
    private const val KEY_PURCHASE_TOKEN = "purchase_token"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isPro(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_PRO, false)

    fun setPro(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_IS_PRO, value).apply()
    }

    /** Play purchase token of the active Pro subscription; the backend verifies it
     *  with Google Play before lifting the free limits. */
    fun purchaseToken(context: Context): String? =
        prefs(context).getString(KEY_PURCHASE_TOKEN, null)

    fun setPurchaseToken(context: Context, token: String?) {
        prefs(context).edit().putString(KEY_PURCHASE_TOKEN, token).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_IS_PRO).remove(KEY_PURCHASE_TOKEN).apply()
    }
}
