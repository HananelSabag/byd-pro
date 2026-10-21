package com.example.byd

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.byd.data.CarCommand
import com.example.byd.data.CarRepository
import com.example.byd.system.AppLanguage
import com.example.byd.ui.DashboardScreen
import com.example.byd.ui.theme.BydTheme

class MainActivity : ComponentActivity() {
    private val repo by lazy { CarRepository.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) runShortcut(intent) // not again after rotation
        setContent {
            BydTheme { DashboardScreen(repo) }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase)) // chosen language on phones before Android 13
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        runShortcut(intent)
    }

    /** Launcher shortcuts (long-press the icon) open the app and fire the command at once. */
    private fun runShortcut(intent: Intent?) {
        val action = intent?.action ?: return
        if (!action.startsWith(SHORTCUT_PREFIX)) return
        CarCommand.fromName(action.removePrefix(SHORTCUT_PREFIX))?.let(repo::sendAsync)
    }

    private companion object {
        const val SHORTCUT_PREFIX = "com.example.byd.action."
    }
}
