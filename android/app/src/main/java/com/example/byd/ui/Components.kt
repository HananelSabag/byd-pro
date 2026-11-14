package com.example.byd.ui

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableLongStateOf
import com.example.byd.R
import com.example.byd.bus.CommandRecord
import com.example.byd.bus.Display
import com.example.byd.bus.Outcomes
import com.example.byd.bus.Tone
import com.example.byd.data.CarCommand
import com.example.byd.data.CarUiState
import com.example.byd.ui.theme.Numeric
import com.example.byd.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ------------------------------------------------------------------ command phase

/** What a command control should show right now (from the shared table in [Outcomes]). */
sealed interface Phase {
    data object Idle : Phase
    data class Busy(val since: Long, val display: Display) : Phase
    data class Done(val result: CommandRecord) : Phase
    data class Failed(val result: CommandRecord) : Phase
}

@Composable
fun rememberPhase(state: CarUiState, vararg commands: CarCommand): Phase {
    val record = state.latest(commands.toList())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(record?.id, record?.finishedAt) {
        val finished = record?.finishedAt ?: return@LaunchedEffect
        val left = Outcomes.SHOW_MS - (System.currentTimeMillis() - finished)
        if (left > 0) delay(left + 20)
        now = System.currentTimeMillis() // the answer's moment is over: draw the normal state
    }
    record ?: return Phase.Idle
    if (!record.terminal) return Phase.Busy(record.createdAt, Outcomes.of(record))
    if (!Outcomes.showing(record, maxOf(now, System.currentTimeMillis()))) return Phase.Idle
    return when (Outcomes.of(record).tone) {
        Tone.OK -> Phase.Done(record)
        Tone.ERR -> Phase.Failed(record)
        else -> Phase.Idle // e.g. "not confirmed": no shake, the banner explains
    }
}

/** Button subtitle: the command's step ("Waking server…", "Sending…") while busy, otherwise the control's own state text. */
@Composable
fun phaseSubtitle(phase: Phase, idle: String?): String? =
    if (phase is Phase.Busy) stringResource(phase.display.label) else idle

/** Compose's colour for an outcome tone (widgets: Outcomes.colorRes). Recolour here. */
fun toneColor(tone: Tone): Color = when (tone) {
    Tone.IDLE -> Palette.TextPrimary
    Tone.BUSY -> Palette.Busy
    Tone.OK -> Palette.Ok
    Tone.WARN -> Palette.Soft
    Tone.ERR -> Palette.Err
}

fun phaseColor(phase: Phase, active: Boolean, activeColor: Color): Color = when {
    phase is Phase.Busy -> Palette.Accent
    active -> activeColor
    else -> Palette.TextPrimary
}

/** A short horizontal shake, played once per failed result. */
@Composable
fun rememberShake(trigger: Any?): Animatable<Float, *> {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger != null) {
            shake.snapTo(0f)
            shake.animateTo(0f, keyframes {
                durationMillis = 360
                -12f at 50
                10f at 110
                -7f at 170
                4f at 240
                0f at 320
            })
        }
    }
    return shake
}

// ------------------------------------------------------------------ haptics

enum class Haptic { Tap, Confirm, Reject }

fun View.haptic(type: Haptic) {
    val constant = when (type) {
        Haptic.Tap -> HapticFeedbackConstants.VIRTUAL_KEY
        Haptic.Confirm -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        Haptic.Reject -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    }
    performHapticFeedback(constant)
}

// ------------------------------------------------------------------ building blocks

/** One tonal surface for a group of controls. Elevation is the tone alone: no border, no shadow. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Surface)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun SectionHeader(@StringRes title: Int, trailing: String? = null, trailingColor: Color = Palette.TextMuted) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary)
        Spacer(Modifier.width(12.dp))
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.bodySmall.merge(Numeric), color = trailingColor, maxLines = 1)
        }
    }
}

@Composable
fun StatusChip(@DrawableRes icon: Int, text: String, color: Color) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun PhaseIcon(@DrawableRes icon: Int, tint: Color, size: Int) {
    AnimatedContent(
        targetState = icon,
        transitionSpec = { (scaleIn(initialScale = 0.7f) + fadeIn()) togetherWith fadeOut() },
        label = "icon",
    ) { res ->
        Icon(painterResource(res), contentDescription = null, tint = tint, modifier = Modifier.size(size.dp))
    }
}

// ------------------------------------------------------------------ lock switch

/**
 * One lock button, like on / off: it shows the car's last known state (blue locked, red unlocked)
 * and a tap sends the opposite. With the state unknown it locks, the safe side.
 */
