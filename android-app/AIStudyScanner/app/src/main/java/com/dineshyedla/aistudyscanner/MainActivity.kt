package com.aistudyscanner.agent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface

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
}
