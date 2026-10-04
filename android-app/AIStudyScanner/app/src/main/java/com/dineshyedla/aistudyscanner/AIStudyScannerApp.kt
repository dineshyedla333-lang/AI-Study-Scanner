package com.aistudyscanner.agent

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aistudyscanner.agent.auth.ProfilePrefs
import com.aistudyscanner.agent.navigation.Routes
import com.aistudyscanner.agent.screens.ExplainScreen
import com.aistudyscanner.agent.screens.HistoryScreen
import com.aistudyscanner.agent.screens.HomeScreen
import com.aistudyscanner.agent.screens.HomeworkScreen
import com.aistudyscanner.agent.screens.LoginScreen
import com.aistudyscanner.agent.screens.NewsAgentScreen
import com.aistudyscanner.agent.screens.PlannerScreen
import com.aistudyscanner.agent.screens.ProfileScreen
import com.aistudyscanner.agent.screens.ScannerScreen
import com.aistudyscanner.agent.screens.SolutionScreen
import com.aistudyscanner.agent.screens.UpgradeScreen
import com.aistudyscanner.agent.screens.WarmingUpBanner
import com.aistudyscanner.agent.usage.TrialPrefs
import com.aistudyscanner.agent.utils.runOcr

@Composable
fun AIStudyScannerApp() {
    val navController = rememberNavController()
    val context = LocalContext.current

    // True while the user may still solve without an account.
    fun trialAvailable(): Boolean =
        ProfilePrefs.isRegistered(context) || !TrialPrefs.exhausted(context)

    // State for gallery result — set by launcher, consumed by LaunchedEffect
    var pendingOcrText by remember { mutableStateOf<String?>(null) }
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
                set("board", pendingBoard)
            }
            navController.navigate(Routes.SOLUTION)
            pendingOcrText = null
        }
    }

    // Start on HOME even for a brand-new install. Someone arriving from a video or
    // an ad gets TrialPrefs.FREE_SOLVES real answers before being asked to register;
    // gating first cost us the users who had not yet seen the app do anything.
    val startDestination = remember { Routes.HOME }


    // The banner floats over every screen, so no screen needs its own cold-start UI.
    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = startDestination) {

            composable(Routes.LOGIN) {
                LoginScreen(
                    onRegistered = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    },
                    freeSolvesUsed = TrialPrefs.used(context),
                )
            }

            composable(Routes.HOME) {
                HomeScreen(
                    onScanQuestion = { board ->
                        if (!trialAvailable()) {
                            navController.navigate(Routes.LOGIN)
                            return@HomeScreen
                        }
                        navController.currentBackStackEntry?.savedStateHandle
                            ?.set("board", board)
                        navController.navigate(Routes.SCANNER)
                    },
                    onUploadScreenshot = { board ->
                        if (!trialAvailable()) {
                            navController.navigate(Routes.LOGIN)
                            return@HomeScreen
                        }
                        pendingBoard = board
                        galleryLauncher.launch("image/*")
                    },
                    onHomework = { board ->
                        navController.currentBackStackEntry?.savedStateHandle
                            ?.set("board", board)
                        navController.navigate(Routes.HOMEWORK)
                    },
                    onNewsAgent = { navController.navigate(Routes.NEWS_AGENT) },
                    onPlanner = { board ->
                        navController.currentBackStackEntry?.savedStateHandle
                            ?.set("board", board)
                        navController.navigate(Routes.PLANNER)
                    },
                    onProfile = { navController.navigate(Routes.PROFILE) },
                    onUpgrade = { navController.navigate(Routes.UPGRADE) },
                    onExplainPage = { navController.navigate(Routes.EXPLAIN) },
                    onHistory = { navController.navigate(Routes.HISTORY) },
                )
            }

            composable(Routes.SCANNER) {
                val board = navController.previousBackStackEntry
                    ?.savedStateHandle?.get<String>("board") ?: "Auto"
                ScannerScreen(
                    onBack = { navController.popBackStack() },
                    onSolved = { extractedText ->
                        navController.currentBackStackEntry?.savedStateHandle?.apply {
                            set("extracted_text", extractedText)
                            set("board", board)
                        }
                        navController.navigate(Routes.SOLUTION)
                    },
                )
            }

            composable(Routes.SOLUTION) {
                val extractedText = navController.previousBackStackEntry
                    ?.savedStateHandle?.get<String>("extracted_text") ?: ""
                val board = navController.previousBackStackEntry
                    ?.savedStateHandle?.get<String>("board") ?: "Auto"
                SolutionScreen(
                    onBack = { navController.popBackStack() },
                    onUpgrade = { navController.navigate(Routes.UPGRADE) },
                    // Free solves are counted only when an answer arrives (see
                    // SolutionViewModel), so the wall can also land mid-screen.
                    onRegister = {
                        navController.navigate(Routes.LOGIN) { popUpTo(Routes.HOME) }
                    },
                    extractedText = extractedText,
                    board = board,
                )
            }

            composable(Routes.HOMEWORK) {
                val board = navController.previousBackStackEntry
                    ?.savedStateHandle?.get<String>("board") ?: "Auto"
                HomeworkScreen(
                    onBack = { navController.popBackStack() },
                    initialBoard = board,
                )
            }

            composable(Routes.PLANNER) {
                val board = navController.previousBackStackEntry
                    ?.savedStateHandle?.get<String>("board") ?: "JEE"
                PlannerScreen(
                    onBack = { navController.popBackStack() },
                    initialBoard = board,
                )
            }

            composable(Routes.NEWS_AGENT) {
                NewsAgentScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.UPGRADE) {
                UpgradeScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.PROFILE) {
                ProfileScreen(
                    onBack = { navController.popBackStack() },
                    onLoggedOut = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.HISTORY) {
                HistoryScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.EXPLAIN) {
                ExplainScreen(onBack = { navController.popBackStack() })
            }
        }
        WarmingUpBanner(modifier = Modifier.align(Alignment.BottomCenter))
    }
}
