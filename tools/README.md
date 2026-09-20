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

### Требования

* PowerShell 5.1+ (входит в Windows). Скрипты сохранены в UTF-8 **с BOM** — иначе
  PowerShell 5.1 читает их как ANSI и портит русский текст.
* Установленный Android SDK с `platform-tools\adb.exe` и `build-tools\*\aapt2.exe`.
* Включённая на телефоне отладка по USB и подтверждённая авторизация компьютера.
