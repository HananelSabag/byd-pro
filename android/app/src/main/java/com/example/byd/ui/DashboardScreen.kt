package com.example.byd.ui

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.byd.BuildConfig
import com.example.byd.R
import com.example.byd.bus.Source
import com.example.byd.data.AppPrefs
import com.example.byd.data.CarCommand
import com.example.byd.data.CarRepository
import com.example.byd.data.CarSnapshot
import com.example.byd.data.CarUiState
import com.example.byd.data.ClimateMode
import com.example.byd.data.Tires
import com.example.byd.ui.theme.Numeric
import com.example.byd.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(repo: CarRepository) {
    val state by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val scroll = rememberScrollState()
    // First run without a server configured: start in Settings, where it is set.
    var showSettings by rememberSaveable { mutableStateOf(repo.prefs.serverUrl.isBlank()) }
    var heroOnScreen by remember { mutableStateOf(true) }
    var pulling by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Re-read the car when the app comes to the front, unless it was read in the last few
    // seconds. The server answers from its cache when its data is recent, so the car is only
    // woken when it is really needed.
    LifecycleStartEffect(Unit) {
        repo.refreshIfStale()
        onStopOrDispose { }
    }

    // The car's answer buzzes once from the command bus (CarRepository), like a widget's or the
    // watch's; the message itself is in ResultBanner. Here only the tap.
    val send: (CarCommand) -> Unit = { command ->
        if (repo.prefs.haptics) view.haptic(Haptic.Tap)
        repo.run(command, source = Source.APP)
    }
    val sendWith: SendWith = { command, params ->
        if (repo.prefs.haptics) view.haptic(Haptic.Tap)
        repo.run(command, params, Source.APP)
    }
    // Charging alerts need the notification permission (Android 13+); asked only when it's relevant.
    val wantsAlerts: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && repo.prefs.chargeAlerts &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Backdrop)) {
        PullToRefreshBox(
            isRefreshing = pulling,
            onRefresh = {
                pulling = true
                scope.launch {
                    repo.refresh(fresh = true)
                    pulling = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 560.dp).padding(horizontal = 20.dp)) {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    TopBar(state, onRefresh = { repo.refreshAsync(fresh = true) }, onSettings = { showSettings = true })
                    Spacer(Modifier.height(12.dp))
                    CarSideView(
                        state.snapshot,
                        warmAir = state.climateMode == ClimateMode.HEAT,
                        fx = heroFx(state),
                        animate = heroOnScreen,
                        online = state.online != false,
                        modifier = Modifier
                            .bleed(20.dp)
                            .onGloballyPositioned { heroOnScreen = it.boundsInWindow().height > 1f },
                    )
                    Spacer(Modifier.height(6.dp))
                    Readout(state.snapshot)
                    WakingBanner(state)
                    Spacer(Modifier.height(22.dp))
                    LockSwitch(state, send)
                    Spacer(Modifier.height(22.dp))
                    QuickActions(state, send)
                    Spacer(Modifier.height(30.dp))
                    ClimateSection(state, repo.prefs, send, sendWith)
                    Spacer(Modifier.height(30.dp))
                    ChargingSection(state, repo.prefs, sendWith, wantsAlerts)
                    Spacer(Modifier.height(30.dp))
                    VehicleSection(state.snapshot)
                    Spacer(Modifier.height(20.dp))
                    Footer(state, repo.prefs)
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }

        // Fades content out under the status bar instead of letting it collide with the clock.
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(Brush.verticalGradient(listOf(Palette.BackdropTop, Palette.BackdropTop.copy(alpha = 0.85f)))),
        )

        ResultBanner(
            result = state.lastResult,
            car = state.snapshot,
            onRetry = sendWith,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .widthIn(max = 560.dp),
        )
    }

    if (showSettings) SettingsSheet(repo, onDismiss = { showSettings = false })
}

/** What the car picture should play for a command the car just confirmed. */
private fun heroFx(state: CarUiState): Pair<Long, HeroFx>? {
    val result = state.lastResult?.takeIf { it.ok && System.currentTimeMillis() - (it.finishedAt ?: 0L) < 3_000 } ?: return null
    return when (result.command) {
        CarCommand.LOCK -> (result.finishedAt ?: 0L) to HeroFx.LOCKED
        CarCommand.UNLOCK -> (result.finishedAt ?: 0L) to HeroFx.UNLOCKED
        CarCommand.FLASH -> (result.finishedAt ?: 0L) to HeroFx.FLASH
        CarCommand.LOCATE -> (result.finishedAt ?: 0L) to HeroFx.FIND
        else -> null
    }
}

