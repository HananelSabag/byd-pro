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
import android.os.SystemClock
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
import com.example.byd.data.AppPrefs
import com.example.byd.data.CarCommand
import com.example.byd.data.CarRepository
import com.example.byd.data.CarSnapshot
import com.example.byd.data.CarUiState
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
    var climateTemp by remember { mutableIntStateOf(repo.prefs.climateTemp) }
    var heroOnScreen by remember { mutableStateOf(true) }
    var pulling by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Re-read the car whenever the app comes to the front. The server answers from its cache
    // when the data is under a minute old, so this only wakes the car when it is really needed.
    LifecycleStartEffect(Unit) {
        repo.refreshAsync()
        onStopOrDispose { }
    }

    // The car's answer: a confirm / reject haptic here, the message itself in ResultBanner.
    LaunchedEffect(state.lastResult?.id) {
        val result = state.lastResult ?: return@LaunchedEffect
        if (SystemClock.elapsedRealtime() - result.finishedAt > 3_000) return@LaunchedEffect
        if (repo.prefs.haptics) {
            view.haptic(
                when {
                    result.ok -> Haptic.Confirm
                    result.error?.soft == true -> Haptic.Tap
                    else -> Haptic.Reject
                },
            )
        }
    }

    val send: (CarCommand) -> Unit = { command ->
        if (repo.prefs.haptics) view.haptic(Haptic.Tap)
        repo.sendAsync(command)
    }
    val sendWith: SendWith = { command, params ->
        if (repo.prefs.haptics) view.haptic(Haptic.Tap)
        repo.sendAsync(command, params)
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
                        warmAir = warmAir(state, climateTemp),
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
                    ClimateSection(state, climateTemp, { climateTemp = it; repo.prefs.climateTemp = it }, repo.prefs, send, sendWith)
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
    val result = state.lastResult?.takeIf { it.ok && SystemClock.elapsedRealtime() - it.finishedAt < 3_000 } ?: return null
    return when (result.command) {
        CarCommand.LOCK -> result.id to HeroFx.LOCKED
        CarCommand.UNLOCK -> result.id to HeroFx.UNLOCKED
        CarCommand.FLASH -> result.id to HeroFx.FLASH
        CarCommand.LOCATE -> result.id to HeroFx.FIND
        else -> null
    }
}

/**
 * Heating or cooling: the temperature the A/C is running at (or the one chosen, before a start)
 * against the cabin's; a mild default when the cabin temperature is unknown.
 */
private fun warmAir(state: CarUiState, chosen: Int): Boolean {
    val target = if (state.snapshot.climateOn == true) state.climateSentTemp ?: chosen else chosen
    return state.snapshot.cabinC?.let { target > it } ?: (target >= 24)
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

/** Shown only when a command has been in flight for a while (sleeping car / cold server). */
@Composable
private fun WakingBanner(state: CarUiState) {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(state.pending) {
        slow = false
        val oldest = state.pending.values.minOrNull() ?: return@LaunchedEffect
        delay((8_000 - (SystemClock.elapsedRealtime() - oldest)).coerceAtLeast(0))
        slow = true
    }
    AnimatedVisibility(slow && state.pending.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
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
            Text(stringResource(R.string.slow_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextPrimary)
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

private val DURATIONS = listOf(10, 15, 20, 25, 30)

@Composable
private fun ClimateSection(
    state: CarUiState,
    temp: Int,
    onTemp: (Int) -> Unit,
    prefs: AppPrefs,
    send: (CarCommand) -> Unit,
    sendWith: SendWith,
) {
    val car = state.snapshot
    val on = car.climateOn == true
    // While the A/C runs, a new temperature is a draft: it reaches the car only with "Update".
    val draft = on && state.climateSentTemp != null && temp != state.climateSentTemp
    var minutes by remember { mutableIntStateOf(prefs.climateMinutes) }
    val view = LocalView.current
    val readings = listOfNotNull(
        car.cabinC?.let { "${stringResource(R.string.cabin)} $it°" },
        car.outsideC?.let { "${stringResource(R.string.outside)} $it°" },
    ).joinToString("  ·  ")

    SectionHeader(R.string.climate_title, readings.ifEmpty { null })
    Spacer(Modifier.height(10.dp))
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(R.drawable.ic_minus, R.string.temp_down, enabled = temp > MIN_TEMP) {
                onTemp(temp - 1)
                view.haptic(Haptic.Tap)
            }
            Text(
                "$temp°",
                style = MaterialTheme.typography.displaySmall.merge(Numeric),
                color = if (on && !draft) Palette.Accent else Palette.TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            StepButton(R.drawable.ic_plus, R.string.temp_up, enabled = temp < MAX_TEMP) {
                onTemp(temp + 1)
                view.haptic(Haptic.Tap)
            }
        }
        Spacer(Modifier.height(12.dp))
        QuickClimate(state, prefs) { target, air ->
            onTemp(target)
            sendWith(CarCommand.CLIMATE_ON, mapOf("temp" to "$target", "air" to air))
        }
        Spacer(Modifier.height(10.dp))
        SeatHeatRow(state, sendWith)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            DurationPicker(minutes) {
                minutes = it
                prefs.climateMinutes = it
            }
            val phase = rememberPhase(state, CarCommand.CLIMATE_ON, CarCommand.CLIMATE_OFF)
            if (draft) {
                CommandButton(
                    command = CarCommand.CLIMATE_ON,
                    label = R.string.cmd_climate_on,
                    text = stringResource(R.string.climate_update, temp),
                    phase = phase,
                    filled = true,
                    onClick = { send(CarCommand.CLIMATE_ON) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                CommandButton(
                    command = if (on) CarCommand.CLIMATE_OFF else CarCommand.CLIMATE_ON,
                    label = if (on) R.string.climate_stop else R.string.cmd_climate_on,
                    phase = phase,
                    filled = !on,
                    onClick = { send(if (on) CarCommand.CLIMATE_OFF else CarCommand.CLIMATE_ON) },
                    modifier = Modifier.weight(1f),
                )
            }
            // State unknown, or the main button is "Update": keep a way to switch it off.
            if (car.climateOn == null || draft) {
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Palette.SurfaceTop)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.climate_stop)) {
                            send(CarCommand.CLIMATE_OFF)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_power), stringResource(R.string.climate_stop), tint = Palette.TextSecondary)
                }
            }
        }
    }
}

