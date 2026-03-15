package com.dineshyedla.aistudyscanner.usage

import android.content.Context
import java.util.UUID

object UserIdProvider {
    private const val PREFS = "ai_study_scanner_prefs"
    private const val KEY_ANON_ID = "anon_user_id"

    /**
     * Stable per-install anonymous identifier (fallback when Firebase Auth user is not available).
     */
    fun getOrCreateAnonymousId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_ANON_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val newId = "anon_${UUID.randomUUID()}"
        prefs.edit().putString(KEY_ANON_ID, newId).apply()
        return newId
    }
}