@Composable
fun LockSwitch(state: CarUiState, onCommand: (CarCommand) -> Unit, modifier: Modifier = Modifier) {
    val phase = rememberPhase(state, CarCommand.LOCK, CarCommand.UNLOCK)
    val locked = state.snapshot.locked
    val next = if (locked == true) CarCommand.UNLOCK else CarCommand.LOCK
    val busy = phase is Phase.Busy
    val shake = rememberShake((phase as? Phase.Failed)?.result?.id)
    val tint by animateColorAsState(
        when {
            busy -> Palette.Busy
            locked == true -> Palette.Ok
            locked == false -> Palette.Warn
            else -> Palette.TextMuted
        },
        tween(240), label = "lockTint",
    )
    val thumb by animateFloatAsState(if (locked == true) 1f else 0f, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow), label = "thumb")
    val title = stringResource(
        when (locked) {
            true -> R.string.state_locked
            false -> R.string.state_unlocked
            null -> R.string.lock_unknown
        },
    )
    val hint = phaseSubtitle(phase, stringResource(if (next == CarCommand.LOCK) R.string.tap_to_lock else R.string.tap_to_unlock))
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(78.dp)
            .graphicsLayer { translationX = shake.value * density }
            .clip(shape)
            .background(Palette.Surface)
            .background(tint.copy(alpha = 0.08f))
            .border(1.5.dp, tint.copy(alpha = 0.5f), shape)
            .clickable(enabled = !busy, role = Role.Switch) { onCommand(next) }
            .semantics { stateDescription = title }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.fillMaxSize(), color = Palette.Busy, strokeWidth = 2.dp)
            PhaseIcon(if (locked == false) R.drawable.ic_unlock else R.drawable.ic_lock, tint, 24)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary, maxLines = 1)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall.merge(Numeric), color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // The on / off track: thumb at the end while locked.
        BoxWithConstraints(Modifier.width(54.dp).height(30.dp).clip(RoundedCornerShape(15.dp)).background(tint.copy(alpha = 0.22f)).padding(3.dp)) {
            Box(
                Modifier
                    .size(24.dp)
                    .offset { IntOffset(((maxWidth - 24.dp).toPx() * thumb).roundToInt(), 0) }
                    .clip(CircleShape)
                    .background(tint),
            )
        }
    }
}

// ------------------------------------------------------------------ round actions

/** Circular quick action (tailgate, find car, …): icon in a disc, label and live state under it. */
@Composable
fun RoundAction(
    command: CarCommand,
    phase: Phase,
    active: Boolean,
    activeColor: Color,
    status: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes label: Int = command.label,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(110), label = "press")
    val shake = rememberShake((phase as? Phase.Failed)?.result?.id)
    val tint by animateColorAsState(phaseColor(phase, active, activeColor), tween(220), label = "tint")
    val lit = active || phase is Phase.Busy
    val subtitle = phaseSubtitle(phase, status)

    // The whole column (disc + label) is the touch target; the ripple stays on the disc.
    Column(
        modifier
            .graphicsLayer { translationX = shake.value * density }
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { if (subtitle != null) stateDescription = subtitle },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(66.dp), contentAlignment = Alignment.Center) {
            if (phase is Phase.Busy) {
                CircularProgressIndicator(Modifier.fillMaxSize(), color = Palette.Accent, strokeWidth = 2.5.dp)
            }
            Box(
                Modifier
                    .size(58.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape)
                    .background(if (lit) tint.copy(alpha = 0.14f) else Palette.SurfaceHigh)
                    .border(1.dp, if (lit) tint.copy(alpha = 0.5f) else Palette.Outline, CircleShape)
                    .indication(source, ripple(color = tint)),
                contentAlignment = Alignment.Center,
            ) {
                PhaseIcon(command.icon, tint, 24)
            }
        }
        Spacer(Modifier.height(6.dp))
        // Up to two lines: a long label ("Close windows") wraps instead of being cut off.
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelLarge.copy(lineHeight = 17.sp),
            color = Palette.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            // Side padding keeps neighbouring labels from reading as one phrase.
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Text(
            subtitle ?: " ",
            style = MaterialTheme.typography.labelSmall.merge(Numeric),
            color = if (phase is Phase.Busy) Palette.Accent else if (active) activeColor else Palette.TextMuted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------ command button

/** Wide button that runs a command: "Sending…" while busy, then it simply shows the next action. */
@Composable
fun CommandButton(
    command: CarCommand,
    @StringRes label: Int,
    phase: Phase,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null, // overrides [label] when the text needs a value, e.g. "Update to 23°"
    enabled: Boolean = true,
) {
    val shake = rememberShake((phase as? Phase.Failed)?.result?.id)
    val container by animateColorAsState(
        when {
            phase is Phase.Busy -> Palette.Accent.copy(alpha = 0.14f)
            !enabled -> Palette.SurfaceHigh
            filled -> Palette.Accent
            else -> Palette.SurfaceTop
        },
        tween(220), label = "container",
    )
    val content = when {
        phase is Phase.Busy -> Palette.Accent
        !enabled -> Palette.TextMuted
        filled -> Palette.OnAccent
        else -> Palette.TextPrimary
    }
    val shown = phaseSubtitle(phase, null) ?: text ?: stringResource(label)
    Row(
        modifier
            .height(54.dp)
            .graphicsLayer { translationX = shake.value * density }
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (phase is Phase.Busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Accent, strokeWidth = 2.dp)
        } else {
            PhaseIcon(command.icon, content, 20)
        }
        Spacer(Modifier.width(10.dp))
        Text(shown, style = MaterialTheme.typography.labelLarge.merge(Numeric), color = content, maxLines = 1)
    }
}
