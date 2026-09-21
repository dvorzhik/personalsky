#requires -Version 5.1
<#
.SYNOPSIS
    Тап по элементу интерфейса на подключённом устройстве, найденному по тексту.

.DESCRIPTION
    Скрипт для проверки экранов приложения на реальном устройстве.
    Он снимает иерархию интерфейса через uiautomator, находит узел по тексту
    или его части и нажимает по центру его области.

    Зачем нужен: координаты на экране зависят от плотности, версии Android
    и содержимого экрана, поэтому задавать их руками вручную ненадёжно —
    проверка «нажал и посмотрел» превращается в угадывание. Поиск по тексту
    делает проверку воспроизводимой.

.PARAMETER Text
    Текст элемента (достаточно частичного совпадения).

.PARAMETER Serial
    Устройство для adb -s. Нужен, только если подключено несколько.

.PARAMETER NoTap
    Только показать найденные координаты, не нажимать.

.EXAMPLE
    .\tools\tap-by-text.ps1 -Text 'Поддержать проект'
    .\tools\tap-by-text.ps1 -Text 'Архив небосвода' -Serial 37281FDJH0033N
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Text,
    [string]$Serial,
    [switch]$NoTap
)

$ErrorActionPreference = 'Stop'

function Find-Adb {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
        'C:\Android\Sdk\platform-tools\adb.exe'
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { return $candidate }
    }
    $inPath = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($inPath) { return $inPath.Source }
    throw 'adb.exe не найден: проверьте Android SDK и PATH.'
}

$adb = Find-Adb
$adbArgs = @()
if ($Serial) { $adbArgs += @('-s', $Serial) }

$dumpFile = Join-Path $env:TEMP 'personal-sky-ui.xml'
& $adb @adbArgs shell uiautomator dump /sdcard/personal-sky-ui.xml | Out-Null
& $adb @adbArgs pull /sdcard/personal-sky-ui.xml $dumpFile | Out-Null

$xml = Get-Content $dumpFile -Raw -Encoding UTF8
$pattern = [regex]::Escape($Text)
$matches = $xml -split '><' |
    Where-Object { $_ -match ('text="[^"]*' + $pattern) } |
    ForEach-Object {
        if ($_ -match 'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            [pscustomobject]@{
                Text = $Matches[1].Replace('&#10;', ' ')
                Left = [int]$Matches[2]
                Top = [int]$Matches[3]
                Right = [int]$Matches[4]
                Bottom = [int]$Matches[5]
            }
        }
    }

if (-not $matches) {
    Write-Host "Элемент с текстом «$Text» на экране не найден." -ForegroundColor Red
    exit 1
}

foreach ($item in $matches) {
    $x = [int](($item.Left + $item.Right) / 2)
    $y = [int](($item.Top + $item.Bottom) / 2)
    Write-Host ("  {0} => ({1},{2})" -f $item.Text, $x, $y) -ForegroundColor Cyan
    if (-not $NoTap) {
        & $adb @adbArgs shell input tap $x $y | Out-Null
    }
}

if (-not $NoTap) {
    Start-Sleep -Seconds 3
    Write-Host 'Нажатие выполнено.' -ForegroundColor Green
}
exit 0
