package com.aistudyscanner.agent.messaging

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aistudyscanner.agent.MainActivity
import com.aistudyscanner.agent.R
import com.aistudyscanner.agent.auth.ProfilePrefs
import com.aistudyscanner.agent.network.ApiClient
import com.aistudyscanner.agent.network.SubscribeRequest
import com.aistudyscanner.agent.usage.UserIdProvider
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import java.util.TimeZone

class StudyMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val notif = message.notification
        val exam = message.data["exam"] ?: "UPSC"
        val title = notif?.title ?: "$exam Current Affairs"
        val body = notif?.body
            ?: message.data["qna"]?.let { firstQuestionOf(it) }
            ?: "Tap to open today's questions."
        showNotification(title, body)
    }

    override fun onNewToken(token: String) {
        // Re-register with the backend if the user has the agent enabled, so a
        // rotated token keeps receiving pushes.
        if (!NewsAgentPrefs.isEnabled(this)) return
        val times = NewsAgentPrefs.getTimes(this)
        if (times.isEmpty()) return
        try {
            runBlocking {
                ApiClient.api.subscribe(
                    SubscribeRequest(
                        token = token,
                        user_id = ProfilePrefs.getUid(this@StudyMessagingService)
                            ?: UserIdProvider.getOrCreateAnonymousId(
                                this@StudyMessagingService
                            ),
                        email = ProfilePrefs.getEmail(this@StudyMessagingService),
                        phone = ProfilePrefs.getPhone(this@StudyMessagingService),
                        exam = NewsAgentPrefs.getExam(this@StudyMessagingService),
                        times = times,
                        tz = TimeZone.getDefault().id,
                        count = NewsAgentPrefs.getCount(this@StudyMessagingService),
                        enabled = true,
                    )
                )
            }
        } catch (_: Exception) {
            // Best effort; the app re-registers on next open as well.
        }
    }

    private fun firstQuestionOf(qnaJson: String): String {
        return try {
            val arr = JSONArray(qnaJson)
            if (arr.length() > 0) {
                arr.getJSONObject(0).optString("question", "Open today's questions.")
            } else {
                "Open today's questions."
            }
        } catch (e: Exception) {
            "Open today's questions."
        }
    }

    private fun showNotification(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_news)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)

        NotificationManagerCompat.from(this).notify(NOTIF_ID, builder.build())
    }

    companion object {
        const val CHANNEL_ID = "upsc_live_agent"
        private const val NOTIF_ID = 2026
    }
}
