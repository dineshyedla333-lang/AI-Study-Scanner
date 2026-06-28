package com.aistudyscanner.agent.messaging

import android.content.Context

/** Local store of the user's UPSC Live Agent subscription, so the messaging
 *  service can re-register with the backend when the FCM token rotates. */
object NewsAgentPrefs {
    private const val PREFS = "upsc_live_agent_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TIMES = "times" // csv e.g. "08:00,18:00"
    private const val KEY_EXAM = "exam"
    private const val KEY_COUNT = "count"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun getTimes(context: Context): List<String> =
        prefs(context).getString(KEY_TIMES, "")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun getExam(context: Context): String =
        prefs(context).getString(KEY_EXAM, "UPSC") ?: "UPSC"

    fun getCount(context: Context): Int =
        prefs(context).getInt(KEY_COUNT, 5)

    fun save(
        context: Context,
        enabled: Boolean,
        times: List<String>,
        exam: String,
        count: Int,
    ) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_TIMES, times.joinToString(","))
            .putString(KEY_EXAM, exam)
            .putInt(KEY_COUNT, count)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
