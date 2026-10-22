# ============================================================================
#  pair-phone.ps1 — выписывает коды для привязки телефона к этому ПК.
#  Нужен только один раз на телефон (или после "Очистить данные").
#  Релей и агент должны быть запущены (start-jarvis.bat).
# ============================================================================

$TokenFile  = Join-Path $env:USERPROFILE '.jarvis-agent\token.properties'
$AdminToken = if ($env:JARVIS_ADMIN_TOKEN) { $env:JARVIS_ADMIN_TOKEN } else { 'dev-admin-change-me' }

function New-Code([hashtable]$Body) {
    Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/pairing-codes' `
        -Headers @{ 'X-Admin-Token' = $AdminToken } -ContentType 'application/json' `
        -Body ($Body | ConvertTo-Json)
}

try {
    if (-not (Test-Path $TokenFile)) { throw 'Агент ещё не привязан — сначала запусти start-jarvis.bat.' }

    # token.properties — формат "ключ=значение"; строки с # (комментарии) PowerShell пропускает сам.
    $props   = ConvertFrom-StringData (Get-Content $TokenFile -Raw)
    $agentId = $props['deviceId']
    if (-not $agentId) { throw "В $TokenFile нет deviceId." }

    # Код 1 — экран «Привязка» при первом открытии приложения (старая схема, пропускает в чат).
    $enter = New-Code @{ label = 'phone' }
    # Код 2 — экран «Устройства» (значок монитора): привязка именно к этому ПК.
    $pc    = New-Code @{ label = 'phone'; agentId = $agentId }

    Write-Host "`nКоды действуют 10 минут, каждый — один раз." -ForegroundColor Cyan
    Write-Host "  1) экран «Привязка» (первый запуск приложения):  $($enter.code)" -ForegroundColor White
    Write-Host "  2) чат -> «Устройства» -> привязать ПК:         $($pc.code)   (ПК: $agentId)" -ForegroundColor White
}
catch {
    Write-Host "`nОШИБКА: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host 'Проверь, что релей запущен (start-jarvis.bat).'
}
