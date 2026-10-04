package com.aistudyscanner.agent.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudyscanner.agent.billing.BillingManager
import com.aistudyscanner.agent.i18n.LanguagePrefs
import com.aistudyscanner.agent.usage.StreakPrefs

// Distinct, theme-friendly colors for the four main actions (white text on each).
private val ScanColor = Color(0xFF6750A4) // brand purple
private val UploadColor = Color(0xFF1565C0) // blue
private val HomeworkColor = Color(0xFF2E7D32) // green
private val PlannerColor = Color(0xFF00838F) // teal (study planner)
private val NewsColor = Color(0xFFC2185B) // rose/crimson (current affairs)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onScanQuestion: (board: String) -> Unit,
    onUploadScreenshot: (board: String) -> Unit,
    onHomework: (board: String) -> Unit,
    onNewsAgent: () -> Unit,
    onPlanner: (board: String) -> Unit,
    onProfile: () -> Unit,
    onUpgrade: () -> Unit,
    onExplainPage: () -> Unit,
    onHistory: () -> Unit,
) {
    val context = LocalContext.current
    var board by remember { mutableStateOf(BOARD_OPTIONS.first()) }
    // Read once and written straight back to prefs, so the choice survives the
    // app being closed — a student picks their language once, not every day.
    var language by remember { mutableStateOf(LanguagePrefs.get(context)) }
    // Read, never written here: the streak is earned by solving, not by
    // opening the app, so this screen only displays it.
    var streak by remember { mutableStateOf(StreakPrefs.get(context)) }
    LaunchedEffect(Unit) { streak = StreakPrefs.get(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Study Scan Agent") },
                actions = {
                    IconButton(onClick = onProfile) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = "Profile & Settings",
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Scan a question and learn how to solve it, step by step.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Daily streak. Shown even at zero, because "start your streak" is
            // the nudge; a counter that only appears once you already have one
            // cannot persuade anybody to begin.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (streak.activeToday)
                        MaterialTheme.colorScheme.tertiaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = streak.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (streak.best > 1) {
                        Text(
                            text = "Best so far: ${streak.best} days",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Language picker — first thing on the screen, because explaining
            // in the student's own language is the reason to use this app.
            LanguageSelector(
                language = language,
                onLanguageChange = {
                    language = it
                    LanguagePrefs.set(context, it)
                },
            )

            // Exam board selector
            BoardSelector(
                board = board,
                onBoardChange = { board = it },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Button(
                onClick = { onScanQuestion(board) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ScanColor,
                    contentColor = Color.White,
                ),
            ) {
                Text("Scan & Understand")
            }

            Button(
                onClick = { onUploadScreenshot(board) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = UploadColor,
                    contentColor = Color.White,
                ),
            ) {
                Text("Upload from Gallery")
            }

            // Home Work — generate practice questions to solve yourself
            Button(
                onClick = { onHomework(board) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HomeworkColor,
                    contentColor = Color.White,
                ),
            ) {
                Text("Home Work — Practice Questions")
            }

            // AI Study Planner — month-by-month program for the chosen exam
            Button(
                onClick = { onPlanner(board) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PlannerColor,
                    contentColor = Color.White,
                ),
            ) {
                Text("AI Study Planner — Month-by-Month Plan")
            }

            // UPSC Live Agent — daily current-affairs push
            Button(
                onClick = onNewsAgent,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NewsColor,
                    contentColor = Color.White,
                ),
            ) {
                Text("UPSC Live Agent — Daily Current Affairs")
            }

            // Pro upsell. Hidden once subscribed — nothing is more irritating than an
            // app still selling you what you already pay for.
            val isPro by BillingManager.isPro.collectAsState()
            if (!isPro) {
                OutlinedButton(
                    onClick = onUpgrade,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Text("Go Pro — remove ads, unlimited solves")
                }
            }

            OutlinedButton(
                onClick = onHistory,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
            ) {
                Text("History")
            }

            OutlinedButton(
                onClick = onExplainPage,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
            ) {
                Text("How to Use")
            }
        }
    }
}
