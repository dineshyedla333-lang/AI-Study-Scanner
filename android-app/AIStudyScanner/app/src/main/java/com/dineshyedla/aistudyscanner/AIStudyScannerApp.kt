package com.aistudyscanner.agent

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aistudyscanner.agent.navigation.Routes
import com.aistudyscanner.agent.screens.ExplainScreen
import com.aistudyscanner.agent.screens.HistoryScreen
import com.aistudyscanner.agent.screens.HomeScreen
import com.aistudyscanner.agent.screens.HomeworkScreen
import com.aistudyscanner.agent.screens.NewsAgentScreen
import com.aistudyscanner.agent.screens.ScannerScreen
import com.aistudyscanner.agent.screens.SolutionScreen
import com.aistudyscanner.agent.utils.runOcr

@Composable
fun AIStudyScannerApp() {
    val navController = rememberNavController()
    val context = LocalContext.current

    // State for gallery result — set by launcher, consumed by LaunchedEffect
    var pendingOcrText by remember { mutableStateOf<String?>(null) }
    var pendingExamMode by remember { mutableStateOf(true) }
    var pendingBoard by remember { mutableStateOf("Auto") }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            runOcr(
                context = context,
                imageUri = it,
                onTextExtracted = { text -> pendingOcrText = text },
                onError = { pendingOcrText = "" },
            )
        }
    }

    // Navigate to solution once OCR finishes from gallery
    LaunchedEffect(pendingOcrText) {
        pendingOcrText?.let { text ->
            navController.currentBackStackEntry?.savedStateHandle?.apply {
                set("extracted_text", text)
                set("exam_mode", pendingExamMode)
                set("board", pendingBoard)
            }
            navController.navigate(Routes.SOLUTION)
            pendingOcrText = null
        }
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            HomeScreen(
                onScanQuestion = { examMode, board ->
                    navController.currentBackStackEntry?.savedStateHandle?.apply {
                        set("exam_mode", examMode)
                        set("board", board)
                    }
                    navController.navigate(Routes.SCANNER)
                },
                onUploadScreenshot = { examMode, board ->
                    pendingExamMode = examMode
                    pendingBoard = board
                    galleryLauncher.launch("image/*")
                },
                onHomework = { examMode, board ->
                    navController.currentBackStackEntry?.savedStateHandle?.apply {
                        set("exam_mode", examMode)
                        set("board", board)
                    }
                    navController.navigate(Routes.HOMEWORK)
                },
                onNewsAgent = { navController.navigate(Routes.NEWS_AGENT) },
                onExplainPage = { navController.navigate(Routes.EXPLAIN) },
                onHistory = { navController.navigate(Routes.HISTORY) },
            )
        }

        composable(Routes.SCANNER) {
            val examMode = navController.previousBackStackEntry
                ?.savedStateHandle?.get<Boolean>("exam_mode") ?: true
            val board = navController.previousBackStackEntry
                ?.savedStateHandle?.get<String>("board") ?: "Auto"
            ScannerScreen(
                onBack = { navController.popBackStack() },
                onSolved = { extractedText ->
                    navController.currentBackStackEntry?.savedStateHandle?.apply {
                        set("extracted_text", extractedText)
                        set("exam_mode", examMode)
                        set("board", board)
                    }
                    navController.navigate(Routes.SOLUTION)
                },
            )
        }

        composable(Routes.SOLUTION) {
            val extractedText = navController.previousBackStackEntry
                ?.savedStateHandle?.get<String>("extracted_text") ?: ""
            val examMode = navController.previousBackStackEntry
                ?.savedStateHandle?.get<Boolean>("exam_mode") ?: true
            val board = navController.previousBackStackEntry
                ?.savedStateHandle?.get<String>("board") ?: "Auto"
            SolutionScreen(
                onBack = { navController.popBackStack() },
                extractedText = extractedText,
                initialExamMode = examMode,
                board = board,
            )
        }

        composable(Routes.HOMEWORK) {
            val examMode = navController.previousBackStackEntry
                ?.savedStateHandle?.get<Boolean>("exam_mode") ?: true
            val board = navController.previousBackStackEntry
                ?.savedStateHandle?.get<String>("board") ?: "Auto"
            HomeworkScreen(
                onBack = { navController.popBackStack() },
                initialExamMode = examMode,
                initialBoard = board,
            )
        }

        composable(Routes.NEWS_AGENT) {
            NewsAgentScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.HISTORY) {
            HistoryScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.EXPLAIN) {
            ExplainScreen(onBack = { navController.popBackStack() })
        }
    }
}
