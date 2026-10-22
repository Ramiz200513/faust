# ============================================================================
#  start-jarvis.ps1 — поднимает весь Jarvis одной командой:
#    1) Docker Desktop (если не запущен)
#    2) PostgreSQL + релей — оба в Docker, через docker-compose.yml
#    3) агент (сам привязывается)
#  и в конце печатает адрес, который нужно вписать в приложение.
#  Запуск: двойной клик по start-jarvis.bat рядом с этим файлом.
#
#  Релей теперь живёт в Docker (restart: unless-stopped) — он поднимается
#  сам при перезапуске Docker Desktop/Windows, отдельное окно PowerShell
#  ему больше не нужно. Для разработки (быстрая перезагрузка после правки
#  кода) по-прежнему можно запускать "./gradlew bootRun" вручную — см. README.
# ============================================================================

# Корень проекта: этот файл лежит в C:\diplom\jarvis-relay\scripts
$Diplom     = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$RelayDir   = Join-Path $Diplom 'jarvis-relay'
$AgentDir   = Join-Path $Diplom 'jarvis-agent'
$AgentHome  = Join-Path $env:USERPROFILE '.jarvis-agent'
$TokenFile  = Join-Path $AgentHome 'token.properties'
$LastIpFile = Join-Path $AgentHome 'last-ip.txt'
$AdminToken = if ($env:JARVIS_ADMIN_TOKEN) { $env:JARVIS_ADMIN_TOKEN } else { 'dev-admin-change-me' }

# ---------- маленькие помощники ----------

function Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }

# Docker жив, если "docker info" завершилась без ошибки (код выхода 0).
# Через cmd /c — чтобы PowerShell 5.1 не превращал stderr docker'а в исключение.
function Test-Docker {
    cmd /c "docker info >nul 2>&1"
    return ($LASTEXITCODE -eq 0)
}

# Агент уже запущен? Ищем java-процесс с его главным классом в командной строке.
# javaw.exe — это агент из автозапуска (ярлык в shell:startup, без окна консоли),
# java.exe — запущенный вручную через gradlew. Проверяем оба, иначе поднимем второй.
function Test-AgentRunning {
    [bool](Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match 'com\.jarvis\.agent\.AgentMain' })
}

# Ждём, пока условие станет true, рисуя точки. Не дождались — ошибка.
function Wait-Until([scriptblock]$Condition, [int]$TimeoutSec, [string]$What) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while (-not (& $Condition)) {
        if ($sw.Elapsed.TotalSeconds -gt $TimeoutSec) {
            throw "Не дождался: $What (прошло $TimeoutSec с)"
        }
        Write-Host '.' -NoNewline
        Start-Sleep -Seconds 2
    }
    Write-Host ''
}

# Открыть команду в отдельном окне PowerShell с понятным заголовком.
function Start-Window([string]$Title, [string]$Dir, [string]$Command) {
    Start-Process powershell -WorkingDirectory $Dir -ArgumentList @(
        '-NoExit', '-Command', "`$Host.UI.RawUI.WindowTitle='$Title'; $Command"
    )
}

