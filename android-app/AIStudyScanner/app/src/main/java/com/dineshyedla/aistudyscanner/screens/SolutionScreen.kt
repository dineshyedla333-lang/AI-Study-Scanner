package com.dineshyedla.aistudyscanner.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolutionScreen(
    onBack: () -> Unit,
    extractedText: String,
    vm: SolutionViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.uiState.collectAsState()

    LaunchedEffect(extractedText) {
        vm.setQuestion(extractedText)
        if (extractedText.isNotBlank()) {
            vm.solve(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Solution") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Extracted Text",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Text(
                text = if (state.extractedText.isBlank()) "(No text extracted yet)" else state.extractedText,
            )

            state.usage?.let { usage ->
                Text(
                    text = "Free today: ${usage.usedToday}/${usage.limitPerDay} (remaining ${usage.remainingToday})",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = { vm.solve(context) },
                enabled = !state.isLoading && state.extractedText.isNotBlank(),
            ) {
                Text("Solve")
            }

            if (state.isLoading) {
                CircularProgressIndicator()
                Text("Solving...")
            }

            state.error?.let { err ->
                Text(
                    text = "Error: $err",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.answer?.let { ans ->
                Text(
                    text = "Answer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = ans)
            }
        }
    }
}
