package com.aistudyscanner.agent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.aistudyscanner.agent.billing.BillingManager
import com.aistudyscanner.agent.tts.SpeechPlayer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 16 (targetSdk 36) enforces edge-to-edge and ignores any opt-out,
        // so opt in explicitly to get the same layout on every API level.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    AIStudyScannerApp()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Play's recommended hook. A subscription bought or cancelled in the Play
        // Store app, or a pending payment that completed while we were in the
        // background, only reaches the app through a fresh query.
        BillingManager.queryPurchases()
    }

    override fun onStop() {
        super.onStop()
        // Nothing is more startling than an answer still being read aloud
        // after you have left the app.
        SpeechPlayer.stop()
    }

    override fun onDestroy() {
        // The engine is process-wide, so release it only when the activity is
        // actually going away and not merely rotating.
        if (isFinishing) SpeechPlayer.shutdown()
        super.onDestroy()
    }
}
