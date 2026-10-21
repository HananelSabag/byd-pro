package com.example.byd.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.byd.R
import com.example.byd.data.Tires
import com.example.byd.ui.theme.Numeric
import com.example.byd.ui.theme.Palette
import java.util.Locale
import kotlin.math.roundToInt

// Wheel centres inside car_top.webp (432 x 578, front of the car at the top): FL FR RL RR.
private val WHEELS = listOf(Offset(95f, 140f), Offset(340f, 140f), Offset(95f, 435f), Offset(340f, 435f))

/**
 * The car from above with each tyre's pressure beside its wheel, green when fine, amber when low.
 * Left stays left in Hebrew too: it's a physical picture.
 */
@Composable
fun TireDiagram(tires: Tires, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        TireDiagramContent(tires, modifier)
    }
}

@Composable
private fun TireDiagramContent(tires: Tires, modifier: Modifier) {
    val image = ImageBitmap.imageResource(R.drawable.car_top)
    Layout(
        modifier = modifier,
        content = {
            Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                val g = topGeometry(size, image)
                // Leader lines from each badge to its wheel.
                WHEELS.forEachIndexed { i, wheel ->
                    val p = g.at(wheel)
                    val toward = if (i % 2 == 0) -1f else 1f
                    drawLine(Palette.Accent.copy(alpha = 0.4f), p, Offset(p.x + toward * 16.dp.toPx(), p.y), 1.2.dp.toPx())
                    drawCircle(Palette.Accent, 2.6.dp.toPx(), p)
                }
                drawImage(image, dstOffset = g.dstOffset, dstSize = g.dstSize)
            }
            TireBadge(R.string.wheel_fl, tires.fl, tires.unit)
            TireBadge(R.string.wheel_fr, tires.fr, tires.unit)
            TireBadge(R.string.wheel_rl, tires.rl, tires.unit)
            TireBadge(R.string.wheel_rr, tires.rr, tires.unit)
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val canvas = measurables[0].measure(constraints)
        val badges = measurables.drop(1).map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val g = topGeometry(Size(width.toFloat(), height.toFloat()), image)
        val gap = 20.dp.roundToPx()
        layout(width, height) {
            canvas.place(0, 0)
            badges.forEachIndexed { i, badge ->
                val wheel = g.at(WHEELS[i])
                val x = if (i % 2 == 0) wheel.x.roundToInt() - gap - badge.width else wheel.x.roundToInt() + gap
                badge.place(x.coerceIn(0, width - badge.width), (wheel.y - badge.height / 2f).roundToInt().coerceIn(0, height - badge.height))
            }
        }
    }
}

@Composable
private fun TireBadge(@StringRes label: Int, value: Double?, unit: String) {
    val low = value != null && when (unit) {
        "psi" -> value < 29
        "kPa" -> value < 200
        else -> value < 2.0
    }
    val tone = if (low) Palette.Warn else Palette.Ok
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.SurfaceHigh)
            .border(1.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(tone))
            Spacer(Modifier.width(5.dp))
            // "front left" reads as a sentence fragment elsewhere; as a badge title it starts upper-case.
            Text(
                stringResource(label).replaceFirstChar { it.titlecase() },
                style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary, maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value?.let { if (it % 1.0 == 0.0) it.toInt().toString() else String.format(Locale.ROOT, "%.1f", it) } ?: "--",
                style = MaterialTheme.typography.titleMedium.merge(Numeric),
                color = Palette.TextPrimary,
            )
            Spacer(Modifier.width(3.dp))
            Text(tireUnit(unit), style = MaterialTheme.typography.labelSmall, color = tone, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

private class TopGeometry(val dstOffset: IntOffset, val dstSize: IntSize, val scale: Float) {
    fun at(p: Offset) = Offset(dstOffset.x + p.x * scale, dstOffset.y + p.y * scale)
}

/** The car fills the height, centred. */
private fun topGeometry(size: Size, image: ImageBitmap): TopGeometry {
    val scale = size.height / image.height
    val w = (image.width * scale).roundToInt()
    val h = (image.height * scale).roundToInt()
    return TopGeometry(IntOffset(((size.width - w) / 2f).roundToInt(), 0), IntSize(w, h), scale)
}
