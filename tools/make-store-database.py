"""Создаёт базу приложения с историей для скриншотов листинга Google Play.

Запуск: python tools/make-store-database.py [-o <путь к .db>]

Зачем: скриншоты для магазина должны показывать живую историю — обычные
звёзды, яркие и туманности пропусков, — а не пустой экран после установки.
Собрать её на устройстве нельзя: `sqlite3` в системе Android отсутствует,
а русское название через `adb shell input text` не вводится. Поэтому база
создаётся здесь и копируется в приложение через `run-as` debug-сборки.
"""
import argparse
import sqlite3
import sys
from datetime import date, timedelta
from pathlib import Path

TITLE = "Утренняя зарядка"
DURATION_DAYS = 7
CONSTELLATION_ID = "ursa_7"
GUIDE_TONE_INDEX = 0

# Путь на семь дней. Пропуск в середине становится туманностью, дни
# с усилием — яркими звёздами, поэтому на карте видны все три состояния
# продукта сразу. Последний день намеренно не отмечен: приложение
# открывается на активном небосводе с приглашением зажечь звезду.
HISTORY = [
    (0, "STANDARD"),
    (1, "BRIGHT"),
    (2, None),
    (3, "STANDARD"),
    (4, "BRIGHT"),
    (5, "STANDARD"),
    (6, None),
]

SCHEMA = [
    """
    CREATE TABLE IF NOT EXISTS `goals` (
        `id` TEXT NOT NULL,
        `title` TEXT NOT NULL,
        `durationDays` INTEGER NOT NULL,
        `constellationId` TEXT NOT NULL,
        `startEpochDay` INTEGER NOT NULL,
        `status` TEXT NOT NULL,
        `createdAtMillis` INTEGER NOT NULL,
        `guideToneIndex` INTEGER NOT NULL DEFAULT -1,
        PRIMARY KEY(`id`)
    )
    """,
    "CREATE INDEX IF NOT EXISTS `index_goals_status` ON `goals` (`status`)",
    """
    CREATE TABLE IF NOT EXISTS `check_ins` (
        `id` TEXT NOT NULL,
        `goalId` TEXT NOT NULL,
        `dayIndex` INTEGER NOT NULL,
        `dateEpochDay` INTEGER NOT NULL,
        `type` TEXT NOT NULL,
        `createdAtMillis` INTEGER NOT NULL,
        PRIMARY KEY(`id`),
        FOREIGN KEY(`goalId`) REFERENCES `goals`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
    )
    """,
    "CREATE INDEX IF NOT EXISTS `index_check_ins_goalId` ON `check_ins` (`goalId`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_check_ins_goalId_dayIndex`"
    " ON `check_ins` (`goalId`, `dayIndex`)",
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
    """,
    """
    CREATE TABLE IF NOT EXISTS room_master_table (
        id INTEGER PRIMARY KEY,
        identity_hash TEXT
    )
    """,
]

# Room сверяет эту строку с отпечатком схемы из app/schemas/.../3.json.
# Если она пустая или отличается, Room считает базу чужой и открывает
# её как пустую — поэтому цель из истории просто не находилась.
IDENTITY_HASH = "f85b255083948b898b9a437291615d0d"

MILLIS_PER_DAY = 86_400_000

# Python считает порядковый номер дня от 0001-01-01, а LocalDate.toEpochDay()
# в Kotlin — от 1970-01-01. Без этой поправки приложение считало бы, что путь
# начался в далёком прошлом, и показывало бы неверный день экспедиции.
EPOCH_ORDINAL = date(1970, 1, 1).toordinal()


def epoch_day(day: date) -> int:
    return day.toordinal() - EPOCH_ORDINAL


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-o", "--output", default="store-screenshots.db")
    arguments = parser.parse_args()

    output = Path(arguments.output)
    if output.exists():
        output.unlink()

    # Датой начала берём «сегодня минус шесть дней»: путь идёт по календарю,
    # поэтому история заканчивается текущим днём.
    start = date.today() - timedelta(days=DURATION_DAYS - 1)

    connection = sqlite3.connect(output)
    try:
        # WAL сливаем в основной файл сразу: приложение открывает базу
        # со своими настройками журнала и без этого данные остались бы
        # в отдельном файле рядом, который мы не копируем.
        connection.execute("PRAGMA journal_mode = DELETE")
        for statement in SCHEMA:
            connection.execute(statement)
        # Отпечаток схемы и версия: по ним Room решает, что база «своя»
        # и миграции не нужны.
        connection.execute("PRAGMA user_version = 3")
        connection.execute(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
            (IDENTITY_HASH,),
        )
        connection.execute(
            "INSERT INTO goals (id, title, durationDays, constellationId,"
            " startEpochDay, status, createdAtMillis, guideToneIndex)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (
                "screen-goal",
                TITLE,
                DURATION_DAYS,
                CONSTELLATION_ID,
                epoch_day(start),
                "ACTIVE",
                epoch_day(start) * MILLIS_PER_DAY,
                GUIDE_TONE_INDEX,
            ),
        )
        for day_index, check_in_type in HISTORY:
            if check_in_type is None:
                continue
            day = start + timedelta(days=day_index)
            connection.execute(
                "INSERT INTO check_ins (id, goalId, dayIndex, dateEpochDay, type,"
                " createdAtMillis) VALUES (?, ?, ?, ?, ?, ?)",
                (
                    f"screen-{day_index}",
                    "screen-goal",
                    day_index,
                    epoch_day(day),
                    check_in_type,
                    epoch_day(day) * MILLIS_PER_DAY,
                ),
            )
        # Напоминание включено: на скриншоте настроек видно живое состояние,
        # а не выключенный по умолчанию переключатель.
        connection.execute(
            "INSERT INTO reminder_settings (id, enabled, hour, minute, hapticsEnabled,"
            " soundEnabled, reducedMotionEnabled, importedFromDataStore)"
            " VALUES (1, 1, 21, 0, 1, 0, 1, 1)"
        )
        connection.commit()
    finally:
        connection.close()

    print(f"База создана: {output} ({output.stat().st_size} байт)")
    print(f"Цель: «{TITLE}», {DURATION_DAYS} дней, начало {start}, статус ACTIVE")
    marked = [day for day, check_in in HISTORY if check_in]
    missed = DURATION_DAYS - len(marked)
    print(f"Отметок: {len(marked)} из {DURATION_DAYS}, пропусков: {missed}")
    print(f"Туманности: {[day + 1 for day, check_in in HISTORY if check_in is None]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