/** Lets a child reach past the column's side padding, out to the screen edges. */
private fun Modifier.bleed(side: Dp) = layout { measurable, constraints ->
    val extra = side.roundToPx() * 2
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

// ------------------------------------------------------------------ top bar

@Composable
private fun TopBar(state: CarUiState, onRefresh: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(state.snapshot.name.uppercase(), style = MaterialTheme.typography.headlineSmall, color = Palette.TextPrimary)
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_refresh), onClick = onRefresh)
                    .heightIn(min = 40.dp)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.refreshing) {
                    CircularProgressIndicator(Modifier.size(10.dp), color = Palette.Accent, strokeWidth = 1.5.dp)
                } else {
                    val dot = when (state.online) {
                        true -> Palette.Ok
                        false -> Palette.Err
                        null -> Palette.TextMuted
                    }
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                }
                Spacer(Modifier.width(8.dp))
                Text(statusLine(state), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1)
            }
        }
        IconButton(onClick = onSettings) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Palette.Surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_settings), stringResource(R.string.action_settings),
                    tint = Palette.TextSecondary, modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** "Updated 2 min ago", re-evaluated every 30 s only while the screen is visible. */
@Composable
private fun statusLine(state: CarUiState): String {
    val now by rememberNow(30_000)
    val car = state.snapshot
    return when {
        state.online == false -> stringResource(R.string.status_offline)
        !car.hasData -> stringResource(R.string.no_data)
        else -> {
            val minutes = ((now - car.updatedAt) / 60_000).toInt().coerceAtLeast(0)
            when {
                minutes < 1 -> stringResource(R.string.updated_now)
                minutes < 60 -> stringResource(R.string.updated_min, minutes)
                minutes < 48 * 60 -> stringResource(R.string.updated_hours, minutes / 60)
                else -> stringResource(R.string.updated_days, minutes / (24 * 60))
            }
        }
    }
}

@Composable
private fun rememberNow(periodMs: Long): androidx.compose.runtime.State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now.longValue = System.currentTimeMillis()
                delay(periodMs)
            }
        }
    }
    return now
}

// ------------------------------------------------------------------ battery readout

/** Battery and range read like an instrument cluster under the car; warnings only when they apply. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Readout(car: CarSnapshot) {
    val socColor = when {
        car.soc == null -> Palette.TextPrimary
        car.soc < 20 -> Palette.Err
        car.soc < 35 -> Palette.Warn
        else -> Palette.TextPrimary
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Metric(car.soc?.toString() ?: "--", "%", R.string.battery, socColor, Palette.Accent)
            Box(
                Modifier
                    .padding(horizontal = 30.dp)
                    .width(1.dp)
                    .height(44.dp)
                    .background(Palette.Outline),
            )
            Metric(car.rangeKm?.let(::grouped) ?: "--", stringResource(R.string.unit_km), R.string.range, Palette.TextPrimary, Palette.TextMuted)
        }
        if (car.charging == true) {
            Spacer(Modifier.height(10.dp))
            val eta = car.chargeEtaMin?.let { duration(LocalContext.current, it) }
            StatusChip(
                R.drawable.ic_bolt,
                if (eta != null) stringResource(R.string.state_charging_eta, eta) else stringResource(R.string.state_charging),
                Palette.Ok,
            )
        }
        // The picture and the buttons already show the tailgate and windows; a door can be on the
        // far side, so its chip says which one.
        doorChip(car)?.let { text ->
            Spacer(Modifier.height(10.dp))
            StatusChip(R.drawable.ic_car, text, Palette.Warn)
        }
    }
}

@Composable
private fun doorChip(car: CarSnapshot): String? {
    val doors = car.doors
    val open = listOfNotNull(
        R.string.state_door_driver.takeIf { doors?.lf == true },
        R.string.state_door_passenger.takeIf { doors?.rf == true },
        R.string.state_door_rear.takeIf { doors?.lr == true || doors?.rr == true },
    )
    val count = listOf(doors?.lf, doors?.rf, doors?.lr, doors?.rr).count { it == true }
    return when {
        count > 1 -> stringResource(R.string.state_doors_open, count)
        open.size == 1 -> stringResource(open.single())
        car.doorsOpen == true -> stringResource(R.string.state_door_open)
        else -> null
    }
}

@Composable
private fun Metric(value: String, unit: String, @StringRes label: Int, valueColor: Color, unitColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.displaySmall.merge(Numeric), color = valueColor)
            Text(
                unit,
                style = MaterialTheme.typography.titleMedium,
                color = unitColor,
                modifier = Modifier.padding(start = 3.dp, bottom = 5.dp),
            )
        }
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
    }
}

/** Shown while a sleeping server starts, or when a command has been on its way for a while (sleeping car). */
@Composable
private fun WakingBanner(state: CarUiState) {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(state.pending) {
        slow = false
        val oldest = state.pending.values.minOrNull() ?: return@LaunchedEffect
        delay((8_000 - (System.currentTimeMillis() - oldest)).coerceAtLeast(0))
        slow = true
    }
    val text = when {
        state.warming -> R.string.st_waking_server_long
        state.climateChecking -> R.string.climate_checking
        else -> R.string.slow_hint
    }
    val visible = state.warming || state.climateChecking || (slow && state.pending.isNotEmpty())
    AnimatedVisibility(visible, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Row(
            Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.Accent.copy(alpha = 0.08f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Palette.Accent, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = Palette.TextPrimary)
        }
    }
}

