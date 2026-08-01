package com.aistudyscanner.agent.usage

import android.content.Context
import com.aistudyscanner.agent.BuildConfig
import com.aistudyscanner.agent.billing.ProPrefs
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.ZoneId

data class UsageStatus(
    val usedToday: Int,
    val limitPerDay: Int,
    val consumedInThisCall: Boolean,
    val bonusToday: Int = 0,
    /** Pro subscribers are not metered at all. */
    val isPro: Boolean = false,
) {
    val effectiveLimit: Int get() = limitPerDay + bonusToday
    val remainingToday: Int get() = (effectiveLimit - usedToday).coerceAtLeast(0)
    val isAllowed: Boolean get() = isPro || consumedInThisCall || usedToday < effectiveLimit
    val limitReached: Boolean get() = !isPro && usedToday >= effectiveLimit

    /**
     * Single source for the quota caption. It lives here because the same line was
     * duplicated across four screens and three of them drifted — they divided by
     * limitPerDay instead of effectiveLimit, so anyone who watched a rewarded ad
     * saw a nonsense fraction like "7/2".
     */
    val label: String
        get() = if (isPro) {
            "Pro · unlimited"
        } else {
            "Free today: $usedToday/$effectiveLimit · $remainingToday remaining"
        }
}

/**
 * Firestore schema (suggested):
 * collection: usage_daily
 * document id: "{userId}_{yyyy-MM-dd}"
 *
 * fields:
 *  - userId: string
 *  - day: string (yyyy-MM-dd)
 *  - count: number
 *  - bonus: number (extra quota granted by rewarded ads, on top of limitPerDay)
 *  - updatedAt: server timestamp
 */
class UsageRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val limitPerDay: Int = if (BuildConfig.DEBUG) 2 else 10,
) {
    private fun resolveUserId(context: Context): String {
        val firebaseUser = auth.currentUser
        return firebaseUser?.uid ?: UserIdProvider.getOrCreateAnonymousId(context)
    }

    private fun todayKey(): String {
        val today = LocalDate.now(ZoneId.systemDefault())
        return today.toString() // yyyy-MM-dd
    }

    private fun docId(userId: String, day: String): String = "${userId}_$day"

    /**
     * Atomically increments today's usage counter in Firestore.
     *
     * Returns updated UsageStatus (after increment attempt).
     * If limit is already reached, it DOES NOT increment and returns current status.
     */
    suspend fun tryConsumeOne(context: Context): UsageStatus {
        // Pro subscribers skip metering entirely, so no Firestore round trip and no
        // counter to grow. Read from ProPrefs rather than BillingManager's flow: the
        // cache is populated before the billing connection completes, and Play
        // overwrites it in both directions once it answers.
        if (ProPrefs.isPro(context)) {
            return UsageStatus(
                usedToday = 0,
                limitPerDay = limitPerDay,
                consumedInThisCall = true,
                isPro = true,
            )
        }

        val userId = resolveUserId(context)
        val day = todayKey()
        val docRef = db.collection("usage_daily").document(docId(userId, day))

        return db.runTransaction { txn ->
            val snap = txn.get(docRef)
            val current = (snap.getLong("count") ?: 0L).toInt()
            val bonus = (snap.getLong("bonus") ?: 0L).toInt()
            val effectiveLimit = limitPerDay + bonus

            if (current >= effectiveLimit) {
                return@runTransaction UsageStatus(
                    usedToday = current,
                    limitPerDay = limitPerDay,
                    consumedInThisCall = false,
                    bonusToday = bonus,
                )
            }

            if (!snap.exists()) {
                txn.set(
                    docRef,
                    mapOf(
                        "userId" to userId,
                        "day" to day,
                        "count" to 1,
                        "bonus" to 0,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                )
                return@runTransaction UsageStatus(
                    usedToday = 1,
                    limitPerDay = limitPerDay,
                    consumedInThisCall = true,
                    bonusToday = bonus,
                )
            }

            txn.update(
                docRef,
                mapOf(
                    "count" to FieldValue.increment(1),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            )
            UsageStatus(
                usedToday = current + 1,
                limitPerDay = limitPerDay,
                consumedInThisCall = true,
                bonusToday = bonus,
            )
        }.await()
    }

    /**
     * Gives back a solve that was consumed for a request that then failed.
     * tryConsumeOne debits before the network call, so without this a timeout or
     * a 500 would silently cost the user one of their few free solves.
     */
    suspend fun refundOne(context: Context) {
        val userId = resolveUserId(context)
        val day = todayKey()
        val docRef = db.collection("usage_daily").document(docId(userId, day))

        db.runTransaction<Unit> { txn ->
            val snap = txn.get(docRef)
            val current = (snap.getLong("count") ?: 0L).toInt()
            if (snap.exists() && current > 0) {
                txn.update(
                    docRef,
                    mapOf(
                        "count" to FieldValue.increment(-1),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                )
            }
        }.await()
    }

    /**
     * Grants bonus quota for today (e.g. after a rewarded ad is watched).
     * Adds on top of any existing bonus rather than replacing it.
     */
    suspend fun grantBonus(context: Context, amount: Int = 3): UsageStatus {
        val userId = resolveUserId(context)
        val day = todayKey()
        val docRef = db.collection("usage_daily").document(docId(userId, day))

        return db.runTransaction { txn ->
            val snap = txn.get(docRef)
            val current = (snap.getLong("count") ?: 0L).toInt()
            val newBonus = (snap.getLong("bonus") ?: 0L).toInt() + amount

            if (!snap.exists()) {
                txn.set(
                    docRef,
                    mapOf(
                        "userId" to userId,
                        "day" to day,
                        "count" to 0,
                        "bonus" to newBonus,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                )
            } else {
                txn.update(
                    docRef,
                    mapOf(
                        "bonus" to newBonus,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                )
            }

            UsageStatus(
                usedToday = current,
                limitPerDay = limitPerDay,
                consumedInThisCall = false,
                bonusToday = newBonus,
            )
        }.await()
    }
}
