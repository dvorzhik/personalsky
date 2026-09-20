# tools

Вспомогательные скрипты для разработки. Не влияют на сборку приложения.

## install-release.ps1

Устанавливает на подключённый по USB телефон release-APK проекта «Личный небосвод».

Зачем нужен: `adb` в PATH может отсутствовать, а рядом с проектом лежат несколько
release-APK разных версий — легко по ошибке поставить старый файл и получить
`INSTALL_FAILED_VERSION_DOWNGRADE`. Скрипт сам находит инструменты Android SDK,
сам определяет свежий APK и показывает версии рядом до начала установки.

### Что делает

1. Находит Android SDK (`local.properties` → `ANDROID_HOME`/`ANDROID_SDK_ROOT` →
   `%LOCALAPPDATA%\Android\Sdk`) и берёт из него `adb` и `aapt2`.
2. Определяет APK: самый свежий файл `PersonalSky-*-release.apk` по каталогу поиска,
   либо указанный явно через `-Apk`.
3. Читает из APK имя пакета и версию, сверяет пакет с `com.personalsky.app`.
4. Находит подключённое устройство и читает версию уже установленного приложения.
5. Сравнивает версии: обновление — идёт дальше, откат — останавливается с ошибкой,
   если явно не разрешён через `-AllowDowngrade`.

### Параметры

| Параметр | Назначение |
|---|---|
| `-Apk <путь>` | Конкретный APK. Без него берётся самый свежий найденный. |
| `-SearchRoot <каталог>` | Где искать свежий APK. По умолчанию — корень проекта. |
| `-Serial <номер>` | Устройство для `adb -s`. Нужен, только если подключено несколько. |
| `-Build` | Сначала выполнить `gradlew assembleRelease`, затем поставить результат. |
| `-AllowDowngrade` | Разрешить установку старой версии (`adb install -d`). **Данные приложения будут удалены.** |
| `-Force` | Не спрашивать подтверждение. |

### Примеры

Поставить самый свежий APK из бэкапов (скрипт спросит, какой, если их несколько):

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\install-release.ps1 -SearchRoot D:\KeysBackup
```

Поставить конкретный файл без вопросов:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\install-release.ps1 `
    -Apk "D:\KeysBackup\PersonalSky-v1.1.1\PersonalSky-1.1.1-release.apk" -Force
```

Собрать release заново и поставить на конкретный аппарат:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\install-release.ps1 -Build -Serial 37281FDJH0033N
```

Осознанный откат на предыдущую версию (сотрёт данные приложения):

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\install-release.ps1 `
    -Apk "D:\KeysBackup\PersonalSky-v1.1.0\PersonalSky-1.1.0-release.apk" -AllowDowngrade
```

### install-release.cmd

Обёртка для запуска двойным щелчком или из `cmd`. Аргументы пробрасываются как есть,
в конце выводится пауза, чтобы окно не закрылось:

```cmd
tools\install-release.cmd -Force
tools\install-release.cmd -Build -Serial 37281FDJH0033N
```

## verify-release.ps1

Проверяет AAB перед загрузкой в Google Play Console. Запускать после `bundleRelease`.

Зачем нужен: Play Console выдаёт предупреждение «В приложении могут возникать сбои
на устройствах со страницами памяти размером 16 КБ», если найдёт нативную
библиотеку, собранную старым NDK. Это предупреждение относится к библиотекам
зависимостей, а не к своему коду, и заметить источник по статусу в консоли
трудно: консоль обновляется с задержкой. Скрипт показывает проблему сразу
и указывает, какая именно библиотека её приносит.

### Что делает

1. Находит AAB (по умолчанию `app\build\outputs\bundle\release\app-release.aab`)
   и показывает размер и время сборки.
2. Открывает архив и разбирает каждый файл `.so`:
   * ищет в строковой таблице следы старого NDK (`ndk16a`, `LLVM 8.x` и подобные) —
     именно их находит Play Console;
   * читает заголовок ELF и проверяет, что сегменты `LOAD` выровнены
     на 16 КБ (`0x4000`) или 64 КБ (`0x10000`) — этого требует 16 КБ-ядро.
3. Проверяет подпись через `keytool`: владельца, срок действия и отпечаток SHA256.
   Отдельно предупреждает, если AAB подписан debug-ключом.
4. Возвращает код 0, если всё в порядке, и 1, если есть проблемы.

### Параметры

| Параметр | Назначение |
|---|---|
| `-AabPath <путь>` | Конкретный AAB. Без него берётся путь по умолчанию. |
| `-SkipSignature` | Не проверять подпись (если `keytool` недоступен). |

### Примеры

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\verify-release.ps1
powershell -ExecutionPolicy Bypass -File .\tools\verify-release.ps1 -AabPath "D:\build\app-release.aab"
```

### verify-release.cmd

```cmd
tools\verify-release.cmd
```

### Требования

* PowerShell 5.1+ (входит в Windows). Скрипты сохранены в UTF-8 **с BOM** — иначе
  PowerShell 5.1 читает их как ANSI и портит русский текст.
* Установленный Android SDK с `platform-tools\adb.exe` и `build-tools\*\aapt2.exe`.
* Включённая на телефоне отладка по USB и подтверждённая авторизация компьютера.
* Для `-Build` нужен JDK. Он не обязан быть в `PATH`: скрипт ищет его сам
  (сначала `JAVA_HOME`, затем встроенный JBR Android Studio) и подставляет
  в текущую сессию, поэтому `gradlew` работает и из чистой консоли.

## Тесты миграций

`app/src/androidTest/java/com/personalsky/app/data/local/MigrationTest.kt`
проверяет на подключённом устройстве, что обновление схемы базы не теряет
данные. Запуск:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat connectedDebugAndroidTest --no-configuration-cache
```

Зачем: версия 1.1.2 переносит настройки из DataStore в Room и добавляет
таблицу `reminder_settings` (схема 2 → 3). Ошибка в миграции означает
потерю целей и отметок или падение при запуске у всех, кто обновляется.
Тест создаёт базу версии 2, наполняет её данными, открывает через
настоящий `PersonalSkyDatabase` и проверяет, что цель и отметки на месте.

`MigrationTestHelper` из `androidx.room:room-testing` здесь не используется:
его модуль `room-migration` требует `kotlinx-serialization 1.8.1`, тогда как
в проекте эта библиотека зафиксирована на `1.7.3` зависимостями lifecycle.
Конфликт версий в тестовой конфигурации не разрешается, поэтому тест
создаёт базу прошлой версии напрямую через `SupportSQLiteOpenHelper`.

Схемы базы выгружаются в `app/schemas` (настройка `room.schemaLocation`):
по ним видно в diff, что изменилось между версиями.
