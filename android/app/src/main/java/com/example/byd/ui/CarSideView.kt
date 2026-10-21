package com.example.byd.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.byd.R
import com.example.byd.data.CarSnapshot
import com.example.byd.ui.theme.Palette
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/** A short-lived effect from a command the car just confirmed. */
enum class HeroFx { LOCKED, UNLOCKED, FLASH, FIND }

/**
 * The ATTO 3 from the side, drawn in code and split into moving parts, so the picture is the state:
 * doors swing open toward you, glass drops into the door, the tailgate lifts and lights the cargo
 * bay, the mirrors fold while locked, air streams through the cabin (blue cooling, orange heating),
 * the heated seat glows, and the battery under the floor fills while plugged in.
 * Lights fire only for the lights and find-car commands.
 *
 * Everything is drawn on a 680 x 300 artboard (the design mock's coordinates). Each part is its own
 * layer, so a moving part redraws alone; the looping effects exist only while they are active, and
 * like all Compose animation they stop when the app leaves the screen.
 *
 * If only a right-side door is open, the car turns around to show that side.
 */
@Composable
fun CarSideView(
    car: CarSnapshot,
    warmAir: Boolean,
    fx: Pair<Long, HeroFx>?,
    modifier: Modifier = Modifier,
    animate: Boolean = true, // false while scrolled out of view: looping effects hold still
    online: Boolean = true,
) {
    val doors = car.doors
    val wantRight = (doors?.rf == true || doors?.rr == true) && doors.lf != true && doors.lr != true

    // Turning to the other side happens in order: doors on this side close, the car turns, then
    // the doors on the new side open. Never a car spinning with a door hanging open.
    var shownRight by remember { mutableStateOf(wantRight) }
    val turnTarget = if (shownRight) -1f else 1f
    val turn = animateFloatAsState(turnTarget, tween(900, easing = TurnEasing), label = "turn")
    val settled by remember(turnTarget) { derivedStateOf { turn.value == turnTarget } }
    val onShownSide = shownRight == wantRight && settled
    fun shown(left: Boolean?, right: Boolean?) = (if (shownRight) right else left) == true

    val anyWindow = car.windowsOpen == true && car.windows == null
    val frontDoor = rememberProgress(onShownSide && shown(doors?.lf, doors?.rf), DoorSpring)
    val rearDoor = rememberProgress(onShownSide && shown(doors?.lr, doors?.rr), DoorSpring)
    val frontGlass = rememberProgress(anyWindow || shown(car.windows?.lf, car.windows?.rf), GlassSpec)
    val rearGlass = rememberProgress(anyWindow || shown(car.windows?.lr, car.windows?.rr), GlassSpec)
    val tailgate = rememberProgress(car.trunkOpen == true, TailgateSpec)
    // Mirrors start where they are: folding on every launch would look like a fresh lock.
    val mirrorsOut = rememberProgress(car.locked != true, tween(600), startAtTarget = true)
    val nearSeat = (if (shownRight) car.seatPassenger else car.seatDriver) ?: 0
    val farSeat = (if (shownRight) car.seatDriver else car.seatPassenger) ?: 0
    LaunchedEffect(wantRight) {
        if (wantRight != shownRight) {
            snapshotFlow { frontDoor.value + rearDoor.value }.first { it < 0.03f }
            shownRight = wantRight
        }
    }
    val heat by animateFloatAsState(nearSeat.coerceIn(0, 2) / 2f, tween(900), label = "seat")
    val farHeat by animateFloatAsState(farSeat.coerceIn(0, 2) / 2f, tween(900), label = "farSeat")
    val plugged by animateFloatAsState(if (car.plugged == true || car.charging == true) 1f else 0f, tween(600), label = "plug")

    val lights = remember { Animatable(0f) }
    val horn = remember { Animatable(0f) }
    val badge = remember { Animatable(0f) }
    // The effect playing now. Only a newer effect replaces it: another command's result arriving
    // mid-play (fx turns null) must not cut it off and leave the lights or the padlock on screen.
    var playing by remember { mutableStateOf<Pair<Long, HeroFx>?>(null) }
    LaunchedEffect(fx?.first) { if (fx != null) playing = fx }
    LaunchedEffect(playing?.first) {
        val effect = playing?.second ?: return@LaunchedEffect
        try {
            when (effect) {
                HeroFx.FLASH -> blink(lights, 2)
                HeroFx.FIND -> coroutineScope {
                    launch { horn.snapTo(0f); horn.animateTo(1f, tween(2200, easing = LinearEasing)) }
                    blink(lights, 3)
                }
                HeroFx.LOCKED, HeroFx.UNLOCKED -> {
                    badge.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f))
                    delay(1_100)
                    badge.animateTo(0f, tween(250))
                }
            }
        } finally {
            withContext(NonCancellable) {
                lights.snapTo(0f)
                horn.snapTo(0f)
                badge.snapTo(0f)
            }
        }
    }

    val description = heroDescription(car)
    // No data yet, or no server: the picture is a placeholder or possibly stale, so it steps back.
    val presence = when {
        !car.hasData -> 0.45f
        !online -> 0.7f
        else -> 1f
    }
    Box(modifier.aspectRatio(ART_W / ART_H).semantics { contentDescription = description }) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = turn.value
                alpha = presence
            },
        ) {
            Canvas(Modifier.fillMaxSize()) { inCar { base(frontDoor.value, rearDoor.value) } }
            if (plugged > 0f) BatteryStrip(car.soc ?: 0, car.charging == true && animate, plugged)
            Canvas(Modifier.fillMaxSize()) { inCar { interior(heat, farHeat, maxOf(frontDoor.value, rearDoor.value)) } }
            AnimatedVisibility(car.climateOn == true, Modifier.fillMaxSize(), enter = fadeIn(tween(800)), exit = fadeOut(tween(600))) {
                Airflow(if (warmAir) WarmAir else Palette.Accent, animate)
            }
            Canvas(Modifier.fillMaxSize()) { inCar { bodyTop(bayLight(tailgate.value)) } }
            Canvas(Modifier.fillMaxSize()) { inCar { cargoBay(tailgate.value) } }
            Canvas(Modifier.fillMaxSize().swingingDoor(FRONT_HINGE) { frontDoor.value }) {
                inCar { door(Art.frontDoor, Art.frontLower, Art.frontTrim, Art.frontCrease, Rect(296f, 101f, 320f, 105.5f), Art.frontWin, Art.frontTop, Art.frontFrame, frontGlass.value) }
            }
            Canvas(Modifier.fillMaxSize().swingingDoor(REAR_HINGE) { rearDoor.value }) {
                inCar { door(Art.rearDoor, Art.rearLower, Art.rearTrim, Art.rearCrease, Rect(430f, 96f, 452f, 100.5f), Art.rearWin, Art.rearTop, Art.rearFrame, rearGlass.value) }
            }
            Canvas(Modifier.fillMaxSize()) { inCar { tailgate(tailgate.value, lights.value) } }
            // The mirror sits on the front door: it swings with the door and folds while locked.
            Box(Modifier.fillMaxSize().swingingDoor(FRONT_HINGE) { frontDoor.value }) {
                Canvas(
                    Modifier.fillMaxSize().graphicsLayer {
                        transformOrigin = TransformOrigin(MIRROR_PIVOT.x, MIRROR_PIVOT.y)
                        rotationY = MIRROR_OUT * mirrorsOut.value
                        cameraDistance = camera(size.width)
                    },
                ) { inCar { mirror(lights.value) } }
            }
            Canvas(Modifier.fillMaxSize()) { inCar { lamps(lights.value, horn.value) } }
        }

        // Lock / unlock confirmation: a padlock pops above the roof (not mirrored with the car).
        if (badge.value > 0f) {
            val locked = playing?.second == HeroFx.LOCKED
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp)
                    .size(34.dp)
                    .graphicsLayer {
                        scaleX = 0.5f + 0.5f * badge.value
                        scaleY = scaleX
                        alpha = badge.value.coerceIn(0f, 1f)
                    }
                    .clip(CircleShape)
                    .background(if (locked) Palette.Ok else Palette.Warn),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(if (locked) R.drawable.ic_lock else R.drawable.ic_unlock), null,
                    tint = Palette.Bg, modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ looping layers

