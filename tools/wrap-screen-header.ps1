#requires -Version 5.1
<#
.SYNOPSIS
    Оборачивает верхнюю часть экранов в ScreenHeader и расставляет стандартные отступы.

.DESCRIPTION
    Скрипт для разовой миграции MainActivity.kt: находит в ScreenColumn пару
    «BrandLabel() + Column(...)» и заворачивает её в ScreenHeader, который сам
    задаёт зазор между названием продукта и заголовком, а Spacer(10/8/7/6/5.dp)
    внутри блока заголовка заменяет на TitleGap.

    Границы блока определяются подсчётом фигурных скобок, а не отступами:
    ручная расстановка отступов в предыдущих попытках разъехалась и сломала
    вложенность. После каждой правки скрипт проверяет, что баланс скобок
    в файле равен нулю, и откатывает файл, если баланс нарушен.

    Скрипт идемпотентен: экраны, уже обёрнутые в ScreenHeader, пропускаются.

.EXAMPLE
    .\tools\wrap-screen-header.ps1 -Path app\src\main\java\com\personalsky\app\MainActivity.kt
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Path
)

$ErrorActionPreference = 'Stop'

function Get-BraceBalance {
    param([string[]]$Lines, [int]$UpTo = -1)
    $limit = if ($UpTo -lt 0) { $Lines.Count } else { $UpTo }
    $depth = 0
    for ($i = 0; $i -lt $limit; $i++) {
        $clean = $Lines[$i] -replace '"[^"]*"', '' -replace "'[^']*'", '' -replace '//.*$', ''
        $depth += ([regex]::Matches($clean, '\{')).Count
        $depth -= ([regex]::Matches($clean, '\}')).Count
    }
    return $depth
}

$full = (Resolve-Path $Path).Path
$lines = [System.Collections.Generic.List[string]](Get-Content $full -Encoding UTF8)
$backup = Get-Content $full -Raw -Encoding UTF8

# Кандидаты: строка с BrandLabel(), за которой (через пустую строку) идёт
# открытие Column(...) { на следующем уровне вложенности.
$changed = 0
$index = 0
while ($index -lt $lines.Count) {
    $line = $lines[$index]
    if ($line -notmatch '^(\s*)BrandLabel\(\)\s*$') { $index++; continue }

    $indent = $Matches[1]
    $brandIndex = $index

    # Ищем начало Column после BrandLabel, пропуская пустые строки.
    $cursor = $brandIndex + 1
    while ($cursor -lt $lines.Count -and $lines[$cursor].Trim() -eq '') { $cursor++ }
    if ($cursor -ge $lines.Count -or $lines[$cursor] -notmatch '^\s*Column\(') { $index++; continue }

    $columnIndex = $cursor

    # Конец Column: ищем строку, на которой баланс скобок вернулся к нулю.
    # Первая строка Column( сама содержит '{', поэтому нулём она стать не может —
    # закрывающая скобка блока всегда идёт после него.
    $depth = 0
    $endIndex = -1
    $sawOpenBrace = $false
    for ($i = $columnIndex; $i -lt $lines.Count; $i++) {
        $clean = $lines[$i] -replace '"[^"]*"', '' -replace "'[^']*'", '' -replace '//.*$', ''
        $opened = ([regex]::Matches($clean, '\{')).Count
        $closed = ([regex]::Matches($clean, '\}')).Count
        $depth += $opened - $closed
        if ($opened -gt 0) { $sawOpenBrace = $true }
        if ($sawOpenBrace -and $depth -eq 0) { $endIndex = $i; break }
    }
    if ($endIndex -lt 0) { throw "Не найдена закрывающая скобка Column со строки $($columnIndex + 1)." }

    # Проверяем, не обёрнут ли уже этот экран.
    $before = $lines[$brandIndex - 1]
    if ($before -match '^\s*ScreenHeader \{\s*$') { $index = $endIndex + 1; continue }

    # Собираем блок: BrandLabel и вложенный Column со всеми его строками.
    $block = New-Object System.Collections.Generic.List[string]
    $block.Add("$indent" + 'ScreenHeader {')
    for ($i = $brandIndex; $i -le $endIndex; $i++) {
        if ($lines[$i].Trim() -eq '') { $block.Add(''); continue }
        $text = $lines[$i]
        if ($text -match '^(\s*)Spacer\(modifier = Modifier\.height\((?:5|6|7|8|10)\.dp\)\)\s*$') {
            $text = $Matches[1] + 'Spacer(modifier = Modifier.height(TitleGap))'
        }
        $block.Add('    ' + $text)
    }
    $block.Add("$indent}")

    # Заменяем исходный диапазон целиком — сдвиг на один уровень выполняется
    # в блоке, поэтому индексы за его пределами остаются валидными.
    $lines.RemoveRange($brandIndex, $endIndex - $brandIndex + 1)
    $lines.InsertRange($brandIndex, $block)

    $changed++
    if ((Get-BraceBalance -Lines $lines.ToArray()) -ne 0) {
        Set-Content $full -Value $backup -Encoding UTF8 -NoNewline
        throw "Баланс скобок нарушен после экрана со строки $($brandIndex + 1). Файл восстановлен."
    }

    $index = $brandIndex + $block.Count
}

Set-Content $full -Value $lines -Encoding UTF8
Write-Host "Обёрнуто экранов: $changed" -ForegroundColor Green
Write-Host ("Итоговый баланс скобок: {0}" -f (Get-BraceBalance -Lines $lines.ToArray())) -ForegroundColor Green
