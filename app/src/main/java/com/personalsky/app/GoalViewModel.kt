package com.personalsky.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personalsky.app.data.local.CheckInType
import com.personalsky.app.data.local.GoalRepository
import com.personalsky.app.data.local.PersonalSkyDatabase
import com.personalsky.app.data.local.StoredGoal
import com.personalsky.app.data.reminder.ReminderScheduler
import com.personalsky.app.data.reminder.ReminderSettings
import com.personalsky.app.data.reminder.ReminderSettingsRepository
import com.personalsky.app.widget.PersonalSkyWidgetUpdater
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SkyStatistics(
    val totalStars: Int = 0,
    val totalBrightStars: Int = 0,
    val totalNebulae: Int = 0,
    val completedPaths: Int = 0,
    val currentStreak: Int = 0,
    val activeTitle: String? = null,
    val activeDay: Int = 0,
    val activeDurationDays: Int = 0
)

class GoalViewModel(application: Application) : AndroidViewModel(application) {
    private val database = PersonalSkyDatabase.getInstance(application)
    private val goalRepository = GoalRepository(
        goalDao = database.goalDao(),
        checkInDao = database.checkInDao()
    )
    private val reminderSettingsRepository = ReminderSettingsRepository(application)
    private var archivedCompletionId: String? = null

    private val _activeGoal = MutableStateFlow<StoredGoal?>(null)
    val activeGoal: StateFlow<StoredGoal?> = _activeGoal.asStateFlow()

    private val _completedGoal = MutableStateFlow<StoredGoal?>(null)
    val completedGoal: StateFlow<StoredGoal?> = _completedGoal.asStateFlow()

    private val _archivedGoals = MutableStateFlow<List<StoredGoal>>(emptyList())
    val archivedGoals: StateFlow<List<StoredGoal>> = _archivedGoals.asStateFlow()

    private val _reminderSettings = MutableStateFlow(ReminderSettings())
    val reminderSettings: StateFlow<ReminderSettings> = _reminderSettings.asStateFlow()

    private val _skyStatistics = MutableStateFlow(SkyStatistics())
    val skyStatistics: StateFlow<SkyStatistics> = _skyStatistics.asStateFlow()

    init {
        ReminderScheduler.createNotificationChannel(application)
        viewModelScope.launch {
            val settings = reminderSettingsRepository.load()
            _reminderSettings.value = settings
            updateActiveGoalState(settings)
            refreshArchivedGoals()
            refreshSkyStatistics()
        }
    }

    fun createGoal(title: String, durationDays: Int) {
        viewModelScope.launch {
            goalRepository.createGoal(title, durationDays)
            updateActiveGoalState(_reminderSettings.value)
        }
    }

    fun renameActiveGoal(title: String) {
        viewModelScope.launch {
            goalRepository.renameActiveGoal(title)
            updateActiveGoalState(_reminderSettings.value)
        }
    }

    fun saveReminderSettings(enabled: Boolean, hour: Int, minute: Int) {
        viewModelScope.launch {
            val settings = _reminderSettings.value.copy(
                enabled = enabled,
                hour = hour,
                minute = minute
            )
            reminderSettingsRepository.save(settings)
            _reminderSettings.value = settings
            updateActiveGoalState(settings)
        }
    }

    fun saveExperienceSettings(
        hapticsEnabled: Boolean,
        soundEnabled: Boolean,
        reducedMotionEnabled: Boolean
    ) {
        viewModelScope.launch {
            val settings = _reminderSettings.value.copy(
                hapticsEnabled = hapticsEnabled,
                soundEnabled = soundEnabled,
                reducedMotionEnabled = reducedMotionEnabled
            )
            reminderSettingsRepository.save(settings)
            _reminderSettings.value = settings
        }
    }

    fun savePersonalization(
        reminderEnabled: Boolean,
        hour: Int,
        minute: Int,
        hapticsEnabled: Boolean,
        soundEnabled: Boolean,
        reducedMotionEnabled: Boolean
    ) {
        viewModelScope.launch {
            val settings = ReminderSettings(
                enabled = reminderEnabled,
                hour = hour,
                minute = minute,
                hapticsEnabled = hapticsEnabled,
                soundEnabled = soundEnabled,
                reducedMotionEnabled = reducedMotionEnabled
            )
            reminderSettingsRepository.save(settings)
            _reminderSettings.value = settings
            updateActiveGoalState(settings)
        }
    }

    fun recordTodayStandard() {
        recordToday(CheckInType.STANDARD)
    }

    fun recordTodayBright() {
        recordToday(CheckInType.BRIGHT)
    }

    fun finishActiveGoalEarly() {
        viewModelScope.launch {
            val goal = goalRepository.getActiveGoal() ?: return@launch
            goalRepository.finishActiveGoalEarly()
            _activeGoal.value = null
            _completedGoal.value = goal
            ReminderScheduler.cancel(getApplication())
            PersonalSkyWidgetUpdater.requestUpdate(getApplication())
            refreshSkyStatistics()
        }
    }

    fun refreshArchivedGoals() {
        viewModelScope.launch {
            _archivedGoals.value = goalRepository.getArchivedGoals()
        }
    }