/** Air streaming from the dash to the rear seats, seen through the glass. */
@Composable
private fun Airflow(color: Color, animate: Boolean) {
    val phase: State<Float> = if (animate) {
        val flow = rememberInfiniteTransition(label = "air")
        flow.animateFloat(0f, DASH_PERIOD, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "phase")
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    Canvas(Modifier.fillMaxSize()) {
        inCar {
            clipPath(Art.cabin) {
                drawRect(color.copy(alpha = 0.16f), Offset(170f, 20f), Size(380f, 180f))
                Art.air.forEachIndexed { i, stream ->
                    // Phase counts down so the dashes travel rearward along each stream.
                    val shift = (DASH_PERIOD - phase.value + i * 11f) % DASH_PERIOD
                    drawPath(stream, color, style = Stroke(2.4f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 26f), shift)))
                }
            }
        }
    }
}

/** The battery pack under the floor: filled to the charge level, with a pulse running while charging. */
@Composable
private fun BatteryStrip(soc: Int, charging: Boolean, alpha: Float) {
    val pulse = if (charging) {
        val loop = rememberInfiniteTransition(label = "charge")
        loop.animateFloat(-60f, 250f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "pulse")
    } else {
        null
    }
    Canvas(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        inCar {
            val track = Rect(186f, 203f, 414f, 209f)
            val radius = CornerRadius(3f)
            drawRoundRect(Color(0xFF1B232E), track.topLeft, track.size, radius)
            val fill = track.width * soc.coerceIn(0, 100) / 100f
            drawRoundRect(Brush.horizontalGradient(listOf(Palette.Ok, Palette.Accent), track.left, track.right), track.topLeft, Size(fill, track.height), radius)
            pulse?.let {
                clipPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(track, radius)) }) {
                    val x = track.left + it.value
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.85f), Color.Transparent), x, x + 50f),
                        Offset(x, track.top), Size(50f, track.height),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ drawing

