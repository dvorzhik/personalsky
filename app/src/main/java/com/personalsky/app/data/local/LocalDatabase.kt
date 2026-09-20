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

@Database(
    entities = [GoalEntity::class, CheckInEntity::class],
    version = 2,
    exportSchema = false
)
abstract class PersonalSkyDatabase : RoomDatabase() {
    abstract fun goalDao(): GoalDao
    abstract fun checkInDao(): CheckInDao

    companion object {
        @Volatile
        private var instance: PersonalSkyDatabase? = null

        /**
         * v1 -> v2: у цели появился вариант звёздной справки.
         * Существующие цели получают -1 («ещё не назначен»), поэтому
         * у уже завершённых спринтов справка выберется по прежней логике.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE goals ADD COLUMN guideToneIndex INTEGER NOT NULL DEFAULT -1"
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
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
