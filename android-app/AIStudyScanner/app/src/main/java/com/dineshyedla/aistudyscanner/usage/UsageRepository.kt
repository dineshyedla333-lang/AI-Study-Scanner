package com.dineshyedla.aistudyscanner.usage

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.ZoneId

data class UsageStatus(
    val usedToday: Int,
    val limitPerDay: Int,
) {
    val remainingToday: Int get() = (limitPerDay - usedToday).coerceAtLeast(0)
    val isAllowed: Boolean get() = usedToday < limitPerDay
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
 *  - updatedAt: server timestamp
 */
class UsageRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val limitPerDay: Int = 10,
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
     * Returns updated UsageStatus (after increment).
     * If limit is already reached, it DOES NOT increment and returns current status.
     */
    suspend fun tryConsumeOne(context: Context): UsageStatus {
        val userId = resolveUserId(context)
        val day = todayKey()
        val docRef = db.collection("usage_daily").document(docId(userId, day))

        return db.runTransaction { txn ->
            val snap = txn.get(docRef)
            val current = (snap.getLong("count") ?: 0L).toInt()

            if (current >= limitPerDay) {
                return@runTransaction UsageStatus(
                    usedToday = current,
                    limitPerDay = limitPerDay,
                )
            }

            if (!snap.exists()) {
                txn.set(
                    docRef,
                    mapOf(
                        "userId" to userId,
                        "day" to day,
                        "count" to 1,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                )
                return@runTransaction UsageStatus(usedToday = 1, limitPerDay = limitPerDay)
            }

            txn.update(
                docRef,
                mapOf(
                    "count" to FieldValue.increment(1),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            )
            UsageStatus(usedToday = current + 1, limitPerDay = limitPerDay)
        }.await()
    }
}