private fun DrawScope.base(frontDoor: Float, rearDoor: Float) {
    oval(Offset(295f, 233f), 292f, 10f, Color.Black.copy(alpha = 0.6f))
    // Puddle lights: an open door lights the ground below it, like the car does.
    if (frontDoor > 0.02f) oval(Offset(262f, 234f), 64f, 7f, CourtesyWarm.copy(alpha = 0.7f * frontDoor.coerceIn(0f, 1f)))
    if (rearDoor > 0.02f) oval(Offset(384f, 234f), 52f, 6f, CourtesyWarm.copy(alpha = 0.6f * rearDoor.coerceIn(0f, 1f)))
    drawPath(Art.body, CarPaint)
    drawPath(Art.creases, Color.White.copy(alpha = 0.28f), style = Stroke(1.3f))
    drawPath(Art.frontIntake, Trim)
    drawPath(Art.frontLip, Trim, style = Stroke(3f))
    drawPath(Art.rearBumper, Trim)
    drawPath(Art.arches, Color(0xFF15181D), style = Stroke(7f))
    drawRect(Color(0xFF16191E), Offset(174f, 184f), Size(250f, 15f))
    wheel(117f)
    wheel(481f)
}

private fun DrawScope.wheel(cx: Float) = translate(cx, 186f) {
    drawCircle(Color(0xFF0C0E11), 47f, Offset.Zero)
    drawCircle(Color(0xFF1F232A), 45f, Offset.Zero, style = Stroke(1.5f))
    drawCircle(Color(0xFF1B1F25), 34f, Offset.Zero)
    val alloy = Brush.radialGradient(listOf(Color(0xFFE4E9EF), Color(0xFF9AA3AF), Color(0xFF6B7380)), Offset(-6f, -8f), 46f)
    repeat(5) { i ->
        rotate(i * 72f, Offset.Zero) {
            drawPath(Art.spokeA, alloy)
            drawPath(Art.spokeB, alloy)
        }
    }
    drawCircle(Color(0xFFB4BCC7), 33.5f, Offset.Zero, style = Stroke(2.2f))
    drawCircle(Color(0xFFC9D0D9), 8f, Offset.Zero)
    drawCircle(Color(0xFF2A2F37), 3f, Offset.Zero)
}

