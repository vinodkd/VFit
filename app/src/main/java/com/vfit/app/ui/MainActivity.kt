package com.vfit.app.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { VFitTheme { VFitRoot(vm) } }
    }
}

@Composable
fun VFitTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private data class Dest(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Dest("today", "Today", Icons.Filled.Edit),
    Dest("summary", "Summary", Icons.Filled.Home),
    Dest("charts", "Charts", Icons.Filled.DateRange),
    Dest("history", "History", Icons.AutoMirrored.Filled.List),
    Dest("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun VFitRoot(vm: AppViewModel) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    val current = nav.currentBackStackEntryAsState().value?.destination?.route
    fun go(route: String) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                for (t in tabs) NavigationBarItem(
                    selected = current == t.route,
                    onClick = { go(t.route) },
                    icon = { Icon(t.icon, contentDescription = t.label) },
                    label = { Text(t.label) },
                )
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "today", modifier = Modifier.padding(pad)) {
            composable("today") { TodayScreen(vm, onManageMeals = { nav.navigate("meals") }) }
            composable("summary") { SummaryScreen(vm) }
            composable("charts") { ChartsScreen(vm) }
            composable("history") { HistoryScreen(vm, onOpenDay = { vm.selectDate(it); go("today") }) }
            composable("settings") { SettingsScreen(vm, onManageMeals = { nav.navigate("meals") }) }
            composable("meals") { MealsScreen(vm, onBack = { nav.popBackStack() }) }
        }
    }
}
