package com.personalsky.app.data.local

import java.time.LocalDate
import java.util.UUID

data class StoredGoal(
    val id: String,
    val title: String,
    val durationDays: Int,
    val constellationId: String,
    val startEpochDay: Long,
    val checkIns: List<StoredCheckIn>,
    /**
     * Вариант звёздной справки, показанный при завершении созвездия.
     * null — вариант ещё не выбран (цель активна), и его нужно назначить.
     */
    val guideToneIndex: Int? = null
)

data class StoredCheckIn(
    val dayIndex: Int,
    val type: CheckInType
)

enum class CheckInType {
    STANDARD,
    BRIGHT,
    MISSED
}

class GoalRepository(
    private val goalDao: GoalDao,
    private val checkInDao: CheckInDao
) {
    suspend fun getActiveGoal(): StoredGoal? {
        materializeMissedDays()
        return goalDao.getActiveGoal()?.toStoredGoal()
    }

    suspend fun getLatestCompletedGoal(): StoredGoal? {
        return goalDao.getLatestCompletedGoal()?.toStoredGoal()
    }

    suspend fun getArchivedGoals(): List<StoredGoal> {
        return goalDao.getArchivedGoals().map { goal -> goal.toStoredGoal() }
    }

    suspend fun createGoal(title: String, durationDays: Int) {
        val existingGoal = goalDao.getActiveGoal()
        if (existingGoal != null) return

        val today = LocalDate.now()
        goalDao.insertGoal(
            GoalEntity(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                durationDays = durationDays,
                constellationId = constellationIdFor(durationDays),
                startEpochDay = today.toEpochDay(),
                status = ACTIVE_STATUS,
                createdAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun renameActiveGoal(title: String) {
        val goal = goalDao.getActiveGoal() ?: return
        val normalizedTitle = title.trim()
        if (normalizedTitle.length < 3) return
        goalDao.updateActiveGoalTitle(goal.id, normalizedTitle)
    }

    suspend fun recordToday(type: CheckInType) {
        val goal = goalDao.getActiveGoal() ?: return
        val today = LocalDate.now()
        val dayIndex = (today.toEpochDay() - goal.startEpochDay).toInt()

        if (dayIndex !in 0 until goal.durationDays) return

        checkInDao.insertOrReplace(
            CheckInEntity(
                id = "${goal.id}-$dayIndex",
                goalId = goal.id,
                dayIndex = dayIndex,
                dateEpochDay = today.toEpochDay(),
                type = type.name,
                createdAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun completeActiveGoal() {
        materializeMissedDays()
        val goal = goalDao.getActiveGoal() ?: return
        goalDao.markCompleted(goal.id)
    }

    suspend fun finishActiveGoalEarly() {
        val goal = goalDao.getActiveGoal() ?: return
        goalDao.markCompleted(goal.id)
    }

    suspend fun archiveCompletedGoal(goalId: String) {
        goalDao.archiveCompletedGoal(goalId)
    }

    /**
     * Назначает вариант звёздной справки цели, если он ещё не назначен.
     * Вызывается один раз при завершении созвездия, чтобы в архиве
     * открывался ровно тот текст, который пользователь уже видел.
     */
    suspend fun assignGuideToneIndex(goalId: String, toneIndex: Int) {
        val goal = goalDao.getGoalById(goalId) ?: return
        if (goal.guideToneIndex != GoalEntity.NOT_ASSIGNED_TONE) return
        goalDao.updateGuideToneIndex(goalId, toneIndex)
    }

    suspend fun clearAllData() {
        checkInDao.deleteAllCheckIns()
        goalDao.deleteAllGoals()
    }

    /**
     * Для каждого уже завершившегося дня без отметки создаёт запись MISSED.
     * Сегодня не становится туманностью: у пользователя всегда остаётся время
     * вернуться к цели до конца текущего дня.
     */
    private suspend fun materializeMissedDays() {
        val goal = goalDao.getActiveGoal() ?: return
        val today = LocalDate.now()
        val elapsedDays = (today.toEpochDay() - goal.startEpochDay).toInt()
        val lastPastDayIndex = minOf(elapsedDays - 1, goal.durationDays - 1)

        if (lastPastDayIndex < 0) return

        val existingDayIndexes = checkInDao.getForGoal(goal.id)
            .map { checkIn -> checkIn.dayIndex }
            .toSet()

        for (dayIndex in 0..lastPastDayIndex) {
            if (dayIndex !in existingDayIndexes) {
                checkInDao.insertOrReplace(
                    CheckInEntity(
                        id = "${goal.id}-$dayIndex",
                        goalId = goal.id,
                        dayIndex = dayIndex,
                        dateEpochDay = goal.startEpochDay + dayIndex,
                        type = CheckInType.MISSED.name,
                        createdAtMillis = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private suspend fun GoalEntity.toStoredGoal(): StoredGoal {
        val checkIns = checkInDao.getForGoal(id)
        return StoredGoal(
            id = id,
            title = title,
            durationDays = durationDays,
            constellationId = constellationId,
            startEpochDay = startEpochDay,
            checkIns = checkIns.map { checkIn ->
                StoredCheckIn(
                    dayIndex = checkIn.dayIndex,
                    type = CheckInType.valueOf(checkIn.type)
                )
            },
            guideToneIndex = guideToneIndex
                .takeIf { it != GoalEntity.NOT_ASSIGNED_TONE }
        )
    }

    private fun constellationIdFor(durationDays: Int): String = when (durationDays) {
        5 -> "cass_5"
        7 -> "ursa_7"
        else -> "orion_14"
    }

    private companion object {
        const val ACTIVE_STATUS = "ACTIVE"
    }
}