private fun DrawScope.interior(heat: Float, farHeat: Float, doorOpen: Float) = clipPath(Art.cabin) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF202A36), Color(0xFF0A0E14)), 20f, 200f), Offset(170f, 20f), Size(380f, 180f))
    drawPath(Art.dash, Color(0xFF10151C))
    rotate(-18f, Offset(236f, 84f)) {
        drawOval(Color(0xFF3A4452), Offset(231f, 64f), Size(10f, 40f), style = Stroke(4f))
    }
    // The other front seat, a little behind and higher: it peeks out past the near one.
    translate(11f, -3f) {
        Art.frontSeat.take(2).forEach { drawPath(it, Color(0xFF1B222C)) }
        if (farHeat > 0f) {
            val warm = Brush.radialGradient(listOf(Color(0xFFFF9A55), Color(0xFFE0552B).copy(alpha = 0.55f)), Offset(300f, 90f), 60f)
            Art.frontSeat.take(2).forEach { drawPath(it, warm, alpha = 0.35f + 0.4f * farHeat) }
        }
    }
    for (part in Art.seats) {
        drawPath(part, Color(0xFF232B36))
        drawPath(part, Color(0xFF323C49), style = Stroke(1f))
    }
    if (heat > 0f) {
        val warm = Brush.radialGradient(listOf(Color(0xFFFF9A55), Color(0xFFE0552B).copy(alpha = 0.55f)), Offset(300f, 100f), 70f)
        Art.frontSeat.forEach { drawPath(it, warm, alpha = 0.5f + 0.45f * heat) }
    }
    // Courtesy light: with a door open the cabin is lit from the roof, warm and soft (over the seats).
    val dome = doorOpen.coerceIn(0f, 1f)
    if (dome > 0.02f) {
        drawRect(
            Brush.radialGradient(listOf(CourtesyWarm.copy(alpha = 0.62f * dome), Color.Transparent), Offset(320f, 36f), 175f),
            Offset(170f, 20f), Size(380f, 180f),
        )
    }
    drawRect(Color(0xFF0B0F15), Offset(180f, 160f), Size(290f, 30f))
}

private fun DrawScope.bodyTop(bay: Float) {
    drawPath(Art.bPillar, Black)
    drawPath(Art.cPillar, Black)
    if (bay > 0f) {
        drawPath(Art.quarter, Brush.radialGradient(listOf(Color(0xFFFFC77A).copy(alpha = 0.75f), Color.Transparent), Offset(515f, 54f), 60f), alpha = bay)
    }
    drawPath(Art.quarter, glass(Art.quarterBounds))
    drawPath(Art.dPillarTrim, Color(0xFFC7CFD9))
    drawPath(Art.aPillar, Color(0xFF0E1116))
    drawPath(Art.roofRail, Color(0xFF23272E))
}

private fun DrawScope.cargoBay(open: Float) {
    drawPath(Art.tailgate, Color(0xFF0D1117))
    val bay = bayLight(open)
    if (bay > 0f) {
        drawPath(Art.tailgate, Brush.verticalGradient(0f to Color(0xFF8A6232), 0.6f to Color(0xFF3A2A18), 1f to Color(0xFF18120B), startY = 26f, endY = 128f), alpha = bay)
        drawOval(Color(0xFFFFF3DC).copy(alpha = bay), Offset(510f, 34f), Size(32f, 12f))
        oval(Offset(614f, 232f), 74f, 8f, Color(0xFFFFC77A).copy(alpha = 0.55f * bay))
    }
    drawPath(Art.sillPlate, Color(0xFF9AA3AF))
    drawPath(Art.tailgateEdge, Color(0xFF23272E), style = Stroke(2f))
    // Gas strut: body side of the opening up to the lifted tailgate.
    if (open > 0.05f) {
        val top = rotateAround(Offset(524f, 48f), HINGE, -TAILGATE_LIFT * open)
        val bottom = Offset(541f, 88f)
        val mid = Offset((top.x + bottom.x) / 2f, (top.y + bottom.y) / 2f)
        drawLine(Color(0xFF6B7380), mid, top, 1.6f, StrokeCap.Round)
        drawLine(Color(0xFFAEB7C3), bottom, mid, 2.6f, StrokeCap.Round)
    }
}

