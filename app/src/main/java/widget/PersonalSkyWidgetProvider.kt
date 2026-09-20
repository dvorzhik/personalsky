package com.personalsky.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.personalsky.app.MainActivity
import com.personalsky.app.R
import com.personalsky.app.data.local.CheckInType
import com.personalsky.app.data.local.GoalRepository
import com.personalsky.app.data.local.PersonalSkyDatabase
import com.personalsky.app.data.local.StoredGoal
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PersonalSkyWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAsync(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        updateAsync(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val manager = AppWidgetManager.getInstance(context)
                val component = ComponentName(context, PersonalSkyWidgetProvider::class.java)
                updateAsync(context, manager, manager.getAppWidgetIds(component))
            }
            else -> super.onReceive(context, intent)
        }
    }

    private fun updateAsync(
        context: Context,
        manager: AppWidgetManager,
        widgetIds: IntArray
    ) {
        if (widgetIds.isEmpty()) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val database = PersonalSkyDatabase.getInstance(context)
                val repository = GoalRepository(
                    goalDao = database.goalDao(),
                    checkInDao = database.checkInDao()
                )
                val goal = repository.getActiveGoal()
                widgetIds.forEach { widgetId ->
                    manager.updateAppWidget(widgetId, buildViews(context, goal))
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun buildViews(context: Context, goal: StoredGoal?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_personal_sky)

        if (goal == null) {
            views.setTextViewText(R.id.widget_star, context.getString(R.string.widget_symbol_empty))
            views.setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
            views.setTextViewText(R.id.widget_status, context.getString(R.string.widget_day_empty))
            views.setTextViewText(R.id.widget_day_label, context.getString(R.string.widget_day_label))
            views.setTextViewText(R.id.widget_day_value, context.getString(R.string.widget_day_placeholder))
        } else {
            val todayIndex = (LocalDate.now().toEpochDay() - goal.startEpochDay)
                .toInt()
                .coerceIn(0, goal.durationDays - 1)
            val todayType = goal.checkIns.firstOrNull { it.dayIndex == todayIndex }?.type

            views.setTextViewText(R.id.widget_star, context.getString(starSymbolRes(todayType)))
            views.setTextViewText(R.id.widget_title, goal.title)
            views.setTextViewText(R.id.widget_status, context.getString(statusRes(todayType)))
            views.setTextViewText(R.id.widget_day_label, context.getString(R.string.widget_day_label))
            views.setTextViewText(R.id.widget_day_value, "${todayIndex + 1}/${goal.durationDays}")
        }

        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context))
        return views
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            4101,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun starSymbolRes(type: CheckInType?): Int = when (type) {
        CheckInType.BRIGHT -> R.string.widget_symbol_bright
        CheckInType.STANDARD -> R.string.widget_symbol_standard
        CheckInType.MISSED -> R.string.widget_symbol_missed
        null -> R.string.widget_symbol_empty
    }

    private fun statusRes(type: CheckInType?): Int = when (type) {
        CheckInType.BRIGHT -> R.string.widget_status_bright
        CheckInType.STANDARD -> R.string.widget_status_standard
        CheckInType.MISSED -> R.string.widget_status_missed
        null -> R.string.widget_status_idle
    }

    companion object {
        const val ACTION_REFRESH = "com.personalsky.app.widget.REFRESH"
    }
}

object PersonalSkyWidgetUpdater {
    fun requestUpdate(context: Context) {
        context.sendBroadcast(
            Intent(context, PersonalSkyWidgetProvider::class.java).apply {
                action = PersonalSkyWidgetProvider.ACTION_REFRESH
            }
        )
    }
}