/** The car's range on its own scale (15-31 °C on the ATTO 3). */
private const val MIN_TEMP = 15
private const val MAX_TEMP = 31

/**
 * One-tap starts at the ends of the range, like the car's own app, plus the air source.
 * Cooling recirculates (fastest); heating takes outside air, which keeps the windows clear.
 */
@Composable
private fun QuickClimate(state: CarUiState, prefs: AppPrefs, start: (Int, String) -> Unit) {
    var recirculate by remember { mutableStateOf(prefs.climateRecirculate) }
    val view = LocalView.current
    val busy = state.pending.containsKey(CarCommand.CLIMATE_ON)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickChip(R.drawable.ic_snowflake, R.string.climate_rapid_cool, Palette.Accent, enabled = !busy, Modifier.weight(1f)) {
            start(MIN_TEMP, "recirc")
        }
        QuickChip(R.drawable.ic_flame, R.string.climate_rapid_heat, WarmTint, enabled = !busy, Modifier.weight(1f)) {
            start(MAX_TEMP, "fresh")
        }
        Row(
            Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (recirculate) Palette.Accent.copy(alpha = 0.14f) else Palette.SurfaceHigh)
                .toggleable(value = recirculate, role = Role.Switch) {
                    recirculate = it
                    prefs.climateRecirculate = it
                    view.haptic(Haptic.Tap)
                }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val tint = if (recirculate) Palette.Accent else Palette.TextSecondary
            Icon(painterResource(R.drawable.ic_recirc), null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.climate_recirc), style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
        }
    }
}

private val WarmTint = Color(0xFFFF8A4C)

@Composable
private fun QuickChip(icon: Int, @StringRes label: Int, tint: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.12f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

@Composable
private fun StepButton(icon: Int, @StringRes label: Int, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Palette.SurfaceHigh)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(label), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon), stringResource(label),
            tint = if (enabled) Palette.TextPrimary else Palette.TextMuted.copy(alpha = 0.4f),
        )
    }
}

/** Run time as one compact chip with a menu, instead of five chips competing for the row. */
@Composable
private fun DurationPicker(minutes: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .height(54.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.SurfaceHigh)
                .clickable(role = Role.DropdownList, onClickLabel = stringResource(R.string.climate_duration)) { open = true }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_clock), null, tint = Palette.TextSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.minutes_short, minutes), style = MaterialTheme.typography.labelLarge.merge(Numeric), color = Palette.TextPrimary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.SurfaceTop) {
            DURATIONS.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.minutes_short, option),
                            color = if (option == minutes) Palette.Accent else Palette.TextPrimary,
                        )
                    },
                    onClick = {
                        onPick(option)
                        open = false
                    },
                )
            }
        }
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
            TireDiagram(it, Modifier.fillMaxWidth().height(230.dp).padding(bottom = 6.dp))
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

/** One line of verdict ("Normal · 36 psi", or which wheel is low); the diagram below has all four. */
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

private fun isLow(value: Double, unit: String): Boolean = when (unit) {
    "psi" -> value < 29
    "kPa" -> value < 200
    else -> value < 2.0
}

private fun fmt(value: Double): String =
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
