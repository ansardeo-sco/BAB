package com.ansardeo.babnext

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ansardeo.babnext.ui.home.HomeScreen
import com.ansardeo.babnext.ui.manual.ManualScreen
import com.ansardeo.babnext.ui.more.MoreScreen
import com.ansardeo.babnext.ui.positions.PositionsScreen
import com.ansardeo.babnext.ui.settings.SettingsScreen
import com.ansardeo.babnext.ui.theme.BabNextTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BabNextTheme {
                BabNextRoot()
            }
        }
    }
}

@Composable
fun BabNextRoot() {
    val navController = rememberNavController()
    val tabs = listOf("home", "manual", "positions", "settings", "more")
    val labels = listOf("Home", "Manual", "Positions", "Settings", "More")
    val icons = listOf(
        Icons.Filled.Home,
        Icons.Filled.Edit,
        Icons.Filled.List,
        Icons.Filled.Settings,
        Icons.Filled.Info
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                val current = navController.currentBackStackEntryAsState().value?.destination?.route
                tabs.forEachIndexed { i, route ->
                    NavigationBarItem(
                        selected = current == route,
                        onClick = { navController.navigate(route) { popUpTo(navController.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true } },
                        icon = { Icon(icons[i], contentDescription = labels[i]) },
                        label = { Text(labels[i]) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(navController, startDestination = "home", Modifier.padding(padding)) {
            composable("home") { HomeScreen() }
            composable("manual") { ManualScreen() }
            composable("positions") { PositionsScreen() }
            composable("settings") { SettingsScreen() }
            composable("more") { MoreScreen() }
        }
    }
}