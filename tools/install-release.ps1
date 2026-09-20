<#
.SYNOPSIS
    Устанавливает на подключённый по adb телефон самый свежий release-APK проекта
    «Личный небосвод», предварительно показав его версию.

.DESCRIPTION
    Скрипт закрывает класс ошибок «взял не тот файл»:
      * adb и aapt2 ищутся автоматически в Android SDK (пути из local.properties,
        ANDROID_HOME, ANDROID_SDK_ROOT, %LOCALAPPDATA%\Android\Sdk) — adb не нужно
        держать в PATH;
      * версия APK и версия уже установленного приложения печатаются рядом, поэтому
        downgrade виден до запуска установки, а не после INSTALL_FAILED_VERSION_DOWNGRADE;
      * при попытке откатиться на старую сборку требуется явный -AllowDowngrade,
        с предупреждением, что Android удалит данные приложения.

.PARAMETER Apk
    Путь к APK. Если не задан — берётся самый свежий по времени записи файл вида
    PersonalSky-*-release.apk в -SearchRoot (по умолчанию корень проекта), а если
    таких нет — app\build\outputs\apk\release\app-release.apk.

.PARAMETER SearchRoot
    Каталог поиска свежего APK, если -Apk не задан. По умолчанию — корень проекта.

.PARAMETER Serial
    Серийный номер устройства (adb -s). Нужен, только если устройств несколько.

.PARAMETER Build
    Перед установкой выполнить gradlew assembleRelease. По умолчанию выключено:
    скрипт устанавливает уже собранный APK.

.PARAMETER AllowDowngrade
    Разрешить установку APK с versionCode меньше установленного (adb install -d).
    Android при этом удалит данные приложения.

.PARAMETER Force
    Установка без интерактивного подтверждения.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\tools\install-release.ps1
    Найти самый свежий release-APK и поставить его на единственное устройство.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\tools\install-release.ps1 -Apk "D:\KeysBackup\PersonalSky-v1.1.1\PersonalSky-1.1.1-release.apk" -Force
    Поставить конкретный APK без вопросов.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\tools\install-release.ps1 -Build -Serial 37281FDJH0033N
    Собрать release заново и поставить на конкретный аппарат.
#>

