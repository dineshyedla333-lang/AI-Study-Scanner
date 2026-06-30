package com.aistudyscanner.agent.auth

import android.content.Context

/**
 * Locally cached registered-user profile (verified Google identity + the mobile
 * number the user entered at registration). Drives the launch gate: the app
 * shows the login screen until [isRegistered] is true.
 */
object ProfilePrefs {
    private const val PREFS = "ai_study_scanner_profile"
    private const val KEY_UID = "uid"
    private const val KEY_EMAIL = "email"
    private const val KEY_NAME = "name"
    private const val KEY_PHONE = "phone"
    private const val KEY_REGISTERED = "registered"

    fun isRegistered(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REGISTERED, false)

    fun getUid(context: Context): String? = prefs(context).getString(KEY_UID, null)
    fun getEmail(context: Context): String? = prefs(context).getString(KEY_EMAIL, null)
    fun getName(context: Context): String? = prefs(context).getString(KEY_NAME, null)
    fun getPhone(context: Context): String? = prefs(context).getString(KEY_PHONE, null)

    fun save(
        context: Context,
        uid: String,
        email: String?,
        name: String?,
        phone: String,
    ) {
        prefs(context).edit()
            .putString(KEY_UID, uid)
            .putString(KEY_EMAIL, email)
            .putString(KEY_NAME, name)
            .putString(KEY_PHONE, phone)
            .putBoolean(KEY_REGISTERED, true)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
