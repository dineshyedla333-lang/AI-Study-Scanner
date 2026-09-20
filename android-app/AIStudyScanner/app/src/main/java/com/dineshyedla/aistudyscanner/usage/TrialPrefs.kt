package com.aistudyscanner.agent.usage

import android.content.Context

/**
 * Counts the solves a brand-new user gets before they have to register.
 *
 * The app used to show the login screen first, so someone arriving from an ad or a
 * video had to hand over a Google account and a phone number before they had seen
 * a single answer. Most simply uninstalled. Now they get [FREE_SOLVES] real solves
 * first and the ask lands after the app has proved itself.
 */
object TrialPrefs {
    const val FREE_SOLVES = 3

    private const val PREFS = "ai_study_scanner_trial"
    private const val KEY_USED = "free_solves_used"

    fun used(context: Context): Int = prefs(context).getInt(KEY_USED, 0)

    fun remaining(context: Context): Int = (FREE_SOLVES - used(context)).coerceAtLeast(0)

    /** True once the trial is spent; the caller should send the user to registration. */
    fun exhausted(context: Context): Boolean = used(context) >= FREE_SOLVES

    /** Count one solve. Call only after a solve actually starts, not on a failed OCR. */
    fun record(context: Context) {
        prefs(context).edit().putInt(KEY_USED, used(context) + 1).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
