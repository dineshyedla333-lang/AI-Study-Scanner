package com.aistudyscanner.agent.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplainScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("How to Use") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HowToCard(
                step = "1",
                title = "Scan & Understand",
                desc = "Point your camera at any printed question. Make sure the text is clear and well-lit. Tap Capture to extract the text automatically.",
            )
            HowToCard(
                step = "2",
                title = "Upload from Gallery",
                desc = "Already have a screenshot or photo of a question? Tap 'Upload from Gallery' to pick it from your phone without using the camera.",
            )
            HowToCard(
                step = "3",
                title = "Edit if Needed",
                desc = "After scanning, the extracted text is editable. If the scanner misread something, simply tap on the text box and fix it before solving.",
            )
            HowToCard(
                step = "4",
                title = "Explain in your language",
                desc = "Pick your language at the top of the home screen — Hindi, Telugu, Tamil, Kannada, Malayalam, Marathi, Bengali or Gujarati. The AI then explains every step in that language, while formulas and technical terms stay in English so they match your textbook and exam paper. Your choice is remembered.",
            )
            HowToCard(
                step = "5",
                title = "Select Your Board",
                desc = "Choose JEE, NEET, CBSE, or TS EAMCET so the AI tailors the answer to your syllabus. 'Auto' lets the AI detect the board from the question.",
            )
            HowToCard(
                step = "6",
                title = "Share or Copy Answer",
                desc = "After the AI solves the question, use the Share button to send it via WhatsApp or any app. Use Copy to paste it anywhere.",
            )
            HowToCard(
                step = "7",
                title = "How the AI worked it out",
                desc = "Every answer starts with the concept and the method, then the working, and ends with the final answer. Tap any step card in the solution to see how the AI classified and approached the question — subject, topic, difficulty, and the strategy used.",
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Listen to the explanation",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Tap 'Listen' under any solution — or under any Home " +
                            "Work answer — and your phone reads the whole explanation " +
                            "aloud in the language you chose, so you can follow along " +
                            "instead of only reading. If your phone says the voice " +
                            "isn't available for your language, install it from " +
                            "Settings → Text-to-speech (or Language & input → " +
                            "Text-to-speech output).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Key concept & practice",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Under every solution you get the key concept behind " +
                            "the question — including the mistake students usually " +
                            "make — plus three similar questions to try. These are " +
                            "free: they don't use up your daily solves.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Daily streak",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Solve at least one question a day and your streak goes " +
                            "up, right at the top of the home screen. Miss a day and it " +
                            "starts again — but your best streak is always remembered.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Home Work — Practice Yourself",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Tap 'Home Work' on the home screen, type a topic and pick how many questions (5–20). " +
                            "The AI creates practice questions for your board. Solve them on paper, then tap " +
                            "'Show Answer' on each — or 'Reveal all answers' — to check your work.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "AI Study Planner — Month-by-Month Plan",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Tap 'AI Study Planner', choose your exam (JEE, NEET, CBSE, " +
                            "EAMCET or UPSC), how many months you have, and your daily study " +
                            "hours. The AI builds a month-by-month program with the topics to " +
                            "cover and a milestone to hit each month.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "UPSC Live Agent — Daily Current Affairs",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Open 'UPSC Live Agent', pick up to 4 times of day and turn it on. " +
                            "You'll get a notification at each time with fresh current-affairs " +
                            "questions and answers built from today's news. Tap 'Preview today's " +
                            "questions' to see a sample right away. (Allow notifications when asked.)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Daily Free Limit",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "You can solve 10 questions per day for free, and the " +
                            "counter resets at midnight. Run out early? Watch a short " +
                            "ad for 3 more, or go Pro for unlimited solves with no ads.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HowToCard(step: String, title: String, desc: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    ),
            ) {
                Text(
                    text = step,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
