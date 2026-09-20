package com.personalsky.app.data.reminder

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private const val REMINDER_PREFERENCES_NAME = "reminder_preferences"

private val Context.reminderDataStore: DataStore<Preferences> by preferencesDataStore(
    name = REMINDER_PREFERENCES_NAME
)

data class ReminderSettings(
    val enabled: Boolean = false,
    val hour: Int = 20,
    val minute: Int = 0,
    val hapticsEnabled: Boolean = true,
    val soundEnabled: Boolean = false,
    val reducedMotionEnabled: Boolean = false
)

class ReminderSettingsRepository(private val context: Context) {
    private val dataStore: DataStore<Preferences>
        get() = context.reminderDataStore

    suspend fun load(): ReminderSettings {
        val preferences = dataStore.data.first()
        return ReminderSettings(
            enabled = preferences[ENABLED_KEY] ?: false,
            hour = preferences[HOUR_KEY] ?: 20,
            minute = preferences[MINUTE_KEY] ?: 0,
            hapticsEnabled = preferences[HAPTICS_ENABLED_KEY] ?: true,
            soundEnabled = preferences[SOUND_ENABLED_KEY] ?: false,
            reducedMotionEnabled = preferences[REDUCED_MOTION_ENABLED_KEY] ?: false
        )
    }

    suspend fun save(settings: ReminderSettings) {
        dataStore.edit { preferences ->
            preferences[ENABLED_KEY] = settings.enabled
            preferences[HOUR_KEY] = settings.hour.coerceIn(0, 23)
            preferences[MINUTE_KEY] = settings.minute.coerceIn(0, 59)
            preferences[HAPTICS_ENABLED_KEY] = settings.hapticsEnabled
            preferences[SOUND_ENABLED_KEY] = settings.soundEnabled
            preferences[REDUCED_MOTION_ENABLED_KEY] = settings.reducedMotionEnabled
        }
    }

    /**
     * Возвращает все локальные настройки к значениям по умолчанию.
     * Используется действием «Удалить все локальные данные».
     */
    suspend fun clear() {
        dataStore.edit { preferences -> preferences.clear() }
    }

    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("reminder_enabled")
        val HOUR_KEY = intPreferencesKey("reminder_hour")
        val MINUTE_KEY = intPreferencesKey("reminder_minute")
        val HAPTICS_ENABLED_KEY = booleanPreferencesKey("haptics_enabled")
        val SOUND_ENABLED_KEY = booleanPreferencesKey("sound_enabled")
        val REDUCED_MOTION_ENABLED_KEY = booleanPreferencesKey("reduced_motion_enabled")
    }
}