try {
    # ---------- 1. Docker ----------
    Step 'Docker'
    if (-not (Test-Docker)) {
        $dockerExe = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
        if (-not (Test-Path $dockerExe)) { throw "Не нашёл Docker Desktop: $dockerExe" }
        Write-Host 'Docker не запущен — запускаю Docker Desktop (до 3 минут)'
        Start-Process $dockerExe
        Wait-Until { Test-Docker } 180 'запуск Docker'
    }
    Write-Host 'Docker работает.' -ForegroundColor Green

    # ---------- 2. PostgreSQL + релей ----------
    Step 'PostgreSQL + релей'
    $EnvFile = Join-Path $RelayDir '.env'
    if (-not (Test-Path $EnvFile)) {
        Copy-Item (Join-Path $RelayDir '.env.example') $EnvFile
        Write-Host "Создал $EnvFile из .env.example (значения по умолчанию — только для localhost)." -ForegroundColor Yellow
    }
    # --build: пересобрать образ релея, если код менялся с прошлого запуска.
    # --wait: не идти дальше, пока healthcheck обоих сервисов не скажет "готов"
    # (postgres — pg_isready, relay — слушает ли порт 8080; см. docker-compose.yml).
    Push-Location $RelayDir
    cmd /c "docker compose up -d --wait --build"
    $composeExit = $LASTEXITCODE
    Pop-Location
    if ($composeExit -ne 0) { throw 'docker compose up завершился с ошибкой' }
    Write-Host 'База и релей готовы: ws://localhost:8080/ws' -ForegroundColor Green

    # ---------- 3. Агент ----------
    Step 'Агент'
    if (Test-AgentRunning) {
        Write-Host 'Агент уже запущен.' -ForegroundColor Green
    } else {
        $agentArgs = 'ws://localhost:8080/ws'
        if (-not (Test-Path $TokenFile)) {
            # Первый запуск: сами выписываем код привязки и отдаём его агенту.
            Write-Host 'Агент ещё не привязан — выписываю код автоматически.'
            $body = @{ label = $env:COMPUTERNAME } | ConvertTo-Json
            $r = Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/pairing-codes' `
                -Headers @{ 'X-Admin-Token' = $AdminToken } -ContentType 'application/json' -Body $body
            $agentArgs = "$agentArgs $($r.code)"
        }
        Start-Window 'Jarvis agent' $AgentDir ".\gradlew run --args='$agentArgs'"
        Write-Host 'Агент запущен в отдельном окне.' -ForegroundColor Green
    }

    # ---------- 4. Адрес для телефона ----------
    Step 'Адрес для телефона'
    # Берём только настоящие (физические) сетевые карты, которые подключены.
    # Radmin, Hyper-V/WSL/Docker (vEthernet), VPN-адаптеры отсекаются.
    $virtual  = 'Radmin|Hyper-V|vEthernet|Virtual|VPN|TAP|WireGuard|Bluetooth'
    $adapters = Get-NetAdapter -Physical -ErrorAction SilentlyContinue |
        Where-Object { $_.Status -eq 'Up' -and $_.InterfaceDescription -notmatch $virtual -and $_.Name -notmatch $virtual }
    $ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $adapters.ifIndex -contains $_.InterfaceIndex -and $_.IPAddress -notlike '169.254.*' }

    if (-not $ips) {
        Write-Host 'Не нашёл подключённую Wi-Fi/Ethernet сеть.' -ForegroundColor Yellow
    } else {
        foreach ($ip in $ips) {
            $name = ($adapters | Where-Object ifIndex -eq $ip.InterfaceIndex).Name
            Write-Host ("  {0,-28} ws://{1}:8080/ws" -f $name, $ip.IPAddress) -ForegroundColor White
        }
        $mainIp = @($ips)[0].IPAddress

        # Сравниваем с прошлым запуском: сменилась сеть — адрес в приложении надо поменять.
        $lastIp = if (Test-Path $LastIpFile) { (Get-Content $LastIpFile -Raw).Trim() } else { '' }
        if ($lastIp -and $lastIp -ne $mainIp) {
            Write-Host "  Адрес сменился: был $lastIp, стал $mainIp — поменяй его в приложении." -ForegroundColor Yellow
        }
        New-Item -ItemType Directory -Force -Path $AgentHome | Out-Null
        Set-Content -Path $LastIpFile -Value $mainIp
    }

    # Tailscale (если стоит) даёт постоянный адрес 100.x, который не меняется от сети к сети.
    $ts = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.InterfaceAlias -like '*Tailscale*' }
    if ($ts) {
        Write-Host ("  {0,-28} ws://{1}:8080/ws  (постоянный)" -f 'Tailscale', $ts[0].IPAddress) -ForegroundColor Green
    }

    Write-Host "`nГотово. Релей работает в Docker (сам поднимется и после перезагрузки); окно 'Jarvis agent' не закрывай, пока пользуешься." -ForegroundColor Green
    Write-Host 'Привязать новый телефон: pair-phone.bat'
}
catch {
    Write-Host "`nОШИБКА: $($_.Exception.Message)" -ForegroundColor Red
}