[CmdletBinding()]
param(
    [string]$Apk,
    [string]$SearchRoot,
    [string]$Serial,
    [switch]$Build,
    [switch]$AllowDowngrade,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$PackageId = 'com.personalsky.app'
$ProjectRoot = Split-Path -Parent $PSScriptRoot

function Write-Step {
    param([string]$Message)
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Warn2 {
    param([string]$Message)
    Write-Host "(!) $Message" -ForegroundColor Yellow
}

function Fail {
    param([string]$Message)
    Write-Host ''
    Write-Host "ОШИБКА: $Message" -ForegroundColor Red
    exit 1
}

# --- Поиск Android SDK ------------------------------------------------------
# Порядок: local.properties (то, что реально использует Gradle) -> переменные
# окружения -> стандартный путь установки Android Studio.
function Get-SdkRoot {
    $candidates = New-Object System.Collections.Generic.List[string]

    $localProperties = Join-Path $ProjectRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        foreach ($line in Get-Content -LiteralPath $localProperties) {
            if ($line -match '^\s*sdk\.dir\s*=\s*(.+?)\s*$') {
                $candidates.Add(($Matches[1] -replace '\\\\', '\'))
            }
        }
    }

    foreach ($name in @('ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if (-not [string]::IsNullOrWhiteSpace($value)) { $candidates.Add($value) }
    }

    $candidates.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk'))

    foreach ($candidate in $candidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    Fail 'Android SDK не найден. Проверьте sdk.dir в local.properties или задайте ANDROID_HOME.'
}

function Get-Executable {
    param(
        [string]$SdkRoot,
        [string]$RelativePath,
        [string]$Name
    )

    $direct = Join-Path $SdkRoot $RelativePath
    if (Test-Path -LiteralPath $direct) { return $direct }

    $fromPath = Get-Command $Name -ErrorAction SilentlyContinue
    if ($fromPath) { return $fromPath.Source }

    Fail "Не найден $Name. Ожидался путь: $direct"
}

# aapt2 лежит в build-tools; берём самую новую установленную версию.
function Get-Aapt2 {
    param([string]$SdkRoot)

    $buildToolsRoot = Join-Path $SdkRoot 'build-tools'
    if (Test-Path -LiteralPath $buildToolsRoot) {
        $candidate = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
            Sort-Object -Property Name -Descending |
            ForEach-Object { Join-Path $_.FullName 'aapt2.exe' } |
            Where-Object { Test-Path -LiteralPath $_ } |
            Select-Object -First 1
        if ($candidate) { return $candidate }
    }

    $fromPath = Get-Command 'aapt2' -ErrorAction SilentlyContinue
    if ($fromPath) { return $fromPath.Source }

    Fail "Не найден aapt2 в $buildToolsRoot — без него нельзя проверить versionCode APK."
}

# --- Поиск JDK для Gradle ---------------------------------------------------
# gradlew из чистой консоли падает с «JAVA_HOME is not set», если Java не в PATH.
# Android Studio работает, потому что использует встроенный JBR, поэтому ищем
# его же: сначала уважаем уже заданный JAVA_HOME, дальше перебираем типовые места.
function Get-JavaHome {
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME) -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return (Resolve-Path -LiteralPath $env:JAVA_HOME).Path
    }

    $candidates = New-Object System.Collections.Generic.List[string]

    foreach ($studioRoot in @(
            (Join-Path $env:ProgramFiles 'Android\Android Studio'),
            (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio'),
            (Join-Path ${env:ProgramFiles(x86)} 'Android\Android Studio')
        )) {
        if ([string]::IsNullOrWhiteSpace($studioRoot)) { continue }
        $candidates.Add((Join-Path $studioRoot 'jbr'))
    }

    $candidates.Add((Join-Path $env:ProgramFiles 'Java\jdk-25'))
    $candidates.Add((Join-Path $env:ProgramFiles 'Eclipse Adoptium'))

    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate) -or -not (Test-Path -LiteralPath $candidate)) { continue }

        $direct = Join-Path $candidate 'bin\java.exe'
        if (Test-Path -LiteralPath $direct) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }

        # У Eclipse Adoptium и ему подобных между корнем и bin лежит подкаталог версии.
        $nested = Get-ChildItem -LiteralPath $candidate -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName 'bin\java.exe' } |
            Where-Object { Test-Path -LiteralPath $_ } |
            Select-Object -First 1
        if ($nested) { return (Split-Path -Parent (Split-Path -Parent $nested)) }
    }

    Fail @'
JDK не найден, а он нужен для -Build. Задайте JAVA_HOME, например:
  $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
'@
}

# --- Чтение версии APK ------------------------------------------------------
function Get-ApkBadging {
    param(
        [string]$Aapt2,
        [string]$ApkPath
    )

    $raw = & $Aapt2 dump badging $ApkPath 2>$null
    $line = $raw | Select-String -Pattern '^package: name' | Select-Object -First 1
    if (-not $line) { Fail "Не удалось прочитать манифест APK: $ApkPath" }

    $text = $line.Line
    if ($text -notmatch "name=\x27([^ ]+)\x27") { Fail "В APK нет имени пакета: $ApkPath" }
    $packageName = $Matches[1]

    $versionCode = 0
    $versionName = 'нет'
    if ($text -match "versionCode=\x27(\d+)\x27") { $versionCode = [int]$Matches[1] }
    if ($text -match "versionName=\x27([^ ]*)\x27") { $versionName = $Matches[1] }


    [pscustomobject]@{
        PackageName = $packageName
        VersionCode = $versionCode
        VersionName = $versionName
    }
}

# --- Поиск самого свежего release-APK ---------------------------------------
function Find-LatestReleaseApk {
    param([string]$Root)

    if (-not (Test-Path -LiteralPath $Root)) { return @() }

    Get-ChildItem -LiteralPath $Root -Recurse -File -Filter 'PersonalSky-*-release.apk' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '\\(build|\.gradle|\.git)\\' }
}

