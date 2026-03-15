package com.dineshyedla.aistudyscanner

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dineshyedla.aistudyscanner.navigation.Routes
import com.dineshyedla.aistudyscanner.screens.ExplainScreen
import com.dineshyedla.aistudyscanner.screens.HistoryScreen
import com.dineshyedla.aistudyscanner.screens.HomeScreen
import com.dineshyedla.aistudyscanner.screens.ScannerScreen
import com.dineshyedla.aistudyscanner.screens.SolutionScreen

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
