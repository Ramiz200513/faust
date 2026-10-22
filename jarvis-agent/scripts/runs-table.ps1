# Таблица прогонов ИИ-агента для научной части диплома.
#
# Читает журналы %USERPROFILE%\.jarvis-agent\runs\*.jsonl (по одному на задачу,
# их пишет RunJournal) и печатает markdown-таблицу: режим, итог, шаги, время,
# вопросы к человеку, подзадачи плана.
#
#   powershell -ExecutionPolicy Bypass -File scripts\runs-table.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\runs-table.ps1 -Since "2026-09-30 20:50"
#   powershell -ExecutionPolicy Bypass -File scripts\runs-table.ps1 -Since "2026-09-30" -Out C:\diplom\runs.md
#
# ВАЖНО для таблиц: «итог» здесь — мнение самого агента. До 30.09 любое done
# считалось успехом, включая отказы, поэтому старые журналы завышают успех.
# Для диплома итог стоит проверять по факту (файл на диске, сообщение в чате).

param(
    [string]$Since = '',
    [string]$Out = ''
)

$dir = Join-Path $env:USERPROFILE '.jarvis-agent\runs'
$from = if ($Since) { [DateTime]::Parse($Since) } else { [DateTime]::MinValue }

$rows = Get-ChildItem $dir -Filter *.jsonl | Where-Object { $_.LastWriteTime -ge $from } |
    Sort-Object LastWriteTime | ForEach-Object {
        $j = Get-Content $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
        # Поле mode появилось вместе с планировщиком; в старых журналах его нет.
        $mode = if ($j.mode) { $j.mode } else { 'single (до 30.09)' }
        [pscustomobject]@{
            Time      = $_.LastWriteTime.ToString('dd.MM HH:mm')
            Goal      = if ($j.goal.Length -gt 60) { $j.goal.Substring(0, 59) + '…' } else { $j.goal }
            Mode      = $mode
            Ok        = if ($j.completed) { 'да' } else { 'нет' }
            Steps     = $j.stepsTaken
            Seconds   = [Math]::Round($j.durationMs / 1000)
            Questions = if ($null -ne $j.questionsAsked) { $j.questionsAsked } else { '' }
            Subtasks  = if ($null -ne $j.subtasks) { $j.subtasks } else { '' }
        }
    }

$lines = @('| Время | Задача | Режим | Успех (по агенту) | Шагов | Секунд | Вопросов | Подзадач |',
           '|---|---|---|---|---|---|---|---|')
foreach ($r in $rows) {
    $lines += "| $($r.Time) | $($r.Goal) | $($r.Mode) | $($r.Ok) | $($r.Steps) | $($r.Seconds) | $($r.Questions) | $($r.Subtasks) |"
}
$lines += ''
$lines += "Прогонов: $(@($rows).Count), успешных по мнению агента: $(@($rows | Where-Object { $_.Ok -eq 'да' }).Count)"

if ($Out) {
    [IO.File]::WriteAllLines($Out, $lines, (New-Object Text.UTF8Encoding($true)))
    Write-Host "Сохранено: $Out"
} else {
    $lines | ForEach-Object { Write-Output $_ }
}
