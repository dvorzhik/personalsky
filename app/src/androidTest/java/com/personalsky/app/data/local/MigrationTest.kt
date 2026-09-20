package com.personalsky.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет миграции схемы на реальном устройстве.
 *
 * Перенос настроек из DataStore в Room (v2 -> v3) затрагивает всех, кто
 * обновляется с 1.1.1: если миграция ошибочна, приложение либо теряет цели
 * и отметки, либо падает при запуске. Тест создаёт базу прошлой версии
 * тем же SQL, каким её создавал Room, наполняет данными, открывает её
 * через настоящий PersonalSkyDatabase и проверяет, что данные остались.
 *
 * MigrationTestHelper здесь не используется: его модуль room-migration
 * требует kotlinx-serialization 1.8.1, тогда как в проекте эта библиотека
 * зафиксирована на 1.7.3 зависимостями lifecycle, и конфликт версий
 * в тестовой конфигурации разрешить нельзя.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private lateinit var context: Context
    private lateinit var database: PersonalSkyDatabase

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun миграция_v2_v3_сохраняет_цели_и_отметки() {
        createVersion2Database { db ->
            db.execSQL(
                "INSERT INTO goals (id, title, durationDays, constellationId, " +
                    "startEpochDay, status, createdAtMillis, guideToneIndex) " +
                    "VALUES ('goal-1', 'Цель до миграции', 7, 'ursa-major', " +
                    "20000, 'ACTIVE', 1000, -1)"
            )
            db.execSQL(
                "INSERT INTO check_ins (id, goalId, dayIndex, dateEpochDay, type, " +
                    "createdAtMillis) VALUES ('ci-1', 'goal-1', 0, 20000, " +
                    "'STANDARD', 1000)"
            )
            db.execSQL(
                "INSERT INTO check_ins (id, goalId, dayIndex, dateEpochDay, type, " +
                    "createdAtMillis) VALUES ('ci-2', 'goal-1', 1, 20001, " +
                    "'BRIGHT', 1001)"
            )
        }

        // Открытие базы приложением запускает миграцию 2 -> 3.
        database = openDatabase()

        database.query("SELECT title FROM goals WHERE id = 'goal-1'", emptyArray())
            .use { cursor ->
                assertEquals("цель должна пережить миграцию", 1, cursor.count)
                cursor.moveToFirst()
                assertEquals("Цель до миграции", cursor.getString(0))
            }

        database.query("SELECT COUNT(*) FROM check_ins", emptyArray()).use { cursor ->
            cursor.moveToFirst()
            assertEquals("отметки должны пережить миграцию", 2, cursor.getInt(0))
        }
    }

    @Test
    fun миграция_v2_v3_создаёт_пустую_таблицу_настроек() {
        createVersion2Database { }
        database = openDatabase()

        // Настройки переносит репозиторий при первом чтении, поэтому
        // после миграции таблица существует, но пуста.
        database.query("SELECT COUNT(*) FROM reminder_settings", emptyArray()).use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }

        val columns = mutableListOf<String>()
        database.query("PRAGMA table_info(reminder_settings)", emptyArray()).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
        }

        assertEquals(
            listOf(
                "id", "enabled", "hour", "minute", "hapticsEnabled",
                "soundEnabled", "reducedMotionEnabled", "importedFromDataStore"
            ),
            columns
        )
    }

    /**
     * Создаёт базу версии 2 с той же схемой, что была до переноса настроек.
     * SQL повторяет сгенерированный Room, иначе тест проверял бы не ту схему.
     */
    private fun createVersion2Database(fill: (SupportSQLiteDatabase) -> Unit) {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `goals` (`id` TEXT NOT NULL, " +
                            "`title` TEXT NOT NULL, `durationDays` INTEGER NOT NULL, " +
                            "`constellationId` TEXT NOT NULL, " +
                            "`startEpochDay` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                            "`createdAtMillis` INTEGER NOT NULL, " +
                            "`guideToneIndex` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_goals_status` " +
                            "ON `goals` (`status`)"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `check_ins` (`id` TEXT NOT NULL, " +
                            "`goalId` TEXT NOT NULL, `dayIndex` INTEGER NOT NULL, " +
                            "`dateEpochDay` INTEGER NOT NULL, `type` TEXT NOT NULL, " +
                            "`createdAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                            "FOREIGN KEY(`goalId`) REFERENCES `goals`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_check_ins_goalId` " +
                            "ON `check_ins` (`goalId`)"
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "`index_check_ins_goalId_dayIndex` ON `check_ins` " +
                            "(`goalId`, `dayIndex`)"
                    )
                    fill(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()

        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            helper.writableDatabase
        }
    }

    private fun openDatabase(): PersonalSkyDatabase =
        Room.databaseBuilder(context, PersonalSkyDatabase::class.java, TEST_DB)
            .addMigrations(
                PersonalSkyDatabase.MIGRATION_1_2,
                PersonalSkyDatabase.MIGRATION_2_3
            )
            .build()

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
