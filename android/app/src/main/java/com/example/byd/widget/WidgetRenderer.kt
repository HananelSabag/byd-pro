package com.example.byd.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.example.byd.MainActivity
import com.example.byd.R
import com.example.byd.data.ApiError
import com.example.byd.data.CarCommand
import com.example.byd.data.CarRepository
import com.example.byd.data.CarSnapshot
import com.example.byd.data.CarUiState
import java.util.Date

/**
 * The actions a widget button can trigger. CLIMATE, WINDOWS and CHARGE are toggles that pick
 * their command from the last known state; WINDOWS never opens on an unknown state.
 */
enum class QuickAction(
    val provider: Class<out AppWidgetProvider>,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
) {
    LOCK(LockWidget::class.java, R.string.w_lock, R.drawable.ic_lock),
    UNLOCK(UnlockWidget::class.java, R.string.w_unlock, R.drawable.ic_unlock),
    TRUNK(TrunkWidget::class.java, R.string.w_trunk, R.drawable.ic_trunk),
    CLIMATE(ClimateWidget::class.java, R.string.w_climate, R.drawable.ic_snowflake),
    LOCATE(LocateWidget::class.java, R.string.w_locate, R.drawable.ic_horn),
    FLASH(FlashWidget::class.java, R.string.w_flash, R.drawable.ic_flash),
    WINDOWS(WindowsWidget::class.java, R.string.w_windows, R.drawable.ic_window),
    CHARGE(ChargeWidget::class.java, R.string.w_charge, R.drawable.ic_bolt);

    fun command(repo: CarRepository): CarCommand {
        val car = repo.state.value.snapshot
        return when (this) {
            LOCK -> CarCommand.LOCK
            UNLOCK -> CarCommand.UNLOCK
            TRUNK -> CarCommand.TRUNK
            CLIMATE -> repo.climateToggle()
            LOCATE -> CarCommand.LOCATE
            FLASH -> CarCommand.FLASH
            WINDOWS -> if (car.windowsOpen == false && car.caps.ventWindows == true) CarCommand.WINDOWS_VENT else CarCommand.WINDOWS_CLOSE
            CHARGE -> if (car.charging == true) CarCommand.CHARGE_PAUSE else CarCommand.CHARGE_NOW
        }
    }

    /** Whether this button shows the progress / result of [command]. */
    fun covers(command: CarCommand): Boolean = when (this) {
        CLIMATE -> command == CarCommand.CLIMATE_ON || command == CarCommand.CLIMATE_OFF
        WINDOWS -> command == CarCommand.WINDOWS_CLOSE || command == CarCommand.WINDOWS_VENT
        CHARGE -> command == CarCommand.CHARGE_NOW || command == CarCommand.CHARGE_PAUSE
        else -> command.name == name
    }

    /** "Lit" state: e.g. the lock button glows while the car is locked. */
    fun isActive(car: CarSnapshot): Boolean = when (this) {
        LOCK -> car.locked == true
        UNLOCK -> car.locked == false
        TRUNK -> car.trunkOpen == true
        CLIMATE -> car.climateOn == true
        WINDOWS -> car.windowsOpen == true
        CHARGE -> car.charging == true
        LOCATE, FLASH -> false
    }
}

/** Builds every widget's RemoteViews from the shared [CarUiState]. Pure and cheap: no I/O. */
object WidgetRenderer {
    private enum class Phase { IDLE, BUSY, OK, ERR }

    /** A "sending" mark older than this is treated as lost (process killed mid-command). */
    private const val STALE_PENDING_MS = 75_000L

    private data class DashButton(val action: QuickAction, @param:IdRes val button: Int, @param:IdRes val icon: Int, @param:IdRes val progress: Int)

    private val DASH_BUTTONS = listOf(
        DashButton(QuickAction.LOCK, R.id.d_btn_lock, R.id.d_ic_lock, R.id.d_pb_lock),
        DashButton(QuickAction.UNLOCK, R.id.d_btn_unlock, R.id.d_ic_unlock, R.id.d_pb_unlock),
        DashButton(QuickAction.TRUNK, R.id.d_btn_trunk, R.id.d_ic_trunk, R.id.d_pb_trunk),
        DashButton(QuickAction.CLIMATE, R.id.d_btn_climate, R.id.d_ic_climate, R.id.d_pb_climate),
    )

