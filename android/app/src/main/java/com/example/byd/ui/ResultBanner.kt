package com.example.byd.ui

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.byd.R
import com.example.byd.data.CarCommand
import com.example.byd.data.CarSnapshot
import com.example.byd.data.CommandResult
import com.example.byd.ui.theme.Palette
import kotlinx.coroutines.delay

/**
 * The car's answer as one short notification at the top of the screen:
 * "Car unlocked · Confirmed by the car", or what went wrong plus Retry.
 * Buttons stay clean (they just switch state); this is where confirmation lives.
 */
@Composable
fun ResultBanner(
    result: CommandResult?,
    car: CarSnapshot,
    onRetry: (CarCommand, Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<CommandResult?>(null) }
    LaunchedEffect(result?.id) {
        val fresh = result?.takeIf { SystemClock.elapsedRealtime() - it.finishedAt < 3_000 } ?: return@LaunchedEffect
        shown = fresh
        visible = true
        delay(if (fresh.ok) 2_800 else 6_000)
        visible = false
    }

    AnimatedVisibility(
        visible = visible && shown != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        val r = shown ?: return@AnimatedVisibility
        val soft = r.error?.soft == true
        val color = if (r.ok) Palette.Ok else if (soft) Palette.Warn else Palette.Err
        val shape = RoundedCornerShape(18.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(14.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(Palette.SurfaceTop)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(r.command.icon), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (r.ok) stringResource(doneText(r.command, car)) else stringResource(r.error!!.message),
                    style = MaterialTheme.typography.titleSmall,
                    color = Palette.TextPrimary,
                    maxLines = 2,
                )
                Text(
                    when {
                        r.ok -> stringResource(R.string.confirmed)
                        soft -> stringResource(r.command.label)
                        else -> stringResource(R.string.not_done, stringResource(r.command.label))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                    maxLines = 1,
                )
            }
            // No Retry after a soft outcome: the car may already have done it.
            if (!r.ok && !soft) {
                TextButton(onClick = {
                    visible = false
                    onRetry(r.command, r.params)
                }) { Text(stringResource(R.string.retry), color = Palette.Accent) }
            }
        }
    }
}

/** The tailgate is a toggle, so its message follows where it ended up. */
@StringRes
private fun doneText(command: CarCommand, car: CarSnapshot): Int = when {
    command == CarCommand.TRUNK && car.trunkOpen == true -> R.string.done_trunk_open
    command == CarCommand.TRUNK && car.trunkOpen == false -> R.string.done_trunk_closed
    else -> command.done
}