private fun DrawScope.door(
    panel: Path, lower: Path, trim: Path, crease: Path, handle: Rect,
    window: Path, topEdge: Path, frame: Path, glassDown: Float,
) {
    drawPath(panel, CarPaint)
    drawPath(panel, Color(0xFF22262D), style = Stroke(0.8f))
    drawPath(lower, Color(0xFF16191E))
    drawPath(trim, Color(0xFFBCC5D1))
    drawPath(crease, Color.White.copy(alpha = 0.22f), style = Stroke(1.2f))
    drawRoundRect(Color(0xFF2A2F37), handle.topLeft, handle.size, CornerRadius(2.2f))
    drawRect(Color.White.copy(alpha = 0.25f), Offset(handle.left + 1f, handle.top), Size(handle.width - 2f, 1.2f))
    // The pane slides down into the door; the frame clips it like the real window opening.
    clipPath(window) {
        translate(0f, GLASS_DROP * glassDown) {
            drawPath(window, glass(window.getBounds()))
            drawPath(topEdge, Color.White.copy(alpha = 0.45f), style = Stroke(1.4f))
        }
    }
    drawPath(frame, Black, style = Stroke(3f))
}

private fun DrawScope.tailgate(open: Float, lights: Float) = rotate(-TAILGATE_LIFT * open, HINGE) {
    drawPath(Art.tailgate, CarPaint)
    drawPath(Art.tailgate, Color(0xFF1C2027), style = Stroke(1f))
    drawPath(Art.tailgateInner, Color(0xFF2C323B), style = Stroke(3.5f))
    drawPath(Art.rearGlass, Black)
    drawPath(Art.rearGlassShine, Color.White.copy(alpha = 0.18f), style = Stroke(1.2f))
    drawPath(Art.spoiler, Color(0xFF16191E))
    drawPath(Art.tailLamp, Color(0xFF5E1019))
    drawPath(Art.tailGuide, Color(0xFFB8283A), style = Stroke(1.4f, cap = StrokeCap.Round))
    if (lights > 0f) {
        drawPath(Art.tailLamp, Color(0xFFFF2E46), alpha = lights)
        drawPath(Art.tailGuide, Color(0xFFFFD1D7).copy(alpha = lights), style = Stroke(1.6f, cap = StrokeCap.Round))
        oval(Offset(566f, 95f), 34f, 14f, Color(0xFFFF4458).copy(alpha = 0.9f * lights))
    }
}

private fun DrawScope.mirror(lights: Float) {
    drawPath(Art.mirrorArm, Color(0xFF14171C))
    drawPath(Art.mirror, CarPaint)
    drawPath(Art.mirror, Color(0xFF22262D), style = Stroke(0.6f))
    drawPath(Art.mirrorSignal, Color(0xFF8A6A3A), style = Stroke(1.2f))
    if (lights > 0f) drawPath(Art.mirrorSignal, Color(0xFFFFC14D).copy(alpha = lights), style = Stroke(1.6f))
}

private fun DrawScope.lamps(lights: Float, horn: Float) {
    drawPath(Art.headlamp, Brush.verticalGradient(listOf(Color(0xFF3A4553), Color(0xFF151A21)), 99f, 127f))
    drawPath(Art.headlamp, Black, style = Stroke(0.8f))
    drawPath(Art.drl, Color(0xFFA9B4C2), style = Stroke(1.6f, cap = StrokeCap.Round))
    drawCircle(Color(0xFF5E6B7A), 3.6f, Offset(31f, 114f))
    drawCircle(Color(0xFFC9D3DF), 1.3f, Offset(30f, 113f))
    if (lights > 0f) {
        drawPath(Art.beam, Brush.linearGradient(listOf(BeamWhite.copy(alpha = 0.55f), Color.Transparent), Offset(26f, 114f), Offset(-40f, 226f)), alpha = lights)
        oval(Offset(-22f, 232f), 62f, 7f, BeamWhite.copy(alpha = 0.45f * lights))
        oval(Offset(606f, 232f), 54f, 6f, Color(0xFFFF3347).copy(alpha = 0.35f * lights))
        drawPath(Art.drl, Color.White.copy(alpha = lights), style = Stroke(2.4f, cap = StrokeCap.Round))
        drawCircle(Color.White.copy(alpha = lights), 3.8f, Offset(31f, 114f))
        oval(Offset(34f, 111f), 34f, 12f, Color.White.copy(alpha = 0.95f * lights))
    }
    // Find car: three rings spread out over the car, as the horn sounds.
    if (horn > 0f) {
        repeat(3) { i ->
            val t = ((horn * 2.2f - i * 0.25f) % 1.1f) / 1.1f
            if (horn * 2.2f >= i * 0.25f && t in 0f..1f) {
                drawCircle(Palette.Accent.copy(alpha = 0.9f * (1f - t)), 18f * (0.4f + 2.4f * t), Offset(70f, 70f), style = Stroke(2.2f))
            }
        }
    }
}