    /** Pushes the current state to every placed widget. Skips work when none are placed. */
    fun updateAll(context: Context, state: CarUiState = CarRepository.get(context).state.value) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        QuickAction.entries.forEach { action ->
            manager.getAppWidgetIds(ComponentName(context, action.provider)).forEach { id ->
                manager.updateAppWidget(id, quick(context, action, state, manager, id))
            }
        }
        manager.getAppWidgetIds(ComponentName(context, DashboardWidget::class.java)).forEach { id ->
            manager.updateAppWidget(id, dashboard(context, state, manager, id))
        }
    }

    // ------------------------------------------------------------ 1x1 widgets

    /**
     * A 1x1 widget in three sizes, so it never looks crammed: icon only in a very small cell,
     * icon and name in a normal one, plus the car's state when there is room. Android 12+ picks
     * the size as the home screen lays it out; older versions are told the size in the options.
     */
    fun quick(context: Context, action: QuickAction, state: CarUiState, manager: AppWidgetManager, id: Int): RemoteViews =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RemoteViews(
                mapOf(
                    SizeF(40f, 40f) to quickViews(context, action, state, R.layout.widget_quick_tiny),
                    SizeF(48f, 60f) to quickViews(context, action, state, R.layout.widget_quick_mid),
                    SizeF(56f, 76f) to quickViews(context, action, state, R.layout.widget_quick_full),
                ),
            )
        } else {
            val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 70)
            val layout = when {
                height >= 76 -> R.layout.widget_quick_full
                height >= 60 -> R.layout.widget_quick_mid
                else -> R.layout.widget_quick_tiny
            }
            quickViews(context, action, state, layout)
        }

    private fun quickViews(context: Context, action: QuickAction, state: CarUiState, @LayoutRes layout: Int): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        val car = state.snapshot
        val phase = phase(state, action)
        views.setOnClickPendingIntent(R.id.w_click, quickIntent(context, action))
        views.setContentDescription(R.id.w_click, context.getString(action.label))
        views.setImageViewResource(
            R.id.w_disc,
            when (phase) {
                Phase.BUSY -> R.drawable.widget_disc_on
                Phase.OK -> R.drawable.widget_disc_ok
                Phase.ERR -> R.drawable.widget_disc_err
                Phase.IDLE -> if (action.isActive(car)) activeDisc(action) else R.drawable.widget_disc
            },
        )
        views.setViewVisibility(R.id.w_progress, if (phase == Phase.BUSY) View.VISIBLE else View.GONE)
        applyIcon(context, views, R.id.w_icon, action, phase, car)

        val label = when (phase) {
            Phase.BUSY -> context.getString(R.string.sending)
            Phase.OK -> context.getString(doneLabel(action, car))
            Phase.ERR -> context.getString(R.string.failed)
            Phase.IDLE -> context.getString(action.label)
        }

        // The tiny size has no texts, and a widget can't set a view its layout doesn't have.
        if (layout == R.layout.widget_quick_tiny) return views
        views.setTextViewText(R.id.w_label, label)
        val status = if (phase == Phase.IDLE && layout == R.layout.widget_quick_full) quickStatus(action, car) else null
        if (layout == R.layout.widget_quick_full) {
            views.setViewVisibility(R.id.w_state, if (status != null) View.VISIBLE else View.GONE)
            if (status != null) {
                views.setTextViewText(R.id.w_state, context.getString(status.first))
                views.setTextColor(R.id.w_state, context.getColor(status.second))
            }
        }
        return views
    }

    private fun quickStatus(action: QuickAction, car: CarSnapshot): Pair<Int, Int>? = when (action) {
        QuickAction.LOCK, QuickAction.UNLOCK -> when (car.locked) {
            true -> R.string.state_locked to R.color.ok
            false -> R.string.state_unlocked to R.color.warn
            null -> null
        }
        QuickAction.TRUNK -> when (car.trunkOpen) {
            true -> R.string.state_open to R.color.warn
            false -> R.string.state_closed to R.color.text_muted
            null -> null
        }
        QuickAction.CLIMATE -> when (car.climateOn) {
            true -> R.string.state_on to R.color.accent
            false -> R.string.state_off to R.color.text_muted
            null -> null
        }
        QuickAction.WINDOWS -> when (car.windowsOpen) {
            true -> R.string.state_windows_open to R.color.warn
            false -> R.string.state_windows_closed to R.color.text_muted
            null -> null
        }
        QuickAction.CHARGE -> when {
            car.charging == true -> R.string.state_charging to R.color.ok
            car.plugged == true -> R.string.state_plugged_short to R.color.text_secondary
            car.plugged == false -> R.string.state_unplugged_short to R.color.text_muted
            else -> null
        }
        QuickAction.LOCATE, QuickAction.FLASH -> null
    }

    // ------------------------------------------------------------- dashboard

    fun dashboard(context: Context, state: CarUiState, manager: AppWidgetManager, id: Int): RemoteViews =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The launcher picks the layout that fits as the user resizes; no callback needed.
            RemoteViews(
                mapOf(
                    SizeF(180f, 40f) to dashboardViews(context, state, compact = true),
                    SizeF(200f, 110f) to dashboardViews(context, state, compact = false),
                ),
            )
        } else {
            val minHeight = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            dashboardViews(context, state, compact = minHeight < 100)
        }

    private fun dashboardViews(context: Context, state: CarUiState, compact: Boolean): RemoteViews {
        val views = RemoteViews(
            context.packageName,
            if (compact) R.layout.widget_dashboard_compact else R.layout.widget_dashboard,
        )
        val car = state.snapshot
        val soc = car.soc?.toString() ?: "--"
        views.setOnClickPendingIntent(R.id.d_header, openAppIntent(context))
        views.setTextViewText(R.id.d_soc, if (compact) "$soc%" else soc)

        val (lockText, lockColor) = when (car.locked) {
            true -> R.string.state_locked to R.color.ok
            false -> R.string.state_unlocked to R.color.warn
            null -> R.string.state_unknown to R.color.text_muted
        }
        views.setTextViewText(R.id.d_lock, context.getString(lockText))
        views.setTextColor(R.id.d_lock, context.getColor(lockColor))

        if (!compact) {
            // One chip for what matters right now, most urgent first.
            val status = dashboardStatus(car)
            views.setViewVisibility(R.id.d_status, if (status != null) View.VISIBLE else View.GONE)
            if (status != null) {
                views.setTextViewText(R.id.d_status, context.getString(status.first))
                views.setTextColor(R.id.d_status, context.getColor(status.second))
            }
            views.setOnClickPendingIntent(R.id.d_body, openAppIntent(context))
            views.setTextViewText(R.id.d_name, car.name)
            views.setTextViewText(
                R.id.d_range,
                car.rangeKm?.let { context.getString(R.string.range_km, it) } ?: "-- ${context.getString(R.string.unit_km)}",
            )
            views.setProgressBar(R.id.d_battery, 100, car.soc ?: 0, false)
            views.setViewVisibility(R.id.d_bolt, if (car.charging == true) View.VISIBLE else View.GONE)
            views.setTextViewText(
                R.id.d_updated,
                if (car.hasData) DateFormat.getTimeFormat(context).format(Date(car.updatedAt)) else "",
            )
            views.setOnClickPendingIntent(R.id.d_refresh, refreshIntent(context))
            views.setViewVisibility(R.id.d_refreshing, if (state.refreshing) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.d_refresh, if (state.refreshing) View.INVISIBLE else View.VISIBLE)
        }

        DASH_BUTTONS.forEach { button ->
            val phase = phase(state, button.action)
            views.setOnClickPendingIntent(button.button, quickIntent(context, button.action))
            views.setInt(
                button.button, "setBackgroundResource",
                when (phase) {
                    Phase.BUSY -> R.drawable.widget_btn_on
                    Phase.OK -> R.drawable.widget_btn_ok
                    Phase.ERR -> R.drawable.widget_btn_err
                    Phase.IDLE -> if (button.action.isActive(car)) activeBackground(button.action) else R.drawable.widget_btn
                },
            )
            views.setViewVisibility(button.progress, if (phase == Phase.BUSY) View.VISIBLE else View.GONE)
            applyIcon(context, views, button.icon, button.action, phase, car)
        }
        return views
    }

    private fun dashboardStatus(car: CarSnapshot): Pair<Int, Int>? = when {
        car.doorsOpen == true -> R.string.state_door_open to R.color.warn
        car.trunkOpen == true -> R.string.state_trunk_open to R.color.warn
        car.windowsOpen == true -> R.string.state_window_open to R.color.warn
        car.charging == true -> R.string.state_charging to R.color.ok
        car.climateOn == true -> R.string.state_climate_on to R.color.accent
        else -> null
    }

    // --------------------------------------------------------------- helpers

    private fun applyIcon(context: Context, views: RemoteViews, @IdRes id: Int, action: QuickAction, phase: Phase, car: CarSnapshot) {
        // No checkmarks: the button keeps its own icon and confirms through color and its new state.
        val tint = when (phase) {
            Phase.OK -> R.color.ok
            Phase.ERR -> R.color.err
            Phase.BUSY -> R.color.accent
            Phase.IDLE -> if (action.isActive(car)) activeTint(action) else R.color.text_primary
        }
        views.setImageViewResource(id, action.icon)
        views.setInt(id, "setColorFilter", context.getColor(tint))
    }

    /** Short confirmation for a 1x1 tile: the state the car is in now ("Locked", "Open", "On"…). */
    private fun doneLabel(action: QuickAction, car: CarSnapshot): Int = when (action) {
        QuickAction.LOCK -> R.string.state_locked
        QuickAction.UNLOCK -> R.string.state_unlocked
        QuickAction.TRUNK -> if (car.trunkOpen == true) R.string.state_open else R.string.state_closed
        QuickAction.CLIMATE -> if (car.climateOn == true) R.string.state_on else R.string.state_off
        QuickAction.WINDOWS -> if (car.windowsOpen == true) R.string.state_windows_open else R.string.state_windows_closed
        QuickAction.CHARGE -> if (car.charging == true) R.string.state_charging else R.string.state_charge_paused
        QuickAction.LOCATE, QuickAction.FLASH -> R.string.done_short
    }

    @ColorRes
    private fun activeTint(action: QuickAction): Int = when (action) {
        QuickAction.LOCK, QuickAction.CHARGE -> R.color.ok
        QuickAction.UNLOCK, QuickAction.TRUNK, QuickAction.WINDOWS -> R.color.warn
        else -> R.color.accent
    }

    @DrawableRes
    private fun activeDisc(action: QuickAction): Int = when (action) {
        QuickAction.LOCK, QuickAction.CHARGE -> R.drawable.widget_disc_ok
        QuickAction.UNLOCK, QuickAction.TRUNK, QuickAction.WINDOWS -> R.drawable.widget_disc_warn
        else -> R.drawable.widget_disc_on
    }

    @DrawableRes
    private fun activeBackground(action: QuickAction): Int = when (action) {
        QuickAction.LOCK -> R.drawable.widget_btn_ok
        QuickAction.UNLOCK, QuickAction.TRUNK, QuickAction.WINDOWS -> R.drawable.widget_btn_warn
        QuickAction.CHARGE -> R.drawable.widget_btn_ok
        else -> R.drawable.widget_btn_on
    }

    private fun phase(state: CarUiState, action: QuickAction): Phase {
        val now = SystemClock.elapsedRealtime()
        if (state.pending.any { (command, since) -> action.covers(command) && now - since < STALE_PENDING_MS }) {
            return Phase.BUSY
        }
        val result = state.lastResult ?: return Phase.IDLE
        if (!action.covers(result.command) || now - result.finishedAt > CarRepository.RESULT_SHOW_MS) return Phase.IDLE
        return when {
            result.ok -> Phase.OK
            result.error == ApiError.TOO_LATE -> Phase.ERR // the widget must say the tap wasn't sent
            result.error?.soft == true -> Phase.IDLE
            else -> Phase.ERR
        }
    }

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun quickIntent(context: Context, action: QuickAction): PendingIntent = PendingIntent.getBroadcast(
        context, 100 + action.ordinal,
        Intent(context, WidgetClickReceiver::class.java)
            .setAction(WidgetClickReceiver.ACTION_QUICK)
            .putExtra(WidgetClickReceiver.EXTRA_ACTION, action.ordinal),
        FLAGS,
    )

    private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 99, Intent(context, WidgetClickReceiver::class.java).setAction(WidgetClickReceiver.ACTION_REFRESH), FLAGS,
    )

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS,
    )
}
