package com.aistudyscanner.agent.usage

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class Streak(
    val current: Int = 0,
    val best: Int = 0,
    /** True when today already counts, so the home card can say so. */
    val activeToday: Boolean = false,
) {
    val label: String
        get() = when {
            current <= 0 -> "Solve one question to start your streak"
            activeToday && current == 1 -> "🔥 Day 1 — come back tomorrow"
            activeToday -> "🔥 $current day streak"
            current == 1 -> "🔥 1 day streak — solve one today to keep it"
            else -> "🔥 $current day streak — solve one today to keep it"
        }
}

/**
 * A daily "did you study" counter.
 *
 * Deliberately local-first: the streak must survive a flaky connection and an
 * unregistered user, and the number a student sees should never flicker
 * because Firestore was slow. The copy to the user's profile is best effort.
 *
 * Days are counted in India time, not the device's, so a student travelling or
 * with a mis-set clock does not lose a streak they earned.
 */
object StreakPrefs {
    private const val PREFS = "ai_study_scanner_streak"
    private const val KEY_LAST_DAY = "last_active_day"
    private const val KEY_CURRENT = "current_streak"
    private const val KEY_BEST = "best_streak"

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun today(): LocalDate = LocalDate.now(zone)

    fun get(context: Context): Streak {
        val p = prefs(context)
        val last = p.getString(KEY_LAST_DAY, null)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
        val stored = p.getInt(KEY_CURRENT, 0)
        val best = p.getInt(KEY_BEST, 0)
        if (last == null) return Streak(0, best, false)

        val gap = ChronoUnit.DAYS.between(last, today())
        return when {
            gap == 0L -> Streak(stored, best, activeToday = true)
            // Yesterday: the streak is still alive but today is unclaimed.
            gap == 1L -> Streak(stored, best, activeToday = false)
            // Two or more days missed — the streak is gone, the best stays.
            else -> Streak(0, best, activeToday = false)
        }
    }

    /**
     * Counts today. Call it when the student actually did something — a solved
     * question or a finished practice set — never on app open, or the streak
     * stops meaning anything.
     */
    fun recordActivity(context: Context): Streak {
        val p = prefs(context)
        val today = today()
        val last = p.getString(KEY_LAST_DAY, null)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
        val stored = p.getInt(KEY_CURRENT, 0)
        val best = p.getInt(KEY_BEST, 0)

        if (last == today && stored > 0) {
            return Streak(stored, best, activeToday = true)
        }

        val gap = last?.let { ChronoUnit.DAYS.between(it, today) }
        val current = if (gap == 1L) stored + 1 else 1
        val newBest = maxOf(best, current)

        p.edit()
            .putString(KEY_LAST_DAY, today.toString())
            .putInt(KEY_CURRENT, current)
            .putInt(KEY_BEST, newBest)
            .apply()

        syncToProfile(current, newBest, today.toString())
        return Streak(current, newBest, activeToday = true)
    }

    /**
     * Mirrors the streak onto the user's profile document. Best effort and
     * fire-and-forget: the device copy above is the one the UI trusts.
     */
    private fun syncToProfile(current: Int, best: Int, day: String) {
        val uid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }
            .getOrNull() ?: return
        runCatching {
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .set(
                    mapOf(
                        "streakCurrent" to current,
                        "streakBest" to best,
                        "streakLastDay" to day,
                    ),
                    SetOptions.merge(),
                )
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