// ------------------------------------------------------------------ helpers

private const val ART_W = 680f
private const val ART_H = 300f
private const val DASH_PERIOD = 44f
private const val GLASS_DROP = 30f
private const val TAILGATE_LIFT = 68f
private const val DOOR_SWING = -56f
// From the side, an open mirror points at you, so it looks short; folded, it lies along the door
// and looks long. The art is drawn folded; "out" turns its tip toward the viewer.
private const val MIRROR_OUT = -64f
private val HINGE = Offset(494f, 26f)
private const val FRONT_HINGE = 0.343f
private const val REAR_HINGE = 0.554f
private val MIRROR_PIVOT = Offset(0.365f, 0.401f)

private val TurnEasing = CubicBezierEasing(0.6f, 0f, 0.3f, 1f)
private val DoorSpring = spring<Float>(dampingRatio = 0.7f, stiffness = 120f)
private val GlassSpec = tween<Float>(1900, easing = CubicBezierEasing(0.4f, 0f, 0.4f, 1f))
private val TailgateSpec = tween<Float>(1700, easing = CubicBezierEasing(0.45f, 0.05f, 0.3f, 1f))

private val Black = Color(0xFF0B0E12)
private val Trim = Color(0xFF14171C)
private val BeamWhite = Color(0xFFE6F1FF)
private val WarmAir = Color(0xFFFF8A4C)
private val CourtesyWarm = Color(0xFFFFD9A8)
private val CarPaint = Brush.verticalGradient(
    0f to Color(0xFFAEB7C5), 0.3f to Color(0xFF7D8695), 0.52f to Color(0xFF5E6674),
    0.78f to Color(0xFF474E5A), 1f to Color(0xFF30353D), startY = 18f, endY = 200f,
)

/** The car's own coordinates: artboard scaled to the width, car placed as in the mock. */
private inline fun DrawScope.inCar(block: DrawScope.() -> Unit) {
    val s = size.width / ART_W
    withTransform({
        scale(s, s, Offset.Zero)
        translate(64f, 46f)
        scale(0.93f, 0.93f, Offset.Zero)
    }, block)
}

/**
 * Camera distance for a door's perspective: about 0.8 of the picture's width away, a natural
 * lens. (Measured on device: one unit of camera distance is about 10.75 dp.)
 */
private fun GraphicsLayerScope.camera(widthPx: Float) = 0.8f * widthPx / (density * 10.75f)

/** A door turning on its front hinge toward the viewer, with real perspective. */
private fun Modifier.swingingDoor(hingeX: Float, open: () -> Float) = graphicsLayer {
    transformOrigin = TransformOrigin(hingeX, 0.49f)
    rotationY = DOOR_SWING * open().coerceAtLeast(0f)
    cameraDistance = camera(size.width)
}

/** Tinted glass: dark, with one diagonal reflection. */
private fun glass(bounds: Rect) = Brush.linearGradient(
    0f to Color(0xC73A4757), 0.45f to Color(0x9E1C2530), 0.5f to Color(0x8C5D6B7C), 0.56f to Color(0x9E1C2530), 1f to Color(0xCC0D1218),
    start = bounds.topLeft, end = bounds.bottomRight,
)

/** A soft elliptical glow or shadow, fading to nothing at its edge. */
private fun DrawScope.oval(center: Offset, rx: Float, ry: Float, color: Color) {
    withTransform({ scale(1f, ry / rx, center) }) {
        drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center, rx), rx, center)
    }
}

/** Cargo-bay light follows the tailgate: it comes on once the lid is well on its way. */
private fun bayLight(open: Float) = ((open - 0.2f) / 0.6f).coerceIn(0f, 1f)

