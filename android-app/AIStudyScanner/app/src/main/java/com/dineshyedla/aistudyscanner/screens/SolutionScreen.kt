package com.aistudyscanner.agent.screens

import android.app.Activity
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aistudyscanner.agent.ads.RewardedAdManager
import com.aistudyscanner.agent.network.AgentStepResponse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolutionScreen(
    onBack: () -> Unit,
    extractedText: String,
    initialExamMode: Boolean = true,
    board: String = "Auto",
    vm: SolutionViewModel = viewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by vm.uiState.collectAsState()

    LaunchedEffect(extractedText) {
        vm.setQuestion(extractedText)
        vm.setExamMode(initialExamMode)
        vm.setExamBoard(board)
        if (extractedText.isNotBlank()) {
            vm.solve(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Agent Solution") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // Editable question text — user can fix OCR mistakes
            Text(
                text = "Question",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = state.extractedText,
                onValueChange = { vm.setQuestion(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tap to edit if scanner made mistakes") },
                minLines = 3,
                maxLines = 8,
            )

            // Usage quota
            state.usage?.let { usage ->
                Text(
                    text = "Free today: ${usage.usedToday}/${usage.effectiveLimit} · ${usage.remainingToday} remaining",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = { vm.solve(context) },
                enabled = !state.isLoading && state.extractedText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Solve with AI Agent")
            }

            // Agent thinking indicator
            if (state.isLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = state.currentAgentStep.ifBlank { "Agent thinking…" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Error
            state.error?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Watch a rewarded ad for bonus quota once the free daily limit is hit
            if (state.usage?.limitReached == true) {
                OutlinedButton(
                    onClick = {
                        val activity = context as? Activity
                        if (activity == null) return@OutlinedButton
                        RewardedAdManager.show(
                            activity = activity,
                            onEarned = { vm.grantAdBonus(context) },
                            onNotReady = {
                                Toast.makeText(
                                    context,
                                    "Ad not ready yet, please try again shortly",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Watch ad for +3 more today")
                }
            }

            // AI-detected info chips (subject / difficulty / board)
            val detected = state.detected
            val chips = listOfNotNull(
                detected.subject.takeIf { it.isNotEmpty() },
                detected.difficulty.takeIf { it.isNotEmpty() },
                detected.examBoard.takeIf { it.isNotEmpty() },
            )
            if (chips.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Detected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(chips) { chip ->
                            SuggestionChip(onClick = {}, label = { Text(chip) })
                        }
                    }
                    if (detected.topic.isNotEmpty()) {
                        Text(
                            text = "Topic: ${detected.topic}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Agent reasoning steps (collapsible)
            if (state.agentSteps.isNotEmpty()) {
                Text(
                    text = "Agent Reasoning",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                state.agentSteps.forEach { step -> AgentStepCard(step = step) }
            }

            // Final answer
            state.answer?.let { ans ->
                Text(
                    text = "Answer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Text(
                        text = ans,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                // Share + Copy buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { vm.shareAnswer(context) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Share")
                    }
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(ans)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Copy")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AgentStepCard(step: AgentStepResponse) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StepBadge(label = step.name)
                    Text(
                        text = "${step.latency_ms} ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Text(
                    text = step.output,
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun StepBadge(label: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.secondary,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondary,
        )
    }
}
