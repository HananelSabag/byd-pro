package com.example.byd.wear

import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.MultiButtonLayout
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tile: lock, unlock, tailgate, quick cool and quick heat in one swipe from the watch face. A tap sends the
 * command at once (no app opening); the status line follows the tap (sent, the phone's steps, the
 * answer) and the tile redraws when the phone acks it or publishes its progress.
 */
class CarTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            val clicked = requestParams.currentState.lastClickableId
            if (clicked in PhoneLink.COMMANDS) {
                Buzz.tap(this@CarTileService)
                val known = StateCache.load(this@CarTileService)
                val tap = Taps.start(this@CarTileService, clicked, legacy = known.hasData && known.proto < 2)
                if (PhoneLink.command(this@CarTileService, tap)) {
                    watchAck(tap)
                } else {
                    Taps.notSent(this@CarTileService, tap.id)?.let { Buzz.settled(this@CarTileService, listOf(it)) }
                }
            }
            Buzz.settled(this@CarTileService, Taps.settle(this@CarTileService))
            tile(requestParams.deviceConfiguration)
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture {
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES).apply {
                ICONS.forEach { (id, res) ->
                    addIdToImageMapping(
                        id,
                        ResourceBuilders.ImageResource.Builder()
                            .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(res).build())
                            .build(),
                    )
                }
            }.build()
        }

    /** No ack and no new state would leave the tile showing "sending": look again after the ack timeout. */
    private fun watchAck(tap: Tap) {
        val app = applicationContext
        background.launch {
            delay((if (tap.legacy) Taps.LEGACY_TIMEOUT_MS else Taps.ACK_TIMEOUT_MS) + 500)
            Buzz.settled(app, Taps.settle(app))
            getUpdater(app).requestUpdate(CarTileService::class.java)
        }
    }

    private fun tile(device: DeviceParameters): TileBuilders.Tile {
        val state = StateCache.load(this)
        val waiting = Taps.waiting(this)
        val busy = waiting + state.livePending()
        val tapStatus = Taps.latest(this)?.let { TapStatus.of(this, it, state) }
        val cooling = state.climateOn == true && state.heating == false
        val heating = state.climateOn == true && state.heating == true

        val headline = when {
            state.soc != null && state.rangeKm != null -> getString(R.string.percent_range, state.soc, state.rangeKm)
            state.soc != null -> getString(R.string.percent, state.soc)
            else -> getString(R.string.app_name)
        }
        val left = state.minutesLeft()
        val (status, statusColor) = when {
            tapStatus != null -> tapStatus.text to toneColor(tapStatus.tone) // the newest tap first
            !state.hasData -> getString(R.string.no_data) to TEXT_MUTED
            left != null && heating -> getString(R.string.heating_left, left) to WARM
            left != null && cooling -> getString(R.string.cooling_left, left) to ACCENT
            state.climateOn == true -> getString(R.string.climate_on) to ACCENT
            state.charging == true && state.chargeKw > 0 -> getString(R.string.charging_kw, state.chargeKw) to OK
            state.trunkOpen == true -> getString(R.string.tailgate_open) to WARN
            state.locked == true -> getString(R.string.locked) to OK
            state.locked == false -> getString(R.string.unlocked) to WARN
            else -> "" to TEXT_MUTED
        }

        val buttons = MultiButtonLayout.Builder()
            // One lock button, like on / off: its id is the command it sends (the opposite of the state).
            .addButtonContent(
                if (state.locked == true) button(PhoneLink.UNLOCK, "lock", R.string.unlock, OK, true, busy)
                else button(PhoneLink.LOCK, if (state.locked == false) "unlock" else "lock", R.string.lock, WARN, state.locked == false, busy),
            )
            .addButtonContent(button(PhoneLink.TRUNK, "trunk", R.string.tailgate, WARN, state.trunkOpen == true, busy))
            // A running mode's button lights up with a power icon: a tap stops the A/C.
            .addButtonContent(
                if (cooling) button(PhoneLink.CLIMATE_OFF, "power", R.string.stop_climate, ACCENT, true, busy)
                else button(PhoneLink.QUICK_COOL, "cool", R.string.cool, ACCENT, false, busy),
            )
            .addButtonContent(
                if (heating) button(PhoneLink.CLIMATE_OFF, "power", R.string.stop_climate, WARM, true, busy)
                else button(PhoneLink.QUICK_HEAT, "heat", R.string.heat, WARM, false, busy),
            )
            .build()

        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build(),
                    )
                    .build(),
            )
            .build()

        val layout = PrimaryLayout.Builder(device)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(
                Text.Builder(this, headline).setTypography(Typography.TYPOGRAPHY_CAPTION1).setColor(argb(TEXT_PRIMARY)).build(),
            )
            .setSecondaryLabelTextContent(
                Text.Builder(this, status).setTypography(Typography.TYPOGRAPHY_CAPTION2).setColor(argb(statusColor)).build(),
            )
            .setContent(buttons)
            .setPrimaryChipContent(
                CompactChip.Builder(this, getString(R.string.open_app), open, device)
                    .setChipColors(ChipColors(argb(SURFACE), argb(TEXT_PRIMARY)))
                    .build(),
            )
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES)
            // While a tap is open the tile asks to be drawn again soon, so its status moves on.
            .setFreshnessIntervalMillis(if (waiting.isNotEmpty()) 5_000L else 0L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
    }

    /** Lit while its state is on; dimmed in the action color while the command is on its way. */
    private fun button(command: String, icon: String, label: Int, color: Int, active: Boolean, busy: Set<String>): Button {
        val sending = command in busy
        val colors = when {
            sending -> ButtonColors(argb(color and 0x00FFFFFF or 0x40000000), argb(color))
            active -> ButtonColors(argb(color), argb(BLACK))
            else -> ButtonColors(argb(SURFACE), argb(TEXT_PRIMARY))
        }
        val click = ModifiersBuilders.Clickable.Builder()
            .setId(if (sending) "busy" else command) // a second tap while sending does nothing
            .setOnClick(ActionBuilders.LoadAction.Builder().build())
            .build()
        return Button.Builder(this, click)
            .setIconContent(icon)
            .setButtonColors(colors)
            .setContentDescription(getString(label))
            .build()
    }

    /** The phone's outcome tones in the tile's colours (the app screen has the same map). */
    private fun toneColor(tone: String): Int = when (tone) {
        "OK" -> OK
        "WARN" -> SOFT
        "ERR" -> ERR
        "BUSY" -> BUSY
        else -> TEXT_MUTED
    }

    private companion object {
        val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        const val ERR = 0xFFFF6B6B.toInt()
        const val BUSY = 0xFFD6DEE8.toInt() // a command on its way: neutral
        const val SOFT = 0xFFFFB648.toInt() // sent but not confirmed yet
        const val RESOURCES = "3"
        val ICONS = mapOf(
            "lock" to R.drawable.ic_lock,
            "unlock" to R.drawable.ic_unlock,
            "trunk" to R.drawable.ic_trunk,
            "cool" to R.drawable.ic_snowflake,
            "heat" to R.drawable.ic_flame,
            "power" to R.drawable.ic_power,
        )
        const val ACCENT = 0xFF5AB0FF.toInt()
        const val OK = 0xFF5AB0FF.toInt()
        const val WARN = 0xFFFF6B6B.toInt()
        const val WARM = 0xFFFF8A4C.toInt()
        const val SURFACE = 0xFF222B36.toInt()
        const val TEXT_PRIMARY = 0xFFF2F5F9.toInt()
        const val TEXT_MUTED = 0xFF8994A5.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