private fun rotateAround(p: Offset, pivot: Offset, degrees: Float): Offset {
    val r = Math.toRadians(degrees.toDouble())
    val dx = p.x - pivot.x
    val dy = p.y - pivot.y
    return Offset(
        pivot.x + (dx * cos(r) - dy * sin(r)).toFloat(),
        pivot.y + (dx * sin(r) + dy * cos(r)).toFloat(),
    )
}

/** 0 → 1 toward [open]. Starts closed (so what's open at launch visibly opens) unless [startAtTarget]. */
@Composable
private fun rememberProgress(open: Boolean, spec: AnimationSpec<Float>, startAtTarget: Boolean = false): Animatable<Float, AnimationVector1D> {
    val value = remember { Animatable(if (startAtTarget && open) 1f else 0f) }
    LaunchedEffect(open) { value.animateTo(if (open) 1f else 0f, spec) }
    return value
}

/** What the picture shows, in words, for screen readers. */
@Composable
private fun heroDescription(car: CarSnapshot): String = listOfNotNull(
    car.name,
    when (car.locked) {
        true -> stringResource(R.string.state_locked)
        false -> stringResource(R.string.state_unlocked)
        null -> null
    },
    stringResource(R.string.state_door_open).takeIf { car.doorsOpen == true },
    stringResource(R.string.state_trunk_open).takeIf { car.trunkOpen == true },
    stringResource(R.string.state_window_open).takeIf { car.windowsOpen == true },
    stringResource(R.string.state_climate_on).takeIf { car.climateOn == true },
    stringResource(R.string.state_charging).takeIf { car.charging == true },
    car.soc?.let { "$it%" },
).joinToString(", ")

private suspend fun blink(lights: Animatable<Float, AnimationVector1D>, times: Int) {
    repeat(times) {
        lights.animateTo(1f, tween(90))
        delay(160)
        lights.animateTo(0f, tween(260))
        delay(60)
    }
}

private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

/** Every shape of the car, in artboard units (car facing left, driver's side). */
private object Art {
    val body = path("M30 200C20 196 14 186 13 168L13 146C13 128 18 116 26 108C60 96 118 86 162 80C190 62 214 42 236 30C300 20 420 18 480 24C505 26 522 29 534 34C546 50 556 66 563 82C571 96 576 110 577 128L577 168C577 182 571 192 560 196L538 196A58 58 0 1 0 424 196L174 196A58 58 0 1 0 60 196L30 200Z")
    val creases = path("M40 104C90 95 140 91 182 93M458 87C492 83 526 79 556 77")
    val frontIntake = path("M16 166C18 178 24 188 34 195L52 195C44 186 40 177 40 166Z")
    val frontLip = path("M14 146C24 142 32 141 40 142")
    val rearBumper = path("M548 158L577 156L577 170C577 182 571 192 560 196L538 196Z")
    val arches = path("M56.8 196A61 61 0 1 1 177.2 196M420.8 196A61 61 0 1 1 541.2 196")
    val spokeA = path("M-3-7C-5-16-9-24-15-31L-8-33C-3-24 1-15 3-7Z")
    val spokeB = path("M3-7C3-17 1-25-2-33L5-33C7-24 8-16 7-6Z")

    val cabin = Path().apply {
        addPath(path("M182 96C186 90 190 87 196 86L244 38C270 34 300 32 331 31L333 81L335 186L184 186Z"))
        addPath(path("M335 81L333 31L452 31L455 77C459 96 458 108 452 118C440 132 427 150 423 186L337 186Z"))
        addPath(path("M459 31C490 33 512 40 531 58L536 64C508 68 484 73 460 76Z"))
    }
    val dash = path("M186 92C210 86 240 82 262 84L262 92C236 94 210 96 186 100Z")
    val frontSeat = listOf(
        path("M309 40H317A7 7 0 0 1 324 47V49A7 7 0 0 1 317 56H309A7 7 0 0 1 302 49V47A7 7 0 0 1 309 40Z"),
        path("M300 60L328 60C330 92 324 124 318 150L290 150C292 120 296 90 300 60Z"),
        path("M236 142C260 138 290 138 318 142L320 160L238 160Z"),
    )
    val seats = frontSeat + listOf(
        path("M427 42H435A7 7 0 0 1 442 49V50A7 7 0 0 1 435 57H427A7 7 0 0 1 420 50V49A7 7 0 0 1 427 42Z"),
        path("M418 60L446 60C448 92 442 124 436 150L408 150C410 120 414 90 418 60Z"),
        path("M352 142C376 138 404 138 434 142L436 160L354 160Z"),
    )
    val air = listOf(
        path("M200 52C240 44 280 60 320 50S400 44 470 56"),
        path("M196 66C240 58 280 74 330 64S410 58 480 68"),
        path("M200 80C250 74 290 88 340 78S420 72 490 80"),
        path("M210 112C260 104 300 120 350 108S420 102 470 112"),
    )

