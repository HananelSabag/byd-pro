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
import com.example.byd.R
import com.example.byd.data.CarCommand
import com.example.byd.data.CarRepository
import com.example.byd.data.CarUiState
import com.example.byd.data.CommandResult
import com.example.byd.ui.theme.Numeric
import com.example.byd.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ------------------------------------------------------------------ command phase

/** What a command control should show right now. */
sealed interface Phase {
    data object Idle : Phase
    data class Busy(val since: Long) : Phase
    data class Done(val result: CommandResult) : Phase
    data class Failed(val result: CommandResult) : Phase
}

@Composable
fun rememberPhase(state: CarUiState, vararg commands: CarCommand): Phase {
    val since = commands.mapNotNull { state.pending[it] }.minOrNull()
    val result = state.lastResult?.takeIf { it.command in commands }
    var showResult by remember(result?.id) {
        mutableStateOf(result != null && SystemClock.elapsedRealtime() - result.finishedAt < CarRepository.RESULT_SHOW_MS)
    }
    LaunchedEffect(result?.id) {
        if (result != null) {
            delay(CarRepository.RESULT_SHOW_MS - (SystemClock.elapsedRealtime() - result.finishedAt))
            showResult = false
        }
    }
    return when {
        since != null -> Phase.Busy(since)
        result != null && showResult -> when {
            result.ok -> Phase.Done(result)
            result.error?.soft == true -> Phase.Idle // e.g. "not confirmed yet": no shake, the banner explains
            else -> Phase.Failed(result)
        }
        else -> Phase.Idle
    }
}

/** Button subtitle: "Sending…" while the car works on it, otherwise the control's own state text. */
@Composable
fun phaseSubtitle(phase: Phase, idle: String?): String? =
    if (phase is Phase.Busy) stringResource(R.string.sending) else idle

fun phaseColor(phase: Phase, active: Boolean, activeColor: Color): Color = when {
    phase is Phase.Busy -> Palette.Accent
    active -> activeColor
    else -> Palette.TextPrimary
}

/** A short horizontal shake, played once per failed result. */
@Composable
fun rememberShake(trigger: Long?): Animatable<Float, *> {
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
 * Lock / unlock as one two-sided switch. The lit thumb sits on the car's state and slides to the
 * other side the moment a command is sent, so the control shows both "where it is" and "where
 * it's going". In RTL the sides mirror with the layout (offset is direction-aware).
 */
@Composable
fun LockSwitch(state: CarUiState, onCommand: (CarCommand) -> Unit, modifier: Modifier = Modifier) {
    val lockPhase = rememberPhase(state, CarCommand.LOCK)
    val unlockPhase = rememberPhase(state, CarCommand.UNLOCK)
    val locked = state.snapshot.locked
    val side = when {
        lockPhase is Phase.Busy -> 0
        unlockPhase is Phase.Busy -> 1
        locked == true -> 0
        locked == false -> 1
        else -> null
    }
    val busy = lockPhase is Phase.Busy || unlockPhase is Phase.Busy
    val failed = (lockPhase as? Phase.Failed)?.result?.id ?: (unlockPhase as? Phase.Failed)?.result?.id
    val shake = rememberShake(failed)
    val position by animateFloatAsState(
        (side ?: 0).toFloat(), spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow), label = "thumb",
    )
    val thumb by animateColorAsState(
        when {
            side == null -> Color.Transparent
            busy -> Palette.Accent
            side == 0 -> Palette.Ok
            else -> Palette.Warn
        },
        tween(240), label = "thumbColor",
    )
    val outer = RoundedCornerShape(22.dp)
    val inner = RoundedCornerShape(17.dp)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(78.dp)
            .graphicsLayer { translationX = shake.value * density }
            .clip(outer)
            .background(Palette.Surface)
            .border(1.dp, Palette.Outline, outer)
            .padding(5.dp),
    ) {
        val half = maxWidth / 2
        Box(
            Modifier
                .width(half)
                .fillMaxHeight()
                .offset { IntOffset((position * half.toPx()).roundToInt(), 0) }
                .clip(inner)
                .background(thumb.copy(alpha = 0.15f))
                .border(1.dp, thumb.copy(alpha = 0.55f), inner),
        )
        Row(Modifier.fillMaxSize()) {
            LockHalf(
                CarCommand.LOCK, lockPhase, active = locked == true, activeColor = Palette.Ok,
                idle = if (locked == true) stringResource(R.string.state_locked) else null,
            ) { onCommand(CarCommand.LOCK) }
            LockHalf(
                CarCommand.UNLOCK, unlockPhase, active = locked == false, activeColor = Palette.Warn,
                idle = if (locked == false) stringResource(R.string.state_unlocked) else null,
            ) { onCommand(CarCommand.UNLOCK) }
        }
    }
}

@Composable
private fun RowScope.LockHalf(
    command: CarCommand,
    phase: Phase,
    active: Boolean,
    activeColor: Color,
    idle: String?,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(phaseColor(phase, active, activeColor), tween(220), label = "tint")
    val subtitle = phaseSubtitle(phase, idle)
    Row(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(17.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (subtitle != null) stateDescription = subtitle }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            if (phase is Phase.Busy) CircularProgressIndicator(Modifier.fillMaxSize(), color = Palette.Accent, strokeWidth = 2.dp)
            PhaseIcon(command.icon, tint, 22)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(stringResource(command.label), style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary, maxLines = 1)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.merge(Numeric),
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
