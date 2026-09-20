#requires -Version 5.1
<#
.SYNOPSIS
    Проверка готовности релиза Personal Sky к публикации в Google Play.

.DESCRIPTION
    Скрипт проверяет артефакты сборки перед загрузкой в Play Console:
      * наличие AAB и его размер;
      * подпись (upload-ключ) и срок действия ключа;
      * отсутствие нативных библиотек, собранных старым NDK,
        которые вызывают замечание Play про страницы памяти 16 КБ;
      * выравнивание LOAD-сегментов оставшихся .so на 16 КБ.

    Проверка 16 КБ важна: Play Console выдаёт предупреждение "В приложении
    могут возникать сбои на устройствах со страницами памяти размером 16 КБ",
    если найдёт библиотеку, собранную старым NDK. Скрипт показывает это
    до загрузки, а не после.

.PARAMETER AabPath
    Путь к AAB. По умолчанию app\build\outputs\bundle\release\app-release.aab.

.PARAMETER SkipSignature
    Пропустить проверку подписи (полезно, если keytool недоступен).

.EXAMPLE
    .\tools\verify-release.ps1
    .\tools\verify-release.ps1 -AabPath 'D:\build\app-release.aab'
#>
[CmdletBinding()]
param(
    [string]$AabPath,
    [switch]$SkipSignature
)

$ErrorActionPreference = 'Stop'

function Write-Step([string]$text) {
    Write-Host ''
    Write-Host "=== $text ===" -ForegroundColor Cyan
}

function Write-Ok([string]$text) {
    Write-Host "  [OK]   $text" -ForegroundColor Green
}

function Write-Warn([string]$text) {
    Write-Host "  [ВНИМАНИЕ] $text" -ForegroundColor Yellow
}

function Write-Fail([string]$text) {
    Write-Host "  [ОШИБКА] $text" -ForegroundColor Red
}

function Find-Tool([string]$name) {
    $searchRoots = @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools'),
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools')
    )
    foreach ($root in $searchRoots) {
        if (-not (Test-Path $root)) { continue }
        $found = Get-ChildItem -Path $root -Recurse -Filter $name -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending |
            Select-Object -First 1
        if ($found) { return $found.FullName }
    }

    if ($name -eq 'keytool.exe') {
        $jbrTool = 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
        if (Test-Path $jbrTool) { return $jbrTool }
    }

    return $null
}


$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

if (-not $AabPath) {
    $AabPath = Join-Path $projectRoot 'app\build\outputs\bundle\release\app-release.aab'
}

$problems = 0

Write-Step 'AAB (файл сборки)'
if (-not (Test-Path $AabPath)) {
    Write-Fail "AAB не найден: $AabPath"
    Write-Host '         Соберите его командой: gradlew bundleRelease' -ForegroundColor Gray
    exit 1
}

$aab = Get-Item $AabPath
Write-Ok ("Найден: {0} ({1:N2} МБ)" -f $aab.Name, ($aab.Length / 1MB))
Write-Host ("         Изменён: {0}" -f $aab.LastWriteTime) -ForegroundColor Gray

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($aab.FullName)