    val bPillar = path("M329 30L346 30L346 82L331 82Z")
    val cPillar = path("M452 30L459 31L461 76L453 77Z")
    val quarter = path("M461 32C490 34 512 41 529 57L533 63C508 67 484 72 462 75Z")
    val quarterBounds = quarter.getBounds()
    val dPillarTrim = path("M455 80C487 76 514 69 533 61C540 53 538 44 530 36L534 34C545 44 547 57 538 66C518 74 488 80 457 85Z")
    val aPillar = path("M164 82C190 62 214 42 238 30L246 36C224 48 204 66 194 86Z")
    val roofRail = path("M262 22C320 16 420 15 486 20L486 24C420 20 320 21 262 26Z")

    val frontDoor = path("M182 96C186 90 190 87 196 86L333 81L335 186L184 186Z")
    val frontLower = path("M184 178L335 178L335 186L184 186Z")
    val frontTrim = path("M190 171C240 170 300 169 335 168L335 172C300 173 240 174 190 175Z")
    val frontCrease = path("M184 98C230 94 290 90 334 88")
    val frontWin = path("M196 86L244 38C270 34 300 32 331 31L333 81Z")
    val frontTop = path("M244 38C270 34 300 32 331 31")
    val frontFrame = path("M196 86L244 38C270 34 300 32 331 31L333 81")

    val rearDoor = path("M335 81L455 77C459 96 458 108 452 118C440 132 427 150 423 186L337 186Z")
    val rearLower = path("M337 178L425 178C424 181 423 184 423 186L337 186Z")
    val rearTrim = path("M337 168C372 167 402 165 426 158L425 163C402 170 372 171 337 172Z")
    val rearCrease = path("M337 87C380 85 420 83 458 81")
    val rearWin = path("M346 31L452 31L453 76L346 80.5Z")
    val rearTop = path("M346 31L452 31")
    val rearFrame = path("M346 31L452 31L453 76")

    val tailgate = path("M494 26C512 27 526 30 537 33C547 48 555 64 562 80C570 95 576 110 577 128L552 128C549 112 546 96 541 82C536 64 528 50 518 40C510 33 502 29 494 26Z")
    val tailgateEdge = path("M494 26C512 27 526 30 537 33C547 48 555 64 562 80C570 95 576 110 577 128")
    val tailgateInner = path("M552 128C549 112 546 96 541 82C536 64 528 50 518 40C510 33 502 29 494 26")
    val rearGlass = path("M497 27C512 27 526 30 536 34C545 47 552 59 558 72L545 74C539 61 531 49 521 40C513 33 505 29 497 27Z")
    val rearGlassShine = path("M499 29C510 30 519 33 526 37")
    val spoiler = path("M486 23C510 22 531 25 545 31L539 37C526 32 509 28 490 28Z")
    val tailLamp = path("M522 90C536 88 552 86 566 84C571 89 575 96 577 104C566 104 552 103 540 100C532 97 526 94 522 90Z")
    val tailGuide = path("M526 91C540 89 556 87 568 86")
    val sillPlate = path("M550 120L577 119L577 128L552 128Z")

    val mirrorArm = path("M196 84L200 76L206 78L204 86Z")
    val mirror = path("M200 74C204 64 218 60 232 62C240 64 242 72 238 78C228 82 212 82 200 80Z")
    val mirrorSignal = path("M210 79C222 80 232 79 238 77")

    val headlamp = path("M14 123C16 115 22 109 30 107C52 102 72 100 91 99C88 102 84 104 79 106C62 109 45 113 31 119C24 122 18 125 14 127Z")
    val drl = path("M17 118C30 110 56 103 89 100")
    val beam = path("M24 112L-70 214L4 236L30 120Z")
}
