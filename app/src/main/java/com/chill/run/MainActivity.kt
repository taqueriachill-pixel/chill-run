package com.chill.run

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chill.run.ui.CheckScreen
import com.chill.run.ui.ChillTheme
import com.chill.run.ui.DetailScreen
import com.chill.run.ui.HistoryScreen
import com.chill.run.ui.HomeScreen
import com.chill.run.ui.IntervalScreen
import com.chill.run.ui.MapSaveScreen
import com.chill.run.ui.Palette
import com.chill.run.ui.PermissionsScreen
import com.chill.run.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    /** いま出している画面。"home" "history" "detail/12" "settings" "permissions" "check" */
    private var route by mutableStateOf("home")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        route = savedInstanceState?.getString("route") ?: "home"
        handleIntent(intent)
        setContent {
            ChillTheme {
                Box(Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val r = route
                    BackHandler(enabled = r != "home") { route = parentOf(r) }
                    when {
                        r == "history" -> HistoryScreen(onBack = { route = "home" }, onOpen = { route = "detail/$it" })
                        r.startsWith("detail/") -> DetailScreen(
                            runId = r.removePrefix("detail/").toLongOrNull() ?: 0,
                            onBack = { route = "history" },
                        )
                        r == "settings" -> SettingsScreen(onBack = { route = "home" }, onNavigate = { route = it })
                        r == "mapsave" -> MapSaveScreen(onBack = { route = "settings" })
                        r.startsWith("interval/") -> IntervalScreen(
                            menuId = r.removePrefix("interval/").toLongOrNull() ?: 0,
                            onBack = { route = "home" },
                        )
                        r == "permissions" -> PermissionsScreen(onBack = { route = "home" })
                        r == "check" -> CheckScreen(onBack = { route = "home" })
                        else -> HomeScreen(onNavigate = { route = it })
                    }
                }
            }
        }
    }

    private fun parentOf(r: String) = when {
        r.startsWith("detail/") -> "history"
        r == "mapsave" -> "settings"
        else -> "home"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_RUN_ID, 0) ?: 0
        if (id > 0) {
            route = "detail/$id"
            intent?.removeExtra(EXTRA_RUN_ID)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("route", route)
    }

    companion object {
        const val EXTRA_RUN_ID = "runId"
    }
}
