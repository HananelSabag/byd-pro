package com.example.byd.wear

import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Round remote for the wrist: lock, unlock, tailgate, quick cool / heat, and stop while the A/C runs. Every
 * tap goes to the BYD Pro app on the phone, which acks it at once and reports its progress and
 * answer by the tap's id (the status line at the top).
 */
class MainActivity : ComponentActivity(), DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {
    private var state by mutableStateOf(WatchState())
    private var taps by mutableStateOf(emptyList<Tap>()) // this watch's taps: open ones and the last answers
    private var phoneReachable by mutableStateOf(true)
    private var now by mutableLongStateOf(System.currentTimeMillis())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = StateCache.load(this)
        setContent {
            val busy = taps.filter { !it.done }.map { it.command }.toSet() + state.livePending(now)
            val status = taps.lastOrNull()?.takeIf { !it.done || now - it.doneAt < Taps.SHOW_MS }?.let { TapStatus.of(this, it, state) }
            WatchApp(state, busy, phoneReachable, status, ::tap)
        }
        // Timeouts (no ack, no answer) are noticed here: every second while a tap is open.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(if (taps.any { !it.done }) 1_000 else 5_000)
                    settle()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Wearable.getDataClient(this).addListener(this)
        Wearable.getMessageClient(this).addListener(this)
        settle()
        lifecycleScope.launch {
            PhoneLink.current(this@MainActivity)?.let(::show)
            phoneReachable = PhoneLink.refresh(this@MainActivity) // the phone reads the car unless it just did
        }
    }

    override fun onStop() {
        Wearable.getDataClient(this).removeListener(this)
        Wearable.getMessageClient(this).removeListener(this)
        super.onStop()
    }

    /** The phone's "got it" for a tap (StateListener sees it too; whoever is first buzzes, once). */
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PhoneLink.PATH_ACK) return
        val parts = String(event.data).split('|')
        Taps.ack(this, parts.firstOrNull().orEmpty(), parts.getOrNull(1).orEmpty())?.let { Buzz.settled(this, listOf(it)) }
        phoneReachable = true
        settle()
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.lastOrNull { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == PhoneLink.PATH_STATE }
            ?.let { DataMapItem.fromDataItem(it.dataItem).dataMap }
            ?.let { map ->
                StateCache.save(this, map)
                show(map)
            }
    }

    private fun show(map: DataMap) {
        state = WatchState.from(map)
        phoneReachable = true
        settle()
    }

    private fun settle() {
        Buzz.settled(this, Taps.settle(this, state))
        taps = Taps.all(this)
        now = System.currentTimeMillis()
    }

    private fun tap(command: String) {
        Buzz.tap(this)
        // A phone known to run 2.7 gets the old message; anything else the 2.8 one (an old phone
        // ignores it: no ack comes, and the tap says so after a few seconds instead of hanging).
        val legacy = state.hasData && state.proto < 2
        val tap = Taps.start(this, command, legacy)
        taps = Taps.all(this)
        lifecycleScope.launch {
            if (!PhoneLink.command(this@MainActivity, tap)) {
                phoneReachable = false
                Taps.notSent(this@MainActivity, tap.id)?.let { Buzz.settled(this@MainActivity, listOf(it)) }
            }
            settle()
        }
    }
}

// ------------------------------------------------------------------ colors

private val Accent = Color(0xFF5AB0FF)
private val Ok = Color(0xFF5AB0FF)
private val Warn = Color(0xFFFF6B6B)
private val Warm = Color(0xFFFF8A4C)
private val Err = Color(0xFFFF6B6B)
private val Busy = Color(0xFFD6DEE8) // a command on its way: neutral, never a state colour
private val Soft = Color(0xFFFFB648) // sent but not confirmed yet
private val Surface = Color(0xFF1B232D)
private val TextPrimary = Color(0xFFF2F5F9)
private val TextMuted = Color(0xFF8994A5)

// ------------------------------------------------------------------ screen

@Composable
private fun WatchApp(
    state: WatchState,
    busy: Set<String>,
    phoneReachable: Boolean,
    status: TapStatus?,
    onTap: (String) -> Unit,
) {
    MaterialTheme {
        AppScaffold(timeText = { TimeText() }) {
            // Start with the car picture just under the clock and Lock / Unlock in full view.
            val listState = rememberScalingLazyListState(initialCenterItemIndex = 0, initialCenterItemScrollOffset = HEADER_OFFSET)
            ScreenScaffold(scrollState = listState) { padding ->
                ScalingLazyColumn(
                    state = listState,
                    contentPadding = padding,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                ) {
                    if (status != null) item { StatusLine(status) }
                    item { Header(state) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // One lock button, like on / off: the opposite of the last known state; unknown locks.
                            val unlocked = state.locked == false
                            RoundAction(
                                if (unlocked) R.drawable.ic_unlock else R.drawable.ic_lock,
                                if (unlocked) R.string.unlocked else R.string.locked,
                                if (state.locked == true) Ok else Warn,
                                state.locked != null,
                                PhoneLink.LOCK in busy || PhoneLink.UNLOCK in busy,
                            ) { onTap(if (state.locked == true) PhoneLink.UNLOCK else PhoneLink.LOCK) }
                        }
                    }
                    item {
                        val cooling = state.climateOn == true && state.heating == false
                        val heating = state.climateOn == true && state.heating == true
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RoundAction(R.drawable.ic_snowflake, R.string.cool, Accent, cooling, PhoneLink.QUICK_COOL in busy) {
                                onTap(if (cooling) PhoneLink.CLIMATE_OFF else PhoneLink.QUICK_COOL)
                            }
                            RoundAction(R.drawable.ic_flame, R.string.heat, Warm, heating, PhoneLink.QUICK_HEAT in busy) {
                                onTap(if (heating) PhoneLink.CLIMATE_OFF else PhoneLink.QUICK_HEAT)
                            }
                        }
                    }
                    item {
                        RoundAction(R.drawable.ic_trunk, R.string.tailgate, Warn, state.trunkOpen == true, PhoneLink.TRUNK in busy) {
                            onTap(PhoneLink.TRUNK)
                        }
                    }
                    if (state.climateOn != false || PhoneLink.CLIMATE_OFF in busy) {
                        item { StopButton(state, PhoneLink.CLIMATE_OFF in busy) { onTap(PhoneLink.CLIMATE_OFF) } }
                    }
                    item { Footer(state, phoneReachable) }
                }
            }
        }
    }
}

