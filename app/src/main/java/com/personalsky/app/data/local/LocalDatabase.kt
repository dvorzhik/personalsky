package com.personalsky.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "goals",
    indices = [Index(value = ["status"])]
)
data class GoalEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val durationDays: Int,
    val constellationId: String,
    val startEpochDay: Long,
    val status: String,
    val createdAtMillis: Long,
    /**
     * Вариант звёздной справки, который пользователь увидел при завершении
     * созвездия. Хранится у самой цели, чтобы из архива открывался тот же
     * текст, а не следующий по счёту.
     * Значение -1 означает «ещё не назначен» (цель активна).
     */
    val guideToneIndex: Int = NOT_ASSIGNED_TONE
) {
    companion object {
        const val NOT_ASSIGNED_TONE = -1
    }
}

@Entity(
    tableName = "check_ins",
    foreignKeys = [
        ForeignKey(
            entity = GoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["goalId"]),
        Index(value = ["goalId", "dayIndex"], unique = true)
    ]
)
data class CheckInEntity(
    @PrimaryKey
    val id: String,
    val goalId: String,
    val dayIndex: Int,
    val dateEpochDay: Long,
    val type: String,
    val createdAtMillis: Long
)

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals WHERE status = 'ACTIVE' LIMIT 1")
    fun observeActiveGoal(): Flow<GoalEntity?>

    @Query("SELECT * FROM goals WHERE status = 'ACTIVE' LIMIT 1")
    suspend fun getActiveGoal(): GoalEntity?

    @Query("SELECT * FROM goals WHERE id = :goalId LIMIT 1")
    suspend fun getGoalById(goalId: String): GoalEntity?

    @Query("SELECT * FROM goals WHERE status = 'COMPLETED' ORDER BY createdAtMillis DESC LIMIT 1")
    suspend fun getLatestCompletedGoal(): GoalEntity?

    @Query("SELECT * FROM goals WHERE status = 'ARCHIVED' ORDER BY createdAtMillis DESC")
    suspend fun getArchivedGoals(): List<GoalEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGoal(goal: GoalEntity)

    @Query("UPDATE goals SET title = :title WHERE id = :goalId AND status = 'ACTIVE'")
    suspend fun updateActiveGoalTitle(goalId: String, title: String)

    @Query("UPDATE goals SET status = 'COMPLETED' WHERE id = :goalId")
    suspend fun markCompleted(goalId: String)

    @Query("UPDATE goals SET status = 'ARCHIVED' WHERE id = :goalId")
    suspend fun archiveCompletedGoal(goalId: String)

    @Query("UPDATE goals SET guideToneIndex = :toneIndex WHERE id = :goalId")
    suspend fun updateGuideToneIndex(goalId: String, toneIndex: Int)

    @Query("DELETE FROM goals")
    suspend fun deleteAllGoals()
}

@Dao
interface CheckInDao {
    @Query("SELECT * FROM check_ins WHERE goalId = :goalId ORDER BY dayIndex ASC")
    fun observeForGoal(goalId: String): Flow<List<CheckInEntity>>

    @Query("SELECT * FROM check_ins WHERE goalId = :goalId ORDER BY dayIndex ASC")
    suspend fun getForGoal(goalId: String): List<CheckInEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(checkIn: CheckInEntity)

    @Query("DELETE FROM check_ins")
    suspend fun deleteAllCheckIns()
}

@Entity(tableName = "reminder_settings")
data class ReminderSettingsEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,
    val enabled: Boolean = false,
    val hour: Int = 20,
    val minute: Int = 0,
    val hapticsEnabled: Boolean = true,
    val soundEnabled: Boolean = false,
    val reducedMotionEnabled: Boolean = false,
    /**
     * Признак, что настройки уже перенесены из прежнего хранилища
     * (DataStore-файл reminder_preferences.preferences_pb).
     * Нужен, чтобы разовый перенос не затирал настройки после того,
     * как пользователь очистил данные и получил значения по умолчанию.
     */
    val importedFromDataStore: Boolean = false
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Dao
interface ReminderSettingsDao {
    @Query("SELECT * FROM reminder_settings WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int = ReminderSettingsEntity.SINGLETON_ID): ReminderSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: ReminderSettingsEntity)

    @Query("UPDATE reminder_settings SET importedFromDataStore = 1 WHERE id = :id")
    suspend fun markImported(id: Int = ReminderSettingsEntity.SINGLETON_ID)

    @Query("DELETE FROM reminder_settings")
    suspend fun deleteAll()
}

@Database(
    entities = [GoalEntity::class, CheckInEntity::class, ReminderSettingsEntity::class],
    version = 3,
    exportSchema = true
)
abstract class PersonalSkyDatabase : RoomDatabase() {
    abstract fun goalDao(): GoalDao
    abstract fun checkInDao(): CheckInDao
    abstract fun reminderSettingsDao(): ReminderSettingsDao

    companion object {
        @Volatile
        private var instance: PersonalSkyDatabase? = null

        /**
         * v1 -> v2: у цели появился вариант звёздной справки.
         * Существующие цели получают -1 («ещё не назначен»), поэтому
         * у уже завершённых спринтов справка выберется по прежней логике.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE goals ADD COLUMN guideToneIndex INTEGER NOT NULL DEFAULT -1"
                )
            }
        }

        /**
         * v2 -> v3: настройки напоминаний переехали из DataStore в Room.
         * Таблица создаётся пустой: перенос старых значений выполняет
         * репозиторий при первом чтении, поэтому данные не теряются,
         * а зависимость androidx.datastore уходит из приложения.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reminder_settings` (
                        `id` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `hour` INTEGER NOT NULL,
                        `minute` INTEGER NOT NULL,
                        `hapticsEnabled` INTEGER NOT NULL,
                        `soundEnabled` INTEGER NOT NULL,
                        `reducedMotionEnabled` INTEGER NOT NULL,
                        `importedFromDataStore` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): PersonalSkyDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PersonalSkyDatabase::class.java,
                    "personal_sky.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
