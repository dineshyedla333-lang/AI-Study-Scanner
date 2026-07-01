package com.aistudyscanner.agent.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aistudyscanner.agent.network.PlannerMonth

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlannerScreen(
    onBack: () -> Unit,
    initialBoard: String = "JEE",
    vm: PlannerViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.uiState.collectAsState()

    LaunchedEffect(Unit) {
        if (initialBoard in PLANNER_BOARD_OPTIONS) vm.setBoard(initialBoard)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Study Planner") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
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

            Text(
                text = "Pick your exam, how long you have, and how many hours a " +
                    "day you can study. The AI builds a month-by-month study " +
                    "program with topics and milestones.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Target exam / board
            Text(
                text = "Target exam",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PLANNER_BOARD_OPTIONS.forEach { option ->
                    FilterChip(
                        selected = state.board == option,
                        onClick = { vm.setBoard(option) },
                        label = { Text(option) },
                    )
                }
            }

            // How many months
            Text(
                text = "How many months?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PLANNER_MONTH_OPTIONS.forEach { option ->
                    FilterChip(
                        selected = state.months == option,
                        onClick = { vm.setMonths(option) },
                        label = { Text(if (option == 1) "1 month" else "$option months") },
                    )
                }
            }

            // Hours per day
            Text(
                text = "Study hours per day",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PLANNER_HOURS_OPTIONS.forEach { option ->
                    FilterChip(
                        selected = state.hoursPerDay == option,
                        onClick = { vm.setHours(option) },
                        label = { Text("${option}h") },
                    )
                }
            }

            OutlinedTextField(
                value = state.goal,
                onValueChange = { vm.setGoal(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Goal (optional, e.g. Crack JEE 2027)") },
                singleLine = true,
            )

            state.usage?.let { usage ->
                Text(
                    text = "Free today: ${usage.usedToday}/${usage.limitPerDay} · ${usage.remainingToday} remaining",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = { vm.generate(context) },
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.plan.isEmpty()) "Build my plan" else "Rebuild plan")
            }

            if (state.isLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Building your study plan…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            state.error?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (state.overview.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "${state.board} · ${state.months}-month plan",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = state.overview,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            state.plan.forEach { month ->
                PlannerMonthCard(month = month)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PlannerMonthCard(month: PlannerMonth) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Month ${month.month}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (month.title.isNotBlank()) {
                Text(
                    text = month.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (month.topics.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                month.topics.forEach { topic ->
                    Text(
                        text = "•  $topic",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (month.milestone.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "🎯 Milestone: ${month.milestone}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
