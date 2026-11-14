package com.example.byd.ui

import android.app.TimePickerDialog
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.byd.R
import com.example.byd.bus.Outcomes
import com.example.byd.bus.Tone
import com.example.byd.data.AppPrefs
import com.example.byd.data.CarCommand
import com.example.byd.data.CarUiState
import com.example.byd.ui.theme.Numeric
import com.example.byd.ui.theme.Palette
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

typealias SendWith = (CarCommand, Map<String, String>) -> Unit

// ------------------------------------------------------------------ seat heating

/**
 * Driver / passenger seat heat: tap to step off → low → high. Taps settle for a moment and then
 * go out as one command, so tapping through levels never sends three commands to the car.
 */
@Composable
fun SeatHeatRow(state: CarUiState, send: SendWith) {
    val car = state.snapshot
    if (car.caps.seatDriver == false && car.caps.seatPassenger == false) return
    var driver by remember(car.seatDriver) { mutableIntStateOf(car.seatDriver ?: 0) }
    var passenger by remember(car.seatPassenger) { mutableIntStateOf(car.seatPassenger ?: 0) }
    var edits by remember { mutableIntStateOf(0) }
    val busy = state.pending.containsKey(CarCommand.SEAT)
    val view = LocalView.current

    LaunchedEffect(edits) {
        if (edits == 0) return@LaunchedEffect
        delay(1_200)
        send(CarCommand.SEAT, mapOf("driver" to "$driver", "passenger" to "$passenger"))
    }
    // The levels above are optimistic: if the car refuses, fall back to what it reports.
    val seatResult = state.latest(listOf(CarCommand.SEAT))?.takeIf { it.terminal }
    LaunchedEffect(seatResult?.id) {
        val result = seatResult ?: return@LaunchedEffect
        if (Outcomes.of(result).tone == Tone.ERR) {
            driver = car.seatDriver ?: 0
            passenger = car.seatPassenger ?: 0
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (car.caps.seatDriver != false) {
            SeatButton(R.string.seat_driver, driver, busy) {
                driver = (driver + 1) % 3
                edits++
                view.haptic(Haptic.Tap)
            }
        }
        if (car.caps.seatPassenger != false) {
            SeatButton(R.string.seat_passenger, passenger, busy) {
                passenger = (passenger + 1) % 3
                edits++
                view.haptic(Haptic.Tap)
            }
        }
    }
}

@Composable
private fun RowScope.SeatButton(@StringRes seat: Int, level: Int, busy: Boolean, onClick: () -> Unit) {
    val warmth by animateColorAsState(
        when (level) {
            2 -> Palette.Heat
            1 -> Palette.Warn
            else -> Palette.TextSecondary
        },
        tween(220), label = "warmth",
    )
    val levelText = stringResource(listOf(R.string.seat_off, R.string.seat_low, R.string.seat_high)[level])
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .weight(1f)
            .height(56.dp)
            .clip(shape)
            .background(if (level > 0) warmth.copy(alpha = 0.12f) else Palette.SurfaceHigh)
            .border(1.dp, if (level > 0) warmth.copy(alpha = 0.45f) else Color.Transparent, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = levelText }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = Palette.Busy, strokeWidth = 2.dp)
            Icon(painterResource(R.drawable.ic_seat), null, tint = warmth, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(seat), style = MaterialTheme.typography.labelLarge, color = Palette.TextPrimary, maxLines = 1)
            Text(levelText, style = MaterialTheme.typography.labelSmall, color = if (level > 0) warmth else Palette.TextMuted, maxLines = 1)
        }
        // Two dots: how hot it is at a glance.
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(2) { i ->
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (level > i) warmth else Palette.Outline))
            }
        }
    }
}

// ------------------------------------------------------------------ charging

/** A "not plugged in" reading older than this may be wrong by now, so it doesn't block "Start charging". */
private const val UNPLUGGED_TRUST_MS = 10 * 60_000L

private val MODES = listOf("stop_only" to R.string.mode_stop, "start_only" to R.string.mode_start, "window" to R.string.mode_window)

