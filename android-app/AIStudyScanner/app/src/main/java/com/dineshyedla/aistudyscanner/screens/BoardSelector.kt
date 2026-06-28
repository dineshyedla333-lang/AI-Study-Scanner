package com.aistudyscanner.agent.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Exam boards the user can target. "Auto" lets the AI detect it. */
val BOARD_OPTIONS = listOf("Auto", "CBSE", "JEE", "NEET", "EAMCET")

/** Reusable exam/board dropdown shared by Home and Home Work screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardSelector(
    board: String,
    onBoardChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = board,
            onValueChange = {},
            readOnly = true,
            label = { Text("Exam / Board") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            supportingText = {
                Text(
                    if (board == "Auto")
                        "AI detects the board from your question"
                    else
                        "Answers tailored to the $board syllabus"
                )
            },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            BOARD_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onBoardChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
