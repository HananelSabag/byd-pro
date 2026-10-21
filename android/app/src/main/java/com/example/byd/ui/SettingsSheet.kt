package com.example.byd.ui

import android.app.Activity
import android.content.ClipData
import android.appwidget.AppWidgetManager
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.byd.BuildConfig
import com.example.byd.R
import com.example.byd.data.CarRepository
import com.example.byd.system.AppLanguage
import com.example.byd.system.ChargeWatch
import com.example.byd.ui.theme.Numeric
import com.example.byd.ui.theme.Palette
import com.example.byd.widget.DashboardWidget
import com.example.byd.widget.QuickAction
import kotlinx.coroutines.launch
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Two sections: the server connection (address, password, test, diagnostics) and the app itself
 * (language, vibration, charging alerts). One Save applies everything; defaults are built in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(repo: CarRepository, onDismiss: () -> Unit) {
    val prefs = repo.prefs
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var url by remember { mutableStateOf(prefs.serverUrl) }
    var secret by remember { mutableStateOf(prefs.secret) }
    var showSecret by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(AppLanguage.get(context)) }
    var haptics by remember { mutableStateOf(prefs.haptics) }
    var chargeAlerts by remember { mutableStateOf(prefs.chargeAlerts) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Result<Long>?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.SurfaceHigh,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, color = Palette.TextPrimary)

            // -------------------------------------------------------------- connection
            Section(R.string.settings_connection) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; testResult = null },
                    label = { Text(stringResource(R.string.server_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(stringResource(R.string.server_secret)) },
                    singleLine = true,
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            Icon(painterResource(R.drawable.ic_eye), stringResource(R.string.show_password))
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                TestRow(testing, testResult) {
                    testing = true
                    scope.launch {
                        testResult = runCatching { repo.ping(url) }
                        testing = false
                    }
                }
                Divider()
                Diagnostics(repo, url, secret)
                Divider()
                TextButton(
                    onClick = {
                        prefs.resetServer()
                        url = prefs.serverUrl
                        secret = prefs.secret
                        testResult = null
                    },
                    modifier = Modifier.padding(top = 2.dp),
                ) { Text(stringResource(R.string.reset_defaults), color = Palette.TextSecondary) }
            }

            // -------------------------------------------------------------- app
            Section(R.string.settings_app) {
                LanguageRow(language) { language = it }
                Divider()
                ToggleRow(R.string.haptics, R.string.haptics_hint, haptics) { haptics = it }
                Divider()
                ToggleRow(R.string.charge_alerts, R.string.charge_alerts_hint, chargeAlerts) { chargeAlerts = it }
                Divider()
                WidgetsRow()
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    prefs.serverUrl = url
                    prefs.secret = secret
                    prefs.haptics = haptics
                    prefs.chargeAlerts = chargeAlerts
                    if (!chargeAlerts) ChargeWatch.cancelAll(context)
                    onDismiss()
                    repo.refreshAsync()
                    context.findActivity()?.let { AppLanguage.set(it, language) } // re-opens the screen if it changed
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
            ) { Text(stringResource(R.string.save), style = MaterialTheme.typography.titleSmall) }

            // Credits: here only, not on the main screen.
            Column(
                Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "BYD Pro  ·  ${stringResource(R.string.version, BuildConfig.VERSION_NAME)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "By Hananel Sabag",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** A titled card that groups related settings. */
@Composable
private fun Section(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = Palette.Accent,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp, start = 4.dp),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Palette.Outline.copy(alpha = 0.6f))

/** Phone default / עברית / English. Each language is written in its own script. */
@Composable
private fun LanguageRow(selected: String, onPick: (String) -> Unit) {
    val options = listOf(
        AppLanguage.SYSTEM to stringResource(R.string.language_system),
        AppLanguage.HEBREW to "עברית",
        AppLanguage.ENGLISH to "English",
    )
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(stringResource(R.string.language), style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.SurfaceHigh)
                .selectableGroup()
                .padding(4.dp),
        ) {
            options.forEach { (tag, label) ->
                val isSelected = tag == selected
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (isSelected) Palette.Accent.copy(alpha = 0.16f) else Color.Transparent)
                        .selectable(selected = isSelected, role = Role.RadioButton) { onPick(tag) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) Palette.Accent else Palette.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Puts a widget on the home screen straight from here: the phone asks once and places it.
 * (Long-pressing the home screen works too, but few people know where to look.)
 */
@Composable
private fun WidgetsRow() {
    val context = LocalContext.current
    val manager = remember { AppWidgetManager.getInstance(context) }
    if (!manager.isRequestPinAppWidgetSupported) return
    var open by remember { mutableStateOf(false) }
    val widgets = listOf(
        DashboardWidget::class.java to R.string.widget_dashboard_name,
    ) + QuickAction.entries.map { it.provider to it.label }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.widgets_title), style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
            Text(stringResource(R.string.widgets_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
        Box {
            TextButton(onClick = { open = true }) { Text(stringResource(R.string.widgets_add), color = Palette.Accent) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.SurfaceTop) {
                widgets.forEach { (provider, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label), color = Palette.TextPrimary) },
                        onClick = {
                            open = false
                            manager.requestPinAppWidget(ComponentName(context, provider), null, null)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(@StringRes title: Int, @StringRes hint: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
        Spacer(Modifier.width(12.dp))
        // The whole row toggles; the switch only shows the state.
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Palette.Accent,
                checkedThumbColor = Palette.OnAccent,
                uncheckedTrackColor = Palette.SurfaceTop,
                uncheckedThumbColor = Palette.TextMuted,
                uncheckedBorderColor = Palette.Outline,
            ),
        )
    }
}

/** Last response time, when the car last reported, and the logs link to share with whoever helps. */
@Composable
private fun Diagnostics(repo: CarRepository, url: String, secret: String) {
    val state = repo.state.value
    val context = LocalContext.current
    val lines = listOfNotNull(
        state.latencyMs?.let { stringResource(R.string.latency, it.toInt()) },
        state.snapshot.updatedAt.takeIf { it > 0 }?.let {
            val ago = if (System.currentTimeMillis() - it < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.just_now)
            else DateUtils.getRelativeTimeSpanString(it).toString()
            stringResource(R.string.last_car_report, ago)
        },
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            lines.joinToString("\n") { "⁨$it⁩" },
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextMuted,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            // A logs-only key (the server derives the same one), never the password: the
            // password would also open the car for whoever gets the link.
            val link = url.trim().trimEnd('/') + "/logs?key=" + logsKey(secret.trim())
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("BYD logs", link))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, R.string.logs_copied, Toast.LENGTH_SHORT).show() // newer Android shows its own
            }
        }) { Text(stringResource(R.string.copy_logs), color = Palette.Accent) }
    }
}

private fun logsKey(secret: String): String {
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
    return mac.doFinal("logs".toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}

@Composable
private fun TestRow(testing: Boolean, result: Result<Long>?, onTest: () -> Unit) {
    val ms = result?.getOrNull()
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !testing, role = Role.Button, onClick = onTest),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_pulse), null, tint = Palette.Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.test_connection), style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
        Spacer(Modifier.weight(1f))
        when {
            testing -> CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Accent, strokeWidth = 2.dp)
            result != null -> {
                val ok = ms != null
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Palette.Ok else Palette.Err))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (ok) stringResource(R.string.test_ok, ms!!.toInt()) else stringResource(R.string.test_fail),
                    style = MaterialTheme.typography.bodySmall.merge(Numeric),
                    color = if (ok) Palette.Ok else Palette.Err,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Accent,
    unfocusedBorderColor = Palette.Outline,
    focusedLabelColor = Palette.Accent,
    unfocusedLabelColor = Palette.TextMuted,
    cursorColor = Palette.Accent,
    focusedTrailingIconColor = Palette.TextSecondary,
    unfocusedTrailingIconColor = Palette.TextMuted,
)

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