// ------------------------------------------------------------------ quick actions

/** Secondary actions as one calm row of discs. Ones the car reports as unsupported are hidden. */
@Composable
private fun QuickActions(state: CarUiState, send: (CarCommand) -> Unit) {
    val car = state.snapshot
    // Equal columns, so a longer label wraps inside its own column instead of crowding a neighbour.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val column = Modifier.weight(1f)
        if (car.caps.trunk != false) {
            RoundAction(
                CarCommand.TRUNK, rememberPhase(state, CarCommand.TRUNK),
                active = car.trunkOpen == true, activeColor = Palette.Warn,
                status = when (car.trunkOpen) {
                    true -> stringResource(R.string.state_open)
                    false -> stringResource(R.string.state_closed)
                    null -> null
                },
                onClick = { send(CarCommand.TRUNK) },
                modifier = column,
            )
        }
        if (car.caps.locate != false) {
            RoundAction(
                CarCommand.LOCATE, rememberPhase(state, CarCommand.LOCATE), active = false, activeColor = Palette.Accent,
                status = null, onClick = { send(CarCommand.LOCATE) },
                modifier = column,
            )
        }
        if (car.caps.flash == true) {
            RoundAction(
                CarCommand.FLASH, rememberPhase(state, CarCommand.FLASH), active = false, activeColor = Palette.Accent,
                status = null, onClick = { send(CarCommand.FLASH) },
                modifier = column,
            )
        }
        if (car.caps.closeWindows == true) {
            // One toggle: open windows close; closed ones open (when the car supports it).
            val open = car.windowsOpen == true
            val canVent = car.caps.ventWindows == true
            val command = if (car.windowsOpen == false && canVent) CarCommand.WINDOWS_VENT else CarCommand.WINDOWS_CLOSE
            RoundAction(
                command,
                rememberPhase(state, CarCommand.WINDOWS_CLOSE, CarCommand.WINDOWS_VENT),
                active = open, activeColor = Palette.Warn,
                status = when (car.windowsOpen) {
                    true -> stringResource(R.string.state_windows_open)
                    false -> if (canVent) stringResource(R.string.state_windows_closed) else null
                    null -> null
                },
                onClick = { send(command) },
                label = if (command == CarCommand.WINDOWS_VENT) R.string.cmd_windows_vent else R.string.cmd_windows,
                modifier = column,
            )
        }
    }
}

// ------------------------------------------------------------------ climate

/**
 * Climate in one tap, like the car's own app: quick cool or quick heat with the saved run time
 * and air source, and Stop while it runs. The air source and seat heating sit under it.
 */
@Composable
private fun ClimateSection(state: CarUiState, prefs: AppPrefs, send: (CarCommand) -> Unit, sendWith: SendWith) {
    val car = state.snapshot
    val readings = listOfNotNull(
        car.cabinC?.let { "${stringResource(R.string.cabin)} $it°" },
        car.outsideC?.let { "${stringResource(R.string.outside)} $it°" },
    ).joinToString("  ·  ")

    SectionHeader(R.string.climate_title, readings.ifEmpty { null })
    Spacer(Modifier.height(10.dp))
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickStart(state, ClimateMode.COOL, prefs.climateMinutes, send, Modifier.weight(1f))
            QuickStart(state, ClimateMode.HEAT, prefs.climateMinutes, send, Modifier.weight(1f))
        }
        // On, or not known to be off: Stop is right there.
        AnimatedVisibility(
            visible = car.climateOn != false || CarCommand.CLIMATE_OFF in state.pending,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            CommandButton(
                command = CarCommand.CLIMATE_OFF,
                label = R.string.climate_stop,
                phase = rememberPhase(state, CarCommand.CLIMATE_OFF),
                filled = car.climateOn == true,
                onClick = { send(CarCommand.CLIMATE_OFF) },
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        RecircRow(state, prefs, sendWith)
        Spacer(Modifier.height(10.dp))
        SeatHeatRow(state, sendWith)
    }
}