@Composable
private fun Header(state: WatchState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.car_silhouette), contentDescription = state.name, modifier = Modifier.width(96.dp))
        Spacer(Modifier.height(4.dp))
        val headline = when {
            state.soc != null && state.rangeKm != null -> stringResource(R.string.percent_range, state.soc, state.rangeKm)
            state.soc != null -> stringResource(R.string.percent, state.soc)
            else -> "--"
        }
        Text(headline, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        val (status, color) = when {
            state.charging == true && state.chargeKw > 0 -> stringResource(R.string.charging_kw, state.chargeKw) to Ok
            state.charging == true -> stringResource(R.string.charging) to Ok
            state.trunkOpen == true -> stringResource(R.string.tailgate_open) to Warn
            state.locked == true -> stringResource(R.string.locked) to Ok
            state.locked == false -> stringResource(R.string.unlocked) to Warn
            else -> null to TextMuted
        }
        if (status != null) Text(status, fontSize = 13.sp, color = color)
    }
}

/** A big round button: icon in a disc, its name under it. Lit while its state is on, spinning while sent. */
@Composable
private fun RoundAction(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    tint: Color,
    active: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val name = stringResource(label)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(if (active) tint.copy(alpha = 0.28f) else Surface)
                .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = name },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) Spinner(tint)
            Image(
                painterResource(icon), null,
                colorFilter = ColorFilter.tint(if (active || busy) tint else TextPrimary),
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(name, fontSize = 12.sp, color = if (active) tint else TextMuted)
    }
}

/** Stop while the A/C runs, with what it is doing and how long it has left. */
@Composable
private fun StopButton(state: WatchState, busy: Boolean, onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.climateUntil) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val left = state.minutesLeft(now)
    val detail = when {
        busy -> stringResource(R.string.sending)
        left != null && state.heating == true -> stringResource(R.string.heating_left, left)
        left != null && state.heating == false -> stringResource(R.string.cooling_left, left)
        state.climateOn == true -> stringResource(R.string.climate_on)
        else -> null
    }
    val tint = if (state.heating == true) Warm else Accent
    Row(
        Modifier
            .fillMaxWidth(0.82f)
            .clip(RoundedCornerShape(26.dp))
            .background(Surface)
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (busy) Spinner(tint)
            Image(painterResource(R.drawable.ic_power), null, colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(stringResource(R.string.stop_climate), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            if (detail != null) Text(detail, fontSize = 12.sp, color = tint)
        }
    }
}

/** The newest tap: its step while the phone works on it, then its answer for a few seconds. */
@Composable
private fun StatusLine(status: TapStatus) {
    Text(
        status.text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = toneColor(status.tone),
        textAlign = TextAlign.Center,
        maxLines = 3,
        modifier = Modifier.padding(horizontal = 22.dp),
    )
}

/** The watch's colour for the phone's outcome tones (the tile has the same map). Recolour here. */
private fun toneColor(tone: String): Color = when (tone) {
    "OK" -> Ok
    "WARN" -> Soft
    "ERR" -> Err
    "BUSY" -> Busy
    else -> TextMuted
}

@Composable
private fun Footer(state: WatchState, phoneReachable: Boolean) {
    val (text, color) = when {
        !phoneReachable -> stringResource(R.string.no_phone) to Err
        !state.hasData -> stringResource(R.string.no_data) to TextMuted
        state.updatedAt > 0 -> stringResource(R.string.updated, DateFormat.getTimeFormat(LocalContext.current).format(Date(state.updatedAt))) to TextMuted
        else -> null to TextMuted
    }
    if (text != null) {
        Text(text, fontSize = 12.sp, color = color, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
    }
}

/** A thin arc turning around a button while its command is on its way. */
@Composable
private fun Spinner(color: Color) {
    val turn by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "turn",
    )
    Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = turn }) {
        val stroke = 2.5.dp.toPx()
        drawArc(
            color = color, startAngle = 0f, sweepAngle = 100f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** How far the first screen is scrolled: the header's centre sits this many px above the screen's. */
private const val HEADER_OFFSET = 100