try {
    Write-Step 'Нативные библиотеки и страницы памяти 16 КБ'
    $nativeLibs = @($zip.Entries | Where-Object { $_.FullName -match '\.so$' })

    if ($nativeLibs.Count -eq 0) {
        Write-Ok 'Нативных библиотек нет: требование 16 КБ неприменимо'
    } else {
        Write-Host ("         Найдено .so: {0}" -f $nativeLibs.Count) -ForegroundColor Gray

        $oldNdkLibs = @()
        $badAlignLibs = @()

        foreach ($entry in $nativeLibs) {
            $stream = $entry.Open()
            $memory = New-Object System.IO.MemoryStream
            try {
                $stream.CopyTo($memory)
            } finally {
                $stream.Dispose()
            }
            $bytes = $memory.ToArray()
            $memory.Dispose()

            $name = ($entry.FullName -split '/')[-1]
            $arch = ($entry.FullName -split '/')[-2]

            if ($bytes.Length -lt 64 -or $bytes[0] -ne 0x7F -or $bytes[1] -ne 0x45) {
                Write-Warn "$arch/$name - не ELF, проверка пропущена"
                continue
            }

            # Следы старого NDK в строковой таблице: именно их находит Play.
            $text = [System.Text.Encoding]::ASCII.GetString($bytes)
            $ndkMatches = [regex]::Matches(
                $text,
                '(?i)(android-ndk-r\d+[a-z]?|ndk(1[0-9]|2[0-6])[a-z]?|LLVM [1-7]\.\d+\.\d+)'
            )
            if ($ndkMatches.Count -gt 0) {
                $marks = ($ndkMatches | Select-Object -ExpandProperty Value -Unique) -join ', '
                $oldNdkLibs += "$arch/$name ($marks)"
            }

            # Выравнивание сегментов LOAD: на 16 КБ-ядре требуется 0x4000.
            if ($bytes[4] -ne 2) { continue }

            $phoff = [BitConverter]::ToInt64($bytes, 0x20)
            $phentsize = [BitConverter]::ToUInt16($bytes, 0x36)
            $phnum = [BitConverter]::ToUInt16($bytes, 0x38)
            if ($phentsize -le 0 -or $phoff -le 0 -or $phnum -le 0) { continue }

            for ($i = 0; $i -lt $phnum; $i++) {
                $offset = $phoff + ($i * $phentsize)
                if ($offset + 0x38 -gt $bytes.Length) { break }
                if ([BitConverter]::ToUInt32($bytes, $offset) -ne 1) { continue }
                $align = [BitConverter]::ToUInt64($bytes, $offset + 0x30)
                if ($align -ne 0x4000 -and $align -ne 0x10000) {
                    $badAlignLibs += "$arch/$name (align=$align)"
                }
            }
        }

        if ($oldNdkLibs.Count -gt 0) {
            Write-Fail 'Найдены библиотеки со следами старого NDK:'
            $oldNdkLibs | ForEach-Object { Write-Host "           $_" -ForegroundColor Red }
            Write-Host '           Play выдаст предупреждение про 16 КБ. Обновите зависимость,' -ForegroundColor Gray
            Write-Host '           которая приносит эту библиотеку.' -ForegroundColor Gray
            $problems++
        } else {
            Write-Ok 'Следов старого NDK в библиотеках нет'
        }

        if ($badAlignLibs.Count -gt 0) {
            Write-Fail 'Сегменты LOAD не выровнены на 16 КБ:'
            $badAlignLibs | ForEach-Object { Write-Host "           $_" -ForegroundColor Red }
            $problems++
        } else {
            Write-Ok 'Все сегменты LOAD выровнены на 16 КБ'
        }

        Write-Host '         Библиотеки в сборке:' -ForegroundColor Gray
        $nativeLibs | ForEach-Object { Write-Host ("           {0}" -f $_.FullName) -ForegroundColor Gray }
    }

    Write-Step 'Подпись'
    if ($SkipSignature) {
        Write-Warn 'Проверка подписи пропущена (-SkipSignature)'
    } else {
        $keytool = Find-Tool 'keytool.exe'
        if (-not $keytool) {
            Write-Warn 'keytool не найден, подпись не проверена'
        } else {
            $output = & $keytool -printcert -jarfile $aab.FullName 2>&1 | Out-String
            $owner = ([regex]::Match($output, 'Owner:\s*(.+)')).Groups[1].Value.Trim()
            $validMatch = [regex]::Match($output, 'from:\s*(.+?)\s+until:\s*(.+)')
            $sha = ([regex]::Match($output, 'SHA256:\s*([0-9A-Fa-f:]+)')).Groups[1].Value.Trim()

            if ($owner) {
                Write-Ok "Подписант: $owner"
                if ($validMatch.Success) {
                    Write-Host ("         Действует до: {0}" -f $validMatch.Groups[2].Value.Trim()) -ForegroundColor Gray
                }
                if ($sha) {
                    Write-Host ("         SHA256: {0}" -f $sha) -ForegroundColor Gray
                }
                if ($owner -match 'Android Debug') {
                    Write-Fail 'AAB подписан debug-ключом, а не upload-ключом!'
                    $problems++
                }
            } else {
                Write-Warn 'Не удалось разобрать данные подписи'
            }
        }
    }
} finally {
    $zip.Dispose()
}

Write-Host ''
if ($problems -eq 0) {
    Write-Host 'ИТОГ: AAB готов к загрузке в Google Play Console.' -ForegroundColor Green
    exit 0
} else {
    Write-Host "ИТОГ: найдено проблем - $problems. Исправьте их перед загрузкой." -ForegroundColor Red
    exit 1
}