private val WarmTint = Palette.Heat

/**
 * A tall one-tap start: icon, name, and what it will do ("15° · 20 min"). While its mode runs it
 * lights up and counts down; the other one switches the running A/C over.
 */
@Composable
private fun QuickStart(state: CarUiState, mode: ClimateMode, minutes: Int, send: (CarCommand) -> Unit, modifier: Modifier) {
    val heat = mode == ClimateMode.HEAT
    val command = if (heat) CarCommand.QUICK_HEAT else CarCommand.QUICK_COOL
    val temp = if (heat) CarRepository.HEAT_TEMP else CarRepository.COOL_TEMP
    val tint = if (heat) WarmTint else Palette.Accent
    val running = state.climateMode == mode
    val phase = rememberPhase(state, command)
    val busy = phase is Phase.Busy
    val blocked = !busy && state.isPending(CarCommand.CLIMATE)
    val shake = rememberShake((phase as? Phase.Failed)?.result?.id)
    val left = rememberMinutesLeft(state.snapshot.climateUntil.takeIf { running })
    val detail = phaseSubtitle(
        phase,
        when {
            running && left != null -> stringResource(R.string.climate_left, left)
            running -> stringResource(R.string.climate_running)
            else -> stringResource(R.string.quick_detail, temp, minutes)
        },
    ).orEmpty()
    val background by animateColorAsState(tint.copy(alpha = if (running || busy) 0.24f else 0.11f), tween(260), label = "quick")

    Column(
        modifier
            .graphicsLayer { translationX = shake.value * density }
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(enabled = !running && !busy && !blocked, role = Role.Button) { send(command) }
            .semantics { stateDescription = detail }
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.fillMaxSize(), color = tint, strokeWidth = 2.dp)
            Icon(painterResource(command.icon), null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(command.label), style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary, maxLines = 1)
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall.merge(Numeric),
            color = if (blocked) Palette.TextMuted else tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Whole minutes until [until] (epoch millis), ticking while on screen; null without an end.
 * Rounds up, less a few seconds of slack: the end comes from the server's clock, and a phone a
 * moment behind it would otherwise show "21 min" right after a 20-minute start.
 */
@Composable
private fun rememberMinutesLeft(until: Long?): Int? {
    val now by produceState(System.currentTimeMillis(), until) {
        while (until != null) {
            value = System.currentTimeMillis()
            delay(15_000)
        }
    }
    return until?.let { (((it - now) - 10_000 + 59_999) / 60_000).toInt().coerceAtLeast(0) }
}

/**
 * Air source. While the A/C runs it shows what the car reports, and a tap changes it in the car;
 * otherwise it is the setting for the next start.
 */
@Composable
private fun RecircRow(state: CarUiState, prefs: AppPrefs, sendWith: SendWith) {
    val car = state.snapshot
    val running = car.climateOn == true
    var chosen by remember { mutableStateOf(prefs.climateRecirculate) }
    val value = if (running) car.recirc ?: chosen else chosen
    val busy = CarCommand.RECIRC in state.pending
    val view = LocalView.current
    val tint = if (value) Palette.Accent else Palette.TextSecondary
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.SurfaceHigh)
            .toggleable(value = value, enabled = !busy, role = Role.Switch) { on ->
                chosen = on
                prefs.climateRecirculate = on
                if (running) sendWith(CarCommand.RECIRC, mapOf("on" to if (on) "1" else "0")) else view.haptic(Haptic.Tap)
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_recirc), null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.climate_recirc_long), style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
            Text(
                stringResource(if (busy) R.string.sending else if (value) R.string.air_recirc_hint else R.string.air_fresh_hint),
                style = MaterialTheme.typography.bodySmall,
                color = if (busy) Palette.Busy else Palette.TextMuted,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = value,
            onCheckedChange = null, // the whole row toggles
            enabled = !busy,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.OnAccent,
                checkedTrackColor = Palette.Accent,
                uncheckedThumbColor = Palette.TextMuted,
                uncheckedTrackColor = Palette.SurfaceTop,
                uncheckedBorderColor = Palette.Outline,
            ),
        )
    }
}

