package com.personalsky.app.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.personalsky.app.data.local.GoalEntity
import com.personalsky.app.data.local.PersonalSkyDatabase
import com.personalsky.app.widget.PersonalSkyWidgetUpdater
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val database = PersonalSkyDatabase.getInstance(context)
                val activeGoal = database.goalDao().getActiveGoal()

                if (activeGoal == null || !activeGoal.isStillInProgress()) {
                    ReminderScheduler.cancel(context)
                    PersonalSkyWidgetUpdater.requestUpdate(context)
                    return@launch
                }

                val settings = ReminderSettingsRepository(context).load()
                ReminderScheduler.showDailyReminder(context)
                ReminderScheduler.schedule(context, settings)
                PersonalSkyWidgetUpdater.requestUpdate(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

private fun GoalEntity.isStillInProgress(): Boolean {
    val finalDayEpoch = startEpochDay + durationDays - 1
    return LocalDate.now().toEpochDay() <= finalDayEpoch
}

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val database = PersonalSkyDatabase.getInstance(context)
                val activeGoal = database.goalDao().getActiveGoal()

                if (activeGoal == null || !activeGoal.isStillInProgress()) {
                    ReminderScheduler.cancel(context)
                    PersonalSkyWidgetUpdater.requestUpdate(context)
                    return@launch
                }

                val settings = ReminderSettingsRepository(context).load()
                ReminderScheduler.createNotificationChannel(context)
                ReminderScheduler.schedule(context, settings)
                PersonalSkyWidgetUpdater.requestUpdate(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