function Select-ApkInteractive {
    param([string[]]$Candidates)

    Write-Host 'Найдено несколько release-APK, выберите нужный:' -ForegroundColor Cyan
    for ($i = 0; $i -lt $Candidates.Count; $i++) {
        Write-Host ('  [{0}] {1}' -f ($i + 1), $Candidates[$i])
    }

    while ($true) {
        $answer = Read-Host 'Номер файла (Enter - отмена)'
        if ([string]::IsNullOrWhiteSpace($answer)) { Fail 'Установка отменена пользователем.' }

        $index = 0
        if ([int]::TryParse($answer, [ref]$index) -and $index -ge 1 -and $index -le $Candidates.Count) {
            return $Candidates[$index - 1]
        }
        Write-Warn2 "Введите число от 1 до $($Candidates.Count)."
    }
}

# --- Устройства -------------------------------------------------------------
function Get-AdbDevices {
    param([string]$Adb)

    $lines = & $Adb devices 2>$null
    $devices = $lines | Select-Object -Skip 1 | ForEach-Object {
        if ($_ -match '^(\S+)\s+(device|offline|unauthorized)\s*$') {
            [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
        }
    }
    return @($devices)
}

function Get-InstalledVersion {
    param(
        [string]$Adb,
        [string]$TargetSerial
    )

    $output = & $Adb -s $TargetSerial shell dumpsys package $PackageId 2>$null
    if (-not $output) { return $null }

    $code = 0
    $name = ''
    foreach ($line in $output) {
        if ($line -match 'versionCode=(\d+)') { $code = [int]$Matches[1] }
        if ($line -match 'versionName=(\S+)') { $name = $Matches[1] }
    }

    if ($code -eq 0) { return $null }
    [pscustomobject]@{ VersionCode = $code; VersionName = $name }
}

# --- Основной сценарий ------------------------------------------------------
Write-Step 'Поиск Android SDK'
$sdkRoot = Get-SdkRoot
$adb = Get-Executable -SdkRoot $sdkRoot -RelativePath 'platform-tools\adb.exe' -Name 'adb'
$aapt2 = Get-Aapt2 -SdkRoot $sdkRoot
Write-Host "    SDK:   $sdkRoot"
Write-Host "    adb:   $adb"
Write-Host "    aapt2: $aapt2"

if ($Build) {
    Write-Step 'Сборка release (gradlew assembleRelease)'
    $gradlew = Join-Path $ProjectRoot 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $gradlew)) { Fail "Не найден $gradlew" }

    # gradlew.bat требует JAVA_HOME: подставляем найденный JDK в текущую сессию.
    $javaHome = Get-JavaHome
    $env:JAVA_HOME = $javaHome
    Write-Host "    JDK:   $javaHome"

    & $gradlew --project-dir $ProjectRoot assembleRelease
    if ($LASTEXITCODE -ne 0) { Fail "Сборка завершилась с кодом $LASTEXITCODE." }

    $builtApk = Join-Path $ProjectRoot 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path -LiteralPath $builtApk)) { Fail "Сборка прошла, но APK не найден: $builtApk" }
    Write-Host "    Собран: $builtApk"
    if (-not $Apk) { $Apk = $builtApk }
}

if (-not $Apk) {
    if (-not $SearchRoot) { $SearchRoot = $ProjectRoot }

    Write-Step "Поиск свежего release-APK в $SearchRoot"
    $found = @(Find-LatestReleaseApk -Root $SearchRoot | Sort-Object -Property LastWriteTime -Descending)

    if ($found.Count -eq 0) {
        Write-Warn2 'Готовых release-APK с именем PersonalSky-*-release.apk не найдено.'
        $defaultBuild = Join-Path $ProjectRoot 'app\build\outputs\apk\release\app-release.apk'
        if (Test-Path -LiteralPath $defaultBuild) {
            Write-Host "    Используется вывод сборки Gradle: $defaultBuild"
            $Apk = $defaultBuild
        } else {
            Fail 'Укажите путь явно: -Apk "путь\файл.apk" либо запустите с -Build.'
        }
    } elseif ($found.Count -eq 1) {
        $Apk = $found[0].FullName
    } else {
        $Apk = Select-ApkInteractive -Candidates ($found | Select-Object -ExpandProperty FullName)
    }
}

if (-not (Test-Path -LiteralPath $Apk)) { Fail "APK не найден: $Apk" }
$Apk = (Resolve-Path -LiteralPath $Apk).Path
$apkFile = Get-Item -LiteralPath $Apk

