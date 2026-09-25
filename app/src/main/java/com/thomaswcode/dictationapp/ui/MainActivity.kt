package com.thomaswcode.dictationapp.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.thomaswcode.dictationapp.DictationApp

class MainActivity : ComponentActivity() {
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as DictationApp).graph
        setContent {
            CompositionLocalProvider(LocalGraph provides graph) {
                DictationTheme { AppNavigation(requestMicrophone = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }) }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MICROPHONE, false) == true) {
            intent.removeExtra(EXTRA_REQUEST_MICROPHONE)
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    companion object {
        const val EXTRA_REQUEST_MICROPHONE = "request_microphone"
    }
}

object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val HISTORY_DETAIL = "history/{id}"
    const val SETTINGS = "settings"
    const val BUBBLE = "settings/bubble"
    const val STYLE = "settings/style"
    const val DICTIONARY = "settings/dictionary"
    const val CORRECTION = "settings/dictionary/correct"
    const val RULES = "settings/rules"
    const val AUDIO = "settings/audio"
    const val API = "settings/api"
    const val GENERAL = "settings/general"
    const val PRIVACY = "settings/privacy"
    const val ABOUT = "settings/about"

    fun historyDetail(id: Long) = "history/$id"
}

@Composable
private fun AppNavigation(requestMicrophone: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val topLevel = route == Routes.HOME || route == Routes.HISTORY || route == Routes.SETTINGS
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (topLevel) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    listOf(
                        Triple(Routes.HOME, "Home", Icons.Rounded.Home),
                        Triple(Routes.HISTORY, "History", Icons.Rounded.History),
                        Triple(Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
                    ).forEach { (r, label, icon) ->
                        NavigationBarItem(
                            selected = route == r,
                            onClick = { nav.goTopLevel(r) },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable(Routes.HOME) { HomeScreen(nav, requestMicrophone) }
            composable(Routes.HISTORY) { HistoryScreen(nav) }
            composable(Routes.HISTORY_DETAIL, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                HistoryDetailScreen(it.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.BUBBLE) { BubbleSettingsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.STYLE) { StyleSettingsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.DICTIONARY) { DictionaryScreen(onBack = { nav.popBackStack() }, onCorrect = { nav.navigate(Routes.CORRECTION) }) }
            composable(Routes.CORRECTION) { CorrectionScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.RULES) { AppRulesScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.AUDIO) { AudioSettingsScreen(onBack = { nav.popBackStack() }, requestMicrophone = requestMicrophone) }
            composable(Routes.API) { ApiSettingsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.GENERAL) { GeneralSettingsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.PRIVACY) { PrivacySettingsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

fun NavHostController.goTopLevel(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
