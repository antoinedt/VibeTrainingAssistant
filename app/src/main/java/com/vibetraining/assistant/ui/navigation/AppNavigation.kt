package com.vibetraining.assistant.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vibetraining.assistant.ui.screens.CompareScreen
import com.vibetraining.assistant.ui.screens.HomeScreen
import com.vibetraining.assistant.ui.screens.SettingsScreen
import com.vibetraining.assistant.ui.screens.TrainingScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Training : Screen("training")
    object Compare : Screen("compare")
    object Settings : Screen("settings")
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Screen.Home.route) {
        composable(Screen.Home.route) {
            HomeScreen(
                onTraining = { navController.navigate(Screen.Training.route) },
                onCompare = { navController.navigate(Screen.Compare.route) },
                onSettings = { navController.navigate(Screen.Settings.route) }
            )
        }
        composable(Screen.Training.route) {
            TrainingScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Compare.route) {
            CompareScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
