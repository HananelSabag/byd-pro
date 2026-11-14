package com.example.byd.widget

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.byd.R
import com.example.byd.bus.Source
import com.example.byd.data.CarCommand
import com.example.byd.data.CarRepository
import com.example.byd.system.AppLanguage
import com.example.byd.ui.theme.BydTheme
import com.example.byd.ui.theme.Palette
import com.example.byd.work.CommandService

/**
 * Quick cool / quick heat from the home screen ask first: a tap in a pocket or a bag must not
 * start the A/C. Nothing is sent until "Start"; then the widget shows "Sending…" at once.
 */
class ConfirmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = QuickAction.entries.getOrNull(intent.getIntExtra(WidgetClickReceiver.EXTRA_ACTION, -1))
        if (action == null) {
            finish()
            return
        }
        val repo = CarRepository.get(this)
        val command = action.command(repo) // decided now: a running A/C of this mode offers Stop
        setContent {
            BydTheme {
                ConfirmDialog(
                    command = command,
                    minutes = repo.prefs.climateMinutes,
                    recirc = repo.prefs.climateRecirculate,
                    onConfirm = {
                        CommandService.tap(this, command, Source.CONFIRM)
                        finish()
                    },
                    onDismiss = ::finish,
                )
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }
}

@Composable
private fun ConfirmDialog(command: CarCommand, minutes: Int, recirc: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val stop = command == CarCommand.CLIMATE_OFF
    val tint = if (command == CarCommand.QUICK_HEAT) Palette.Heat else Palette.Accent
    val title = when (command) {
        CarCommand.QUICK_HEAT -> R.string.confirm_heat_title
        CarCommand.QUICK_COOL -> R.string.confirm_cool_title
        else -> R.string.confirm_stop_title
    }
    val temp = if (command == CarCommand.QUICK_HEAT) CarRepository.HEAT_TEMP else CarRepository.COOL_TEMP
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(command.icon), null, tint = if (stop) Palette.TextSecondary else tint, modifier = Modifier.size(28.dp)) },
        title = { Text(stringResource(title)) },
        text = if (stop) {
            null
        } else {
            {
                Text(
                    stringResource(
                        R.string.confirm_body, temp, minutes,
                        stringResource(if (recirc) R.string.air_recirc_short else R.string.air_fresh_short),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(if (stop) R.string.confirm_stop else R.string.confirm_start), color = if (stop) Palette.TextPrimary else tint)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Palette.TextSecondary) }
        },
        containerColor = Palette.SurfaceHigh,
        iconContentColor = tint,
        titleContentColor = Palette.TextPrimary,
        textContentColor = Palette.TextSecondary,
    )
}
