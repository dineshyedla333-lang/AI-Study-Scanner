package com.aistudyscanner.agent

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aistudyscanner.agent.navigation.Routes
import com.aistudyscanner.agent.screens.ExplainScreen
import com.aistudyscanner.agent.screens.HistoryScreen
import com.aistudyscanner.agent.screens.HomeScreen
import com.aistudyscanner.agent.screens.ScannerScreen
import com.aistudyscanner.agent.screens.SolutionScreen

@Composable
fun AIStudyScannerApp() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onScanQuestion = { navController.navigate(Routes.SCANNER) },
                onUploadScreenshot = { navController.navigate(Routes.SCANNER) },
                onExplainPage = { navController.navigate(Routes.EXPLAIN) },
                onHistory = { navController.navigate(Routes.HISTORY) }
            )
        }

        composable(Routes.SCANNER) {
            ScannerScreen(
                onBack = { navController.popBackStack() },
                onSolved = { extractedText ->
                    navController.currentBackStackEntry?.savedStateHandle?.set(
                        "extracted_text",
                        extractedText
                    )
                    navController.navigate(Routes.SOLUTION)
                }
            )
        }

        composable(Routes.SOLUTION) {
            val extractedText =
                navController.previousBackStackEntry?.savedStateHandle?.get<String>("extracted_text")
                    ?: ""
            SolutionScreen(
                onBack = { navController.popBackStack() },
                extractedText = extractedText
            )
        }

        composable(Routes.HISTORY) {
            HistoryScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.EXPLAIN) {
            ExplainScreen(onBack = { navController.popBackStack() })
        }
    }
}