// ------------------------------------------------------------------ vehicle

@Composable
private fun VehicleSection(car: CarSnapshot) {
    if (car.odometerKm == null && car.efficiency == null && car.tires == null) return
    SectionHeader(R.string.details_title)
    Spacer(Modifier.height(10.dp))
    Panel {
        var first = true
        car.odometerKm?.let {
            InfoRow(R.drawable.ic_road, R.string.odometer, "${grouped(it)} ${stringResource(R.string.unit_km)}")
            first = false
        }
        car.efficiency?.let {
            if (!first) HorizontalDivider(color = Palette.Outline.copy(alpha = 0.7f))
            InfoRow(R.drawable.ic_gauge, R.string.consumption, "$it ${stringResource(R.string.kwh_100km)}")
            first = false
        }
        car.tires?.let {
            if (!first) HorizontalDivider(color = Palette.Outline.copy(alpha = 0.7f))
            TireRow(it)
        }
    }
}

@Composable
private fun InfoRow(icon: Int, @StringRes label: Int, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, tint = Palette.TextMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall.merge(Numeric), color = Palette.TextPrimary)
    }
}

/** One line of verdict ("Normal · 36 psi", or which wheel is low); the hero shows each axle's pair under its wheel. */
@Composable
private fun TireRow(tires: Tires) {
    val wheels = listOf(
        R.string.wheel_fl to tires.fl, R.string.wheel_fr to tires.fr,
        R.string.wheel_rl to tires.rl, R.string.wheel_rr to tires.rr,
    )
    val low = wheels.filter { (_, value) -> value != null && isLow(value, tires.unit) }.map { stringResource(it.first) }
    val known = wheels.mapNotNull { it.second }

    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_tire), null, tint = Palette.TextMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.tires), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
        Spacer(Modifier.weight(1f))
        if (low.isEmpty()) {
            Text(stringResource(R.string.tires_ok), style = MaterialTheme.typography.titleSmall, color = Palette.Ok)
            if (known.isNotEmpty()) {
                val range = if (known.min() == known.max()) fmt(known.min()) else "${fmt(known.min())}–${fmt(known.max())}"
                Text(
                    "  ·  ⁨$range ${tireUnit(tires.unit)}⁩",
                    style = MaterialTheme.typography.bodySmall.merge(Numeric),
                    color = Palette.TextMuted,
                )
            }
        } else {
            Text(
                if (low.size == 1) stringResource(R.string.tires_low, low.single()) else stringResource(R.string.tires_low_many, low.size),
                style = MaterialTheme.typography.titleSmall,
                color = Palette.Warn,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun tireUnit(unit: String): String = stringResource(
    when (unit) {
        "psi" -> R.string.unit_psi
        "kPa" -> R.string.unit_kpa
        else -> R.string.unit_bar
    },
)

internal fun isLow(value: Double, unit: String): Boolean = when (unit) {
    "psi" -> value < 29
    "kPa" -> value < 200
    else -> value < 2.0
}

internal fun fmt(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)

// ------------------------------------------------------------------ footer

@Composable
private fun Footer(state: CarUiState, prefs: AppPrefs) {
    // Timing and the server address live in Settings; the host shows here only when it matters.
    val host = remember(prefs.serverUrl) { prefs.serverUrl.substringAfter("://").substringBefore('/') }
    val parts = listOfNotNull(
        host.takeIf { state.online == false || prefs.serverUrl != BuildConfig.DEFAULT_SERVER_URL },
        stringResource(R.string.version, BuildConfig.VERSION_NAME),
    )
    Text(
        // Unicode isolates keep the host / version from being reordered inside Hebrew text.
        parts.joinToString("  ·  ") { "⁨$it⁩" },
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

// ------------------------------------------------------------------ helpers

private fun grouped(value: Int): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

private fun duration(context: Context, minutes: Int): String = when {
    minutes >= 60 && minutes % 60 == 0 -> context.getString(R.string.duration_h, minutes / 60)
    minutes >= 60 -> context.getString(R.string.duration_hm, minutes / 60, minutes % 60)
    else -> context.getString(R.string.duration_m, minutes)
}
