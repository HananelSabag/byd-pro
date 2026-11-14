package com.example.byd

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import com.example.byd.work.CheckWorker
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.byd.bus.Source
import com.example.byd.data.CarCommand
import com.example.byd.data.CarRepository
import com.example.byd.system.AppLanguage
import com.example.byd.ui.DashboardScreen
import com.example.byd.ui.theme.BydTheme

class MainActivity : ComponentActivity() {
    private val repo by lazy { CarRepository.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) runShortcut(intent) // not again after rotation
        if (repo.prefs.autoCheck) CheckWorker.schedule(this) // kept by WorkManager; KEEP makes this a no-op once set
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

    /**
     * Launcher shortcuts (long-press the icon) open the app and fire the command at once, through
     * the same bus as everything else. Only for a fresh tap: reopening the app from Recents
     * delivers the old shortcut intent again, and must not unlock the car a second time.
     */
    private fun runShortcut(intent: Intent?) {
        val action = intent?.action ?: return
        if (!action.startsWith(SHORTCUT_PREFIX)) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val command = CarCommand.fromName(action.removePrefix(SHORTCUT_PREFIX)) ?: return
        setIntent(Intent(intent).setAction(Intent.ACTION_MAIN)) // consumed: a recreation won't see it again
        repo.run(command, source = Source.SHORTCUT)
    }

    private companion object {
        const val SHORTCUT_PREFIX = "com.example.byd.action."
    }
}