Write-Step 'Проверка APK'
$badging = Get-ApkBadging -Aapt2 $aapt2 -ApkPath $Apk
if ($badging.PackageName -ne $PackageId) {
    Fail "APK относится к другому пакету: $($badging.PackageName) вместо $PackageId"
}
Write-Host "    Файл:        $Apk"
Write-Host ("    Изменён:     {0:yyyy-MM-dd HH:mm:ss}" -f $apkFile.LastWriteTime)
Write-Host "    Пакет:       $($badging.PackageName)"
Write-Host "    Версия APK:  $($badging.VersionName) (versionCode $($badging.VersionCode))" -ForegroundColor Green

Write-Step 'Поиск устройств'
$devices = Get-AdbDevices -Adb $adb
$ready = @($devices | Where-Object { $_.State -eq 'device' })

if ($ready.Count -eq 0) {
    $states = ($devices | ForEach-Object { "$($_.Serial) [$($_.State)]" }) -join ', '
    if ($states) {
        Fail "Нет готовых устройств. Состояния: $states. Подтвердите отладку по USB на телефоне."
    }
    Fail 'Устройства не найдены. Подключите телефон по USB и включите отладку по USB.'
}

$target = $null
if ($Serial) {
    $target = $ready | Where-Object { $_.Serial -eq $Serial } | Select-Object -First 1
    if (-not $target) {
        Fail "Устройство $Serial не найдено. Доступны: $(($ready | ForEach-Object { $_.Serial }) -join ', ')`."
    }
} elseif ($ready.Count -eq 1) {
    $target = $ready[0]
} else {
    Write-Host 'Подключено несколько устройств:' -ForegroundColor Yellow
    $ready | ForEach-Object { Write-Host "    $($_.Serial)" }
    Fail 'Укажите устройство: -Serial <серийный номер>.'
}

Write-Host "    Устройство:  $($target.Serial)"

Write-Step 'Версия установленного приложения'
$installed = Get-InstalledVersion -Adb $adb -TargetSerial $target.Serial
if ($installed) {
    Write-Host "    Установлено: $($installed.VersionName) (versionCode $($installed.VersionCode))"
} else {
    Write-Host "    $PackageId не установлено — будет первая установка."
}

$installArgs = @('install', '-r')
$isDowngrade = $false

if ($installed) {
    if ($badging.VersionCode -lt $installed.VersionCode) {
        $isDowngrade = $true
        if (-not $AllowDowngrade) {
            Write-Warn2 "Это откат: APK versionCode $($badging.VersionCode) < установленного $($installed.VersionCode)."
            Fail 'Android отклонит такую установку (INSTALL_FAILED_VERSION_DOWNGRADE). Осознанный откат: добавьте -AllowDowngrade — данные приложения будут удалены.'
        }
        Write-Warn2 'Откат разрешён явно: Android удалит данные приложения (цели, историю, настройки).'
        $installArgs += '-d'
    } elseif ($badging.VersionCode -eq $installed.VersionCode) {
        Write-Warn2 "На устройстве уже versionCode $($installed.VersionCode) — та же версия будет переустановлена."
    } else {
        Write-Host "    Обновление:  $($installed.VersionCode) -> $($badging.VersionCode)" -ForegroundColor Green
    }
}

if (-not $Force) {
    Write-Host ''
    $answer = Read-Host "Установить $($badging.VersionName) (versionCode $($badging.VersionCode)) на $($target.Serial)? [y/N]"
    if ($answer -notmatch '^(y|Y|д|Д)$') { Fail 'Установка отменена пользователем.' }
}

Write-Step 'Установка'
& $adb -s $target.Serial @installArgs $Apk
if ($LASTEXITCODE -ne 0) { Fail "adb install завершился с кодом $LASTEXITCODE." }

$after = Get-InstalledVersion -Adb $adb -TargetSerial $target.Serial
Write-Host ''
if ($after) {
    $serial = $target.Serial
    Write-Host "Готово: на $serial установлено $($after.VersionName) (versionCode $($after.VersionCode))." -ForegroundColor Green
} else {
    Write-Host 'Установка завершена, но прочитать версию с устройства не удалось.' -ForegroundColor Yellow
}
if ($isDowngrade) {
    Write-Host 'Напоминание: это был откат, данные приложения удалены.' -ForegroundColor Yellow
}
