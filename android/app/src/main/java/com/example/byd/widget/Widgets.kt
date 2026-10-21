package com.example.byd.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.example.byd.data.CarRepository
import com.example.byd.work.CommandService

/** 1x1 one-tap widgets. Nothing updates on a timer: they redraw only when the car state changes. */
abstract class QuickWidget(private val action: QuickAction) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val state = CarRepository.get(context).state.value
        ids.forEach { manager.updateAppWidget(it, WidgetRenderer.quick(context, action, state, manager, it)) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        manager.updateAppWidget(id, WidgetRenderer.quick(context, action, CarRepository.get(context).state.value, manager, id))
    }
}

class LockWidget : QuickWidget(QuickAction.LOCK)
class UnlockWidget : QuickWidget(QuickAction.UNLOCK)
class TrunkWidget : QuickWidget(QuickAction.TRUNK)
class ClimateWidget : QuickWidget(QuickAction.CLIMATE)
class LocateWidget : QuickWidget(QuickAction.LOCATE)
class FlashWidget : QuickWidget(QuickAction.FLASH)
class WindowsWidget : QuickWidget(QuickAction.WINDOWS)
class ChargeWidget : QuickWidget(QuickAction.CHARGE)

/** Resizable dashboard: full 4x2 layout, or one compact row when made short. */
class DashboardWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val state = CarRepository.get(context).state.value
        ids.forEach { manager.updateAppWidget(it, WidgetRenderer.dashboard(context, state, manager, it)) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        manager.updateAppWidget(id, WidgetRenderer.dashboard(context, CarRepository.get(context).state.value, manager, id))
    }
}

/** Every widget tap lands here: instant visual + haptic ack, then the command runs in [CommandService]. */
class WidgetClickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_QUICK -> {
                val action = QuickAction.entries.getOrNull(intent.getIntExtra(EXTRA_ACTION, -1)) ?: return
                CommandService.run(context, action)
            }
            ACTION_REFRESH -> CommandService.refresh(context)
        }
    }

    companion object {
        const val ACTION_QUICK = "com.example.byd.widget.QUICK"
        const val ACTION_REFRESH = "com.example.byd.widget.REFRESH"
        const val EXTRA_ACTION = "action"
    }
}
