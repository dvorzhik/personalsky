package com.personalsky.app.data.reminder

import android.content.Context
import com.personalsky.app.data.local.PersonalSkyDatabase
import com.personalsky.app.data.local.ReminderSettingsDao
import com.personalsky.app.data.local.ReminderSettingsEntity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ReminderSettings(
    val enabled: Boolean = false,
    val hour: Int = 20,
    val minute: Int = 0,
    val hapticsEnabled: Boolean = true,
    val soundEnabled: Boolean = false,
    val reducedMotionEnabled: Boolean = false
)

/**
 * Хранит настройки напоминаний в Room.
 *
 * Раньше настройки лежали в DataStore, но вместе с ним в приложение попадала
 * нативная библиотека libdatastore_shared_counter.so, собранная старым NDK
 * и вызывавшая замечание Google Play про страницы памяти 16 КБ. Room уже
 * используется проектом, поэтому отдельное хранилище больше не нужно.
 *
 * Старый файл DataStore при первом чтении разбирается и переносится в базу,
 * чтобы настройки существующих пользователей не потерялись.
 */
class ReminderSettingsRepository private constructor(
    private val context: Context,
    private val dao: ReminderSettingsDao
) {
    constructor(context: Context) : this(
        context = context,
        dao = PersonalSkyDatabase.getInstance(context).reminderSettingsDao()
    )

    suspend fun load(): ReminderSettings {
        val stored = dao.getById()
        if (stored == null) {
            val imported = importLegacySettings() ?: ReminderSettings()
            val entity = imported.toEntity()
            dao.upsert(entity)
            return entity.toSettings()
        }

        // Настройки в базе есть, но переноса ещё не было: так бывает при
        // обновлении с версии, где таблицы не существовало. Переносим один раз.
        if (!stored.importedFromDataStore) {
            val imported = importLegacySettings()
            if (imported != null) {
                val merged = imported.toEntity(importedFromDataStore = true)
                dao.upsert(merged)
                return merged.toSettings()
            }
            dao.markImported()
        }

        return stored.toSettings()
    }

    suspend fun save(settings: ReminderSettings) {
        dao.upsert(settings.toEntity(importedFromDataStore = true))
    }

    /**
     * Возвращает все локальные настройки к значениям по умолчанию.
     * Используется действием «Удалить все локальные данные».
     */
    suspend fun clear() {
        dao.deleteAll()
        // Старый файл тоже убираем, иначе после очистки данных перенос
        // вернул бы прежние настройки при следующем запуске.
        legacyDataStoreFile()?.delete()
    }

    private fun legacyDataStoreFile(): File? =
        File(
            context.filesDir,
            "$LEGACY_PREFERENCES_DIR/$LEGACY_PREFERENCES_NAME.preferences_pb"
        ).takeIf { it.exists() }

    private fun importLegacySettings(): ReminderSettings? {
        val file = legacyDataStoreFile() ?: return null
        return try {
            LegacyPreferencesReader.read(file)
        } catch (_: Exception) {
            // Повреждённый или незнакомый файл не должен мешать запуску.
            null
        }
    }

    private fun ReminderSettings.toEntity(importedFromDataStore: Boolean = true) =
        ReminderSettingsEntity(
            enabled = enabled,
            hour = hour.coerceIn(0, 23),
            minute = minute.coerceIn(0, 59),
            hapticsEnabled = hapticsEnabled,
            soundEnabled = soundEnabled,
            reducedMotionEnabled = reducedMotionEnabled,
            importedFromDataStore = importedFromDataStore
        )

    private fun ReminderSettingsEntity.toSettings() = ReminderSettings(
        enabled = enabled,
        hour = hour,
        minute = minute,
        hapticsEnabled = hapticsEnabled,
        soundEnabled = soundEnabled,
        reducedMotionEnabled = reducedMotionEnabled
    )

    private companion object {
        const val LEGACY_PREFERENCES_DIR = "datastore"
        const val LEGACY_PREFERENCES_NAME = "reminder_preferences"
    }

/**
 * Минимальный разбор файла Preferences, который писал DataStore.
 *
 * Формат — protobuf:
 *   message PreferenceMap { map<string, Value> preferences = 1; }
 *   message Value { oneof { bool boolean = 1; int32 integer = 2; ... } }
 * Нужны только булевы и целочисленные значения, остальные поля пропускаются,
 * поэтому ручного разбора достаточно и дополнительных зависимостей не требуется.
 */
private object LegacyPreferencesReader {
    private const val FIELD_PREFERENCES = 1
    private const val FIELD_KEY = 1
    private const val FIELD_VALUE = 2
    private const val VALUE_FIELD_BOOLEAN = 1
    private const val VALUE_FIELD_INTEGER = 2

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5

    private const val KEY_ENABLED = "reminder_enabled"
    private const val KEY_HOUR = "reminder_hour"
    private const val KEY_MINUTE = "reminder_minute"
    private const val KEY_HAPTICS = "haptics_enabled"
    private const val KEY_SOUND = "sound_enabled"
    private const val KEY_REDUCED_MOTION = "reduced_motion_enabled"

    fun read(file: File): ReminderSettings {
        var settings = ReminderSettings()
        for ((key, value) in parseMap(file.readBytes())) {
            settings = when (key) {
                KEY_ENABLED -> value.boolean?.let { settings.copy(enabled = it) } ?: settings
                KEY_HOUR -> value.integer?.let { settings.copy(hour = it.coerceIn(0, 23)) } ?: settings
                KEY_MINUTE -> value.integer?.let { settings.copy(minute = it.coerceIn(0, 59)) } ?: settings
                KEY_HAPTICS -> value.boolean?.let { settings.copy(hapticsEnabled = it) } ?: settings
                KEY_SOUND -> value.boolean?.let { settings.copy(soundEnabled = it) } ?: settings
                KEY_REDUCED_MOTION -> value.boolean?.let { settings.copy(reducedMotionEnabled = it) } ?: settings
                else -> settings
            }
        }
        return settings
    }

    private data class LegacyValue(val boolean: Boolean? = null, val integer: Int? = null)

    private fun parseMap(bytes: ByteArray): Map<String, LegacyValue> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val result = mutableMapOf<String, LegacyValue>()

        while (buffer.hasRemaining()) {
            val tag = readVarint(buffer) ?: break
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7L).toInt()

            if (field != FIELD_PREFERENCES || wire != WIRE_LENGTH_DELIMITED) {
                if (!skipField(buffer, wire)) break
                continue
            }

            val length = (readVarint(buffer) ?: break).toInt()
            if (length <= 0 || length > buffer.remaining()) break
            val end = buffer.position() + length

            var key: String? = null
            var value: LegacyValue? = null
            while (buffer.position() < end) {
                val entryTag = readVarint(buffer) ?: break
                val entryField = (entryTag ushr 3).toInt()
                val entryWire = (entryTag and 0x7L).toInt()

                when {
                    entryField == FIELD_KEY && entryWire == WIRE_LENGTH_DELIMITED -> {
                        key = readString(buffer)
                    }

                    entryField == FIELD_VALUE && entryWire == WIRE_LENGTH_DELIMITED -> {
                        value = readLengthDelimited(buffer)?.let { parseValue(it) }
                    }

                    else -> if (!skipField(buffer, entryWire)) break
                }
            }

            buffer.position(minOf(end, buffer.capacity()))
            if (key != null && value != null) result[key] = value
        }

        return result
    }

    private fun parseValue(bytes: ByteArray): LegacyValue {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var value = LegacyValue()
        while (buffer.hasRemaining()) {
            val tag = readVarint(buffer) ?: break
            when ((tag ushr 3).toInt()) {
                VALUE_FIELD_BOOLEAN -> value = value.copy(boolean = (readVarint(buffer) ?: break) != 0L)
                VALUE_FIELD_INTEGER -> value = value.copy(integer = (readVarint(buffer) ?: break).toInt())
                else -> if (!skipField(buffer, (tag and 0x7L).toInt())) break
            }
        }
        return value
    }

    private fun readString(buffer: ByteBuffer): String? =
        readLengthDelimited(buffer)?.let { String(it, Charsets.UTF_8) }

    private fun readLengthDelimited(buffer: ByteBuffer): ByteArray? {
        val length = (readVarint(buffer) ?: return null).toInt()
        if (length < 0 || length > buffer.remaining()) return null
        val chunk = ByteArray(length)
        buffer.get(chunk)
        return chunk
    }

    private fun readVarint(buffer: ByteBuffer): Long? {
        var shift = 0
        var result = 0L
        while (shift < 64) {
            if (!buffer.hasRemaining()) return null
            val byte = buffer.get().toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
        return null
    }

    private fun skipField(buffer: ByteBuffer, wire: Int): Boolean = when (wire) {
        WIRE_VARINT -> readVarint(buffer) != null
        WIRE_FIXED64 -> skipBytes(buffer, 8)
        WIRE_LENGTH_DELIMITED -> {
            val length = (readVarint(buffer) ?: return false).toInt()
            skipBytes(buffer, length)
        }

        WIRE_FIXED32 -> skipBytes(buffer, 4)
        else -> false
    }

    private fun skipBytes(buffer: ByteBuffer, count: Int): Boolean {
        if (count < 0 || count > buffer.remaining()) return false
        buffer.position(buffer.position() + count)
        return true
    }
}

}