    fun refreshSkyStatistics() {
        viewModelScope.launch {
            refreshSkyStatisticsInternal()
        }
    }

    fun archiveCompletedGoal() {
        val goal = _completedGoal.value ?: return
        archivedCompletionId = goal.id
        _completedGoal.value = null

        viewModelScope.launch {
            goalRepository.archiveCompletedGoal(goal.id)
            refreshArchivedGoals()
            PersonalSkyWidgetUpdater.requestUpdate(getApplication())
            refreshSkyStatisticsInternal()
        }
    }

    /**
     * Фиксирует вариант звёздной справки за завершённой целью: тот текст,
     * который пользователь увидел на экране завершения, будет открываться
     * и из архива. Пока вариант не назначен, цель вернёт null и репозиторий
     * посчитает его прежним способом.
     */
    fun rememberGuideToneForCompletedGoal(goalId: String, toneIndex: Int) {
        viewModelScope.launch {
            goalRepository.assignGuideToneIndex(goalId, toneIndex)
            val completed = _completedGoal.value
            if (completed != null && completed.id == goalId) {
                _completedGoal.value = completed.copy(guideToneIndex = toneIndex)
            }
            refreshArchivedGoals()
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            goalRepository.clearAllData()
            reminderSettingsRepository.clear()
            val defaultSettings = ReminderSettings()
            _reminderSettings.value = defaultSettings
            archivedCompletionId = null
            _activeGoal.value = null
            _completedGoal.value = null
            _archivedGoals.value = emptyList()
            _skyStatistics.value = SkyStatistics()
            ReminderScheduler.cancel(getApplication())
            PersonalSkyWidgetUpdater.requestUpdate(getApplication())
        }
    }

    private fun recordToday(type: CheckInType) {
        viewModelScope.launch {
            goalRepository.recordToday(type)
            updateActiveGoalState(_reminderSettings.value)
        }
    }

    private suspend fun updateActiveGoalState(settings: ReminderSettings) {
        val goal = goalRepository.getActiveGoal()

        if (goal != null && shouldComplete(goal)) {
            goalRepository.completeActiveGoal()
            _activeGoal.value = null
            _completedGoal.value = goal
            ReminderScheduler.cancel(getApplication())
            PersonalSkyWidgetUpdater.requestUpdate(getApplication())
            refreshSkyStatisticsInternal()
            return
        }

        _activeGoal.value = goal
        if (goal == null) {
            val latestCompletedGoal = goalRepository.getLatestCompletedGoal()
            _completedGoal.value = if (latestCompletedGoal?.id == archivedCompletionId) {
                null
            } else {
                latestCompletedGoal
            }
            ReminderScheduler.cancel(getApplication())
        } else {
            _completedGoal.value = null
            ReminderScheduler.schedule(getApplication(), settings)
        }
        PersonalSkyWidgetUpdater.requestUpdate(getApplication())
        refreshSkyStatisticsInternal()
    }

    private suspend fun refreshSkyStatisticsInternal() {
        val activeGoal = _activeGoal.value
        val archivedGoals = goalRepository.getArchivedGoals()
        val allGoals = archivedGoals + listOfNotNull(activeGoal)
        val allCheckIns = allGoals.flatMap { it.checkIns }
        val stars = allCheckIns.count {
            it.type == CheckInType.STANDARD || it.type == CheckInType.BRIGHT
        }
        val brightStars = allCheckIns.count { it.type == CheckInType.BRIGHT }
        val nebulae = allCheckIns.count { it.type == CheckInType.MISSED }
        val activeDay = activeGoal?.let {
            (LocalDate.now().toEpochDay() - it.startEpochDay)
                .toInt()
                .coerceIn(0, it.durationDays - 1) + 1
        } ?: 0

        _skyStatistics.value = SkyStatistics(
            totalStars = stars,
            totalBrightStars = brightStars,
            totalNebulae = nebulae,
            completedPaths = archivedGoals.size,
            currentStreak = activeGoal?.currentStarStreak() ?: 0,
            activeTitle = activeGoal?.title,
            activeDay = activeDay,
            activeDurationDays = activeGoal?.durationDays ?: 0
        )
    }

    private fun StoredGoal.currentStarStreak(): Int {
        val todayIndex = (LocalDate.now().toEpochDay() - startEpochDay)
            .toInt()
            .coerceIn(0, durationDays - 1)
        val typeByDay = checkIns.associate { it.dayIndex to it.type }
        var streak = 0
        var index = todayIndex
        while (index >= 0) {
            val type = typeByDay[index]
            if (type == CheckInType.STANDARD || type == CheckInType.BRIGHT) {
                streak += 1
                index -= 1
            } else {
                break
            }
        }
        return streak
    }

    private fun shouldComplete(goal: StoredGoal): Boolean {
        val finalDayIndex = goal.durationDays - 1
        val finalDayEpoch = goal.startEpochDay + finalDayIndex
        val finalDayWasMarked = goal.checkIns.any { it.dayIndex == finalDayIndex }
        val finalDayHasPassed = LocalDate.now().toEpochDay() > finalDayEpoch
        return finalDayWasMarked || finalDayHasPassed
    }
}