/** Charge now / pause, plus the car's charging schedule (stop at · start at · window). */
@Composable
fun ChargingSection(state: CarUiState, prefs: AppPrefs, send: SendWith, onWantsAlerts: () -> Unit) {
    val car = state.snapshot
    if (car.caps.charge == false) return
    val context = LocalContext.current
    // The section title already says "Charging", so the header carries what matters right now.
    val (status, statusColor) = when {
        car.charging == true -> listOfNotNull(
            car.chargingKw?.let { stringResource(R.string.charge_kw, it) },
            car.chargeEtaMin?.let { stringResource(R.string.charge_full_in, durationText(context, it)) },
        ).joinToString("  ·  ").ifEmpty { stringResource(R.string.state_charging_now) } to Palette.Ok
        car.plugged == true -> stringResource(R.string.state_plugged) to Palette.TextSecondary
        car.plugged == false -> stringResource(R.string.state_unplugged) to Palette.TextMuted
        else -> null to Palette.TextMuted
    }

    SectionHeader(R.string.charging_title, status, statusColor)
    Spacer(Modifier.height(10.dp))
    Panel {
        // One button for the move that makes sense now: charge when idle, pause while charging.
        // Both stay available while the car hasn't reported its charging state.
        val unplugged = car.plugged == false && System.currentTimeMillis() - car.updatedAt < UNPLUGGED_TRUST_MS
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (car.charging != true) {
                CommandButton(
                    command = CarCommand.CHARGE_NOW,
                    label = R.string.cmd_charge_now,
                    phase = rememberPhase(state, CarCommand.CHARGE_NOW),
                    filled = car.plugged == true,
                    enabled = !unplugged,
                    onClick = {
                        onWantsAlerts()
                        send(CarCommand.CHARGE_NOW, emptyMap())
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            if (car.charging != false) {
                CommandButton(
                    command = CarCommand.CHARGE_PAUSE,
                    label = R.string.cmd_charge_pause,
                    phase = rememberPhase(state, CarCommand.CHARGE_PAUSE),
                    filled = false,
                    onClick = { send(CarCommand.CHARGE_PAUSE, emptyMap()) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (unplugged && car.charging != true) {
            Text(
                stringResource(R.string.plug_in_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = Palette.Outline.copy(alpha = 0.7f))
        Spacer(Modifier.height(6.dp))

        // Local edits; reset whenever the car reports a different schedule.
        val saved = car.schedule
        var enabled by remember(saved) { mutableStateOf(saved?.enabled ?: false) }
        var mode by remember(saved) { mutableStateOf(prefs.scheduleMode) }
        var start by remember(saved) { mutableStateOf(saved?.start ?: "23:00") }
        var end by remember(saved) { mutableStateOf(saved?.end?.takeIf { it != "full" } ?: "07:00") }

        val wantedEnd = if (mode == "start_only") "full" else end
        val dirty = enabled != (saved?.enabled ?: false) ||
            (enabled && (wantedEnd != saved?.end || (mode != "stop_only" && start != saved?.start) || mode != prefs.scheduleMode))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .toggleable(value = enabled, role = Role.Switch) { enabled = it }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_clock), null, tint = Palette.TextMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.schedule), style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary)
                Text(
                    if (dirty) stringResource(R.string.schedule_unsaved) else scheduleSummary(context, saved?.enabled == true, saved?.start, saved?.end),
                    style = MaterialTheme.typography.bodySmall.merge(Numeric),
                    color = if (dirty) Palette.Warn else Palette.TextMuted,
                )
            }
            Switch(
                checked = enabled,
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

        AnimatedVisibility(enabled, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeSelector(mode) { mode = it }
                Text(
                    modeHint(context, mode, start, end),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (mode != "stop_only") TimeChip(R.string.from_label, start, Modifier.weight(1f)) { start = it }
                    if (mode != "start_only") {
                        TimeChip(R.string.to_label, end, Modifier.weight(1f)) { end = it }
                    } else {
                        Box(
                            Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.until_full), style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
                        }
                    }
                }
            }
        }

        val phase = rememberPhase(state, CarCommand.CHARGE_SCHEDULE)
        AnimatedVisibility(dirty || phase is Phase.Busy, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            CommandButton(
                command = CarCommand.CHARGE_SCHEDULE,
                label = R.string.cmd_charge_schedule,
                phase = phase,
                filled = true,
                onClick = {
                    if (enabled) onWantsAlerts()
                    send(
                        CarCommand.CHARGE_SCHEDULE,
                        mapOf(
                            "mode" to mode, "start" to start, "end" to end, "enabled" to "$enabled",
                            "now" to LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ModeSelector(mode: String, onPick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.SurfaceHigh)
            .selectableGroup()
            .padding(4.dp),
    ) {
        MODES.forEach { (id, label) ->
            val selected = id == mode
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) Palette.Accent.copy(alpha = 0.16f) else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton) { onPick(id) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Palette.Accent else Palette.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TimeChip(@StringRes label: Int, value: String, modifier: Modifier, onPick: (String) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.SurfaceHigh)
            .clickable(role = Role.Button) {
                val (h, m) = value.split(":").map { it.toInt() }
                TimePickerDialog(context, { _, hour, minute -> onPick("%02d:%02d".format(hour, minute)) }, h, m, true).show()
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleLarge.merge(Numeric), color = Palette.TextPrimary)
    }
}

/** One plain sentence saying what the chosen mode will do with the chosen times. */
private fun modeHint(context: Context, mode: String, start: String, end: String): String = when (mode) {
    "stop_only" -> context.getString(
        R.string.mode_stop_hint, "⁨$end⁩", "⁨${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))}⁩",
    )
    "start_only" -> context.getString(R.string.mode_start_hint, "⁨$start⁩")
    else -> context.getString(R.string.mode_window_hint, "⁨$start⁩", "⁨$end⁩")
}

private fun scheduleSummary(context: Context, enabled: Boolean, start: String?, end: String?): String = when {
    !enabled || start == null -> context.getString(R.string.schedule_off)
    end == null || end == "full" -> "${context.getString(R.string.from_label)} $start · ${context.getString(R.string.until_full)}"
    else -> "⁦$start – $end⁩"
}

private fun durationText(context: Context, minutes: Int): String =
    if (minutes >= 60) context.getString(R.string.duration_hm, minutes / 60, minutes % 60)
    else context.getString(R.string.duration_m, minutes)
