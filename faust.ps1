<#
  ФАУСТ — лёгкий запуск всей системы.

  Двойной щелчок по start-faust.bat открывает меню. Или из консоли:
      .\start-faust.bat start    — запустить релей и агента (каждый день)
      .\start-faust.bat qr       — показать QR для привязки телефона
      .\start-faust.bat demo     — показать анимации (без релея и телефона)
      .\start-faust.bat record   — то же, но анимации видны в OBS/Zoom
      .\start-faust.bat phone    — поставить приложение на телефон по USB
      .\start-faust.bat status   — что сейчас запущено
      .\start-faust.bat stop     — остановить агента и релей

  Скрипт ничего не удаляет: stop останавливает контейнеры (docker compose
  stop), а база в томе jarvis-db-data сохраняется.
#>
param([string]$Command = '')

# Continue, а не Stop: в Windows PowerShell 5.1 любой вывод программы в поток
# ошибок (а docker и gradle пишут туда обычный прогресс) при Stop превращается
# в исключение. Ошибки проверяем сами — по коду возврата $LASTEXITCODE.
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$Root      = $PSScriptRoot
$RelayDir  = Join-Path $Root 'jarvis-relay'
$AgentDir  = Join-Path $Root 'jarvis-agent'
$AppDir    = Join-Path $Root 'jarvis'
$AgentLib  = Join-Path $AgentDir 'build\install\jarvis-agent\lib'
# По этой строке в командной строке процесса узнаём запущенного агента —
# так же его ищет scripts\install-autostart.ps1.
$MainClass = 'com.jarvis.agent.AgentMain'
$AppId     = 'com.jarvis.app'
$RelayContainer = 'jarvis-relay'
$Adb       = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$DockerExe = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
# Файл-пропуск агента на релей. Скрипт только проверяет, ЕСТЬ ли он
# (первый это запуск или нет), и никогда его не читает.
$TokenFile = Join-Path $env:USERPROFILE '.jarvis-agent\token.properties'
$AgentLog  = Join-Path $env:USERPROFILE '.jarvis-agent\agent.log'

# ---------------------------------------------------------------------------
#  Вывод
# ---------------------------------------------------------------------------

function Step([string]$text) { Write-Host ''; Write-Host ">> $text" -ForegroundColor Cyan }
function Ok([string]$text)   { Write-Host "   [OK] $text" -ForegroundColor Green }
function Warn([string]$text) { Write-Host "   [!]  $text" -ForegroundColor Yellow }
function Bad([string]$text)  { Write-Host "   [X]  $text" -ForegroundColor Red }
function Info([string]$text) { Write-Host "        $text" -ForegroundColor Gray }

# ---------------------------------------------------------------------------
#  Проверки
# ---------------------------------------------------------------------------

# Запускаем через cmd /c, чтобы PowerShell не превращал вывод docker в поток
# ошибок в красные «исключения»: нам нужен только код возврата.
function Test-DockerEngine {
    cmd /c "docker info >nul 2>&1"
    return ($LASTEXITCODE -eq 0)
}

function Test-Port([string]$hostName, [int]$port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $attempt = $client.BeginConnect($hostName, $port, $null, $null)
        return ($attempt.AsyncWaitHandle.WaitOne(1500) -and $client.Connected)
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Get-AgentProcesses {
    Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine -match [regex]::Escape($MainClass) }
}

function Get-PhoneSerials {
    if (-not (Test-Path $Adb)) { return @() }
    $lines = cmd /c "`"$Adb`" devices 2>nul"
    # Строки вида «4C190DLAQ0035C<TAB>device» — телефон подключён и разрешил отладку.
    return @($lines | Where-Object { $_ -match "`tdevice$" } | ForEach-Object { ($_ -split "`t")[0] })
}

# Самый свежий файл в указанных папках/файлах, или $null, если файлов нет.
function Get-NewestFile([string[]]$paths) {
    $newest = $null
    foreach ($p in $paths) {
        if (-not (Test-Path $p)) { continue }
        $item = Get-ChildItem $p -Recurse -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
        if ($item -and (-not $newest -or $item.LastWriteTimeUtc -gt $newest.LastWriteTimeUtc)) { $newest = $item }
    }
    return $newest
}

# Момент последнего изменения среди этих файлов (UTC).
function Get-NewestWrite([string[]]$paths) {
    $file = Get-NewestFile $paths
    if ($file) { return $file.LastWriteTimeUtc }
    return [DateTime]::MinValue
}

# Docker пишет время как 2026-09-24T07:05:31.123456789Z — девять знаков после
# точки .NET не понимает, обрезаем до семи.
# Не смогли разобрать (пустой ответ, непривычный формат) — $null: тогда
# вызывающий просто считает образ устаревшим и пересобирает, без ошибки.
function ConvertFrom-DockerTime([string]$text) {
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    $clean = $text.Trim() -replace '(\.\d{7})\d+', '$1'
    $parsed = [DateTimeOffset]::MinValue
    $ok = [DateTimeOffset]::TryParse($clean, [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::AssumeUniversal, [ref]$parsed)
    if ($ok) { return $parsed.UtcDateTime }
    return $null
}

# ---------------------------------------------------------------------------
#  Java 21 и сборка агента
# ---------------------------------------------------------------------------

# Агент собран под Java 21. Ищем её там, где она бывает: JAVA_HOME,
# установка Adoptium и JDK, который Gradle скачал сам (~\.gradle\jdks).
# Версию проверяем по файлу release внутри JDK, а не по имени папки.
function Find-Java21 {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    foreach ($base in @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:USERPROFILE\.gradle\jdks")) {
        if (Test-Path $base) { $candidates += (Get-ChildItem $base -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }) }
    }
    # $jdkHome, а не $home: $HOME — встроенная переменная PowerShell, её не перезаписать.
    foreach ($jdkHome in $candidates) {
        $javaw = Join-Path $jdkHome 'bin\javaw.exe'
        $release = Join-Path $jdkHome 'release'
        if ((Test-Path $javaw) -and (Test-Path $release) -and ((Get-Content $release -Raw) -match 'JAVA_VERSION="21')) {
            return $javaw
        }
    }
    return $null
}

# Готовая сборка свежее исходников? Тогда Gradle запускать не нужно — это
# и даёт старт агента за пару секунд вместо 15–30.
function Test-AgentBuildFresh {
    $jar = Get-ChildItem $AgentLib -Filter 'jarvis-agent-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $jar) { return $false }
    $sources = Get-NewestWrite @((Join-Path $AgentDir 'src'), (Join-Path $AgentDir 'build.gradle.kts'))
    return ($jar.LastWriteTimeUtc -ge $sources)
}

function Build-Agent {
    if (Test-AgentBuildFresh) { return $true }
    Info 'Код агента менялся — собираю (~20–40 с, дальше старт будет мгновенным)...'
    Push-Location $AgentDir
    try {
        .\gradlew.bat installDist --console=plain -q
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($code -ne 0) { Bad 'Агент не собрался — см. сообщения Gradle выше.'; return $false }
    # Если исходник «потрогали», но не изменили, Gradle скажет «всё актуально»
    # и jar не перепишет — тогда сборка казалась бы устаревшей при каждом
    # запуске. Отмечаем: сборка сверена с исходниками сейчас.
    $jar = Get-ChildItem $AgentLib -Filter 'jarvis-agent-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jar) { $jar.LastWriteTime = Get-Date }
    return $true
}

# ---------------------------------------------------------------------------
#  Шаги
# ---------------------------------------------------------------------------

function Ensure-Docker {
    Step 'Docker'
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Bad 'Docker не установлен. Нужен Docker Desktop: https://www.docker.com/products/docker-desktop/'
        return $false
    }
    if (Test-DockerEngine) { Ok 'Docker уже работает'; return $true }

    if (-not (Test-Path $DockerExe)) {
        Bad "Docker Desktop не найден по пути $DockerExe — запусти его вручную и повтори."
        return $false
    }
    Info 'Запускаю Docker Desktop — это самый долгий шаг (обычно 30–90 с).'
    Info 'Совет: в настройках Docker Desktop включи «Start Docker Desktop when you sign in» —'
    Info 'тогда он будет готов к моменту, когда ты откроешь это меню.'
    Start-Process $DockerExe | Out-Null
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        Write-Host '.' -NoNewline -ForegroundColor DarkGray
        if (Test-DockerEngine) { Write-Host ''; Ok 'Docker запущен'; return $true }
    }
    Write-Host ''
    Bad 'Docker не ответил за 3 минуты. Открой Docker Desktop и посмотри, что он пишет.'
    return $false
}

# Собрать образ релея из jar, собранного на ЭТОМ компьютере (Dockerfile.local).
#
# Зачем: основной Dockerfile собирает jar внутри Docker и при сброшенном кэше
# заново качает из интернета Gradle и все библиотеки — 02.10 это заняло больше
# 10 минут на медленной сети. На ПК разработчика всё это уже лежит в кэше
# Gradle, сборка идёт 20–40 секунд и интернет не нужен. На защите это значит:
# пересборка релея не зависит от сети в аудитории.
#
# Возвращает $true, если образ jarvis-relay-relay:latest собран.
function Build-RelayLocally {
    Push-Location $RelayDir
    try {
        # Именно «.\gradlew.bat»: при переменной NoDefaultCurrentDirectoryInExePath
        # cmd не ищет программы в текущей папке, и без «.\» сборка не стартует.
        cmd /c ".\gradlew.bat bootJar -q >nul 2>&1"
        if ($LASTEXITCODE -ne 0) { return $false }
        # bootJar кладёт рядом и «-plain.jar» без зависимостей — он нам не нужен.
        $jar = Get-ChildItem 'build\libs\*.jar' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike '*-plain.jar' } |
            Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
        if (-not $jar) { return $false }
        # Отдельная папка-контекст с одним файлом: .dockerignore релея исключает
        # build/ целиком, а здесь контекстом служит сама эта папка.
        $context = Join-Path $RelayDir 'build\docker-local'
        New-Item -ItemType Directory -Force $context | Out-Null
        Copy-Item $jar.FullName (Join-Path $context 'app.jar') -Force
        cmd /c "docker build -q -f Dockerfile.local -t jarvis-relay-relay:latest build\docker-local >nul 2>&1"
        return ($LASTEXITCODE -eq 0)
    } finally {
        Pop-Location
    }
}

function Start-Relay {
    Step 'Релей и база'

    # ПК подключается к релею САМ, по кнопке «Подключить», без кода от
    # администратора (открытая регистрация). Для работы дома, в своей Wi-Fi, это
    # то, что нужно. Переменная окружения важнее файла .env, поэтому действует
    # всегда, когда релей поднимает этот скрипт.
    # ВНИМАНИЕ: на публичном сервере так делать не стоит — зарегистрировать
    # агента смог бы любой, кто знает адрес. Там запускай релей без скрипта.
    $env:JARVIS_OPEN_REGISTRATION = 'true'

    # Уже работает, с открытой регистрацией, и код релея с тех пор не менялся?
    # Тогда ничего не трогаем — это секунда вместо полминуты.
    $running = (docker inspect -f '{{.State.Running}}' $RelayContainer 2>$null) -eq 'true'
    $relayEnv = (docker inspect -f '{{range .Config.Env}}{{.}};{{end}}' $RelayContainer 2>$null) -join ''
    $openReg = $relayEnv -match 'JARVIS_OPEN_REGISTRATION=true'
    # Пересобираем образ, только если ТОЧНО знаем, что код релея менялся после
    # сборки. Если Docker не ответил или дата не разобралась — не пересобираем:
    # docker compose up и так соберёт образ сам, если его вообще нет, а лишняя
    # «перестраховочная» пересборка стоила бы 15+ секунд каждого запуска.
    # Сразу после запуска Docker Desktop движок уже отвечает, но контейнеры
    # подгружает не мгновенно: первый docker inspect бывал пустым. Тогда скрипт
    # решал «не знаю — не пересобираю» и поднимал СТАРЫЙ образ релея (так и
    # случилось 30.09 после смены протокола). Поэтому несколько попыток.
    $imageId = $null
    for ($i = 0; $i -lt 5 -and -not $imageId; $i++) {
        $imageId = docker inspect -f '{{.Image}}' $RelayContainer 2>$null
        if (-not $imageId) { Start-Sleep -Seconds 2 }
    }
    $needBuild = $false
    $changedFile = $null
    if ($imageId) {
        $imageTime = ConvertFrom-DockerTime (docker image inspect -f '{{.Created}}' $imageId 2>$null)
        # Бывает, что образ, из которого запущен контейнер, Docker уже не
        # находит (после пересборки и чистки). Тогда сравниваем со временем
        # создания самого контейнера: он всегда моложе своего образа, так что
        # «код новее контейнера» надёжно значит «код новее образа» (30.09
        # без этого релей не пересобрался после смены протокола).
        if (-not $imageTime) {
            $imageTime = ConvertFrom-DockerTime (docker inspect -f '{{.Created}}' $RelayContainer 2>$null)
        }
        $newest = Get-NewestFile @((Join-Path $RelayDir 'src'), (Join-Path $RelayDir 'build.gradle.kts'), (Join-Path $RelayDir 'Dockerfile'))
        if ($imageTime -and $newest -and $newest.LastWriteTimeUtc -gt $imageTime) {
            $needBuild = $true
            $changedFile = $newest.FullName.Replace($RelayDir + '\', '')
        }
    }
    if ($running -and $openReg -and -not $needBuild -and (Test-Port 'localhost' 8080)) {
        Ok 'Релей уже работает'
        return $true
    }

    Push-Location $RelayDir
    try {
        if ($needBuild) {
            Info ("Изменён $changedFile после сборки образа — пересобираю релей (обычно 30–60 с)...")
            # Сначала быстрый путь: jar собирает Gradle на этом ПК, без интернета.
            # Не вышло (нет Java, сломан кэш Gradle) — прежний путь: сборка внутри
            # Docker, она сама скачает всё нужное, но это может занять 10–15 минут.
            if (Build-RelayLocally) {
                docker compose up -d --wait
            } else {
                Warn 'Локальная сборка не удалась — собираю внутри Docker (дольше, нужен интернет)...'
                docker compose up -d --wait --build
            }
        } else {
            Info 'Запускаю контейнеры (~15–30 с, релей ждёт готовности базы)...'
            docker compose up -d --wait
        }
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($code -ne 0) {
        Bad 'Релей не поднялся. Логи: docker compose logs relay  (в папке jarvis-relay)'
        return $false
    }
    if (Test-Port 'localhost' 8080) { Ok 'Релей работает: ws://localhost:8080/ws' }
    else { Warn 'Контейнер запущен, но порт 8080 пока не отвечает — подожди несколько секунд.' }
    return $true
}

# Запущенный агент — из этого проекта, или старая копия из автозапуска?
function Test-OurAgent($process) {
    return $process.CommandLine -match [regex]::Escape($AgentDir)
}

function Start-Agent([bool]$showQr) {
    Step 'Агент на этом ПК'
    $javaw = Find-Java21
    if (-not $javaw) {
        Bad 'Не нашёл Java 21. Установи Temurin 21: https://adoptium.net/temurin/releases/?version=21'
        return $false
    }
    # Сначала сборка: без неё не понять, устарел ли уже запущенный агент.
    if (-not (Build-Agent)) { return $false }
    $jar = Get-ChildItem $AgentLib -Filter 'jarvis-agent-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1

    $running = @(Get-AgentProcesses)
    $ours = @($running | Where-Object { Test-OurAgent $_ })
    # Запущен раньше, чем собран код, — значит, работает СТАРАЯ версия
    # (например, без новых анимаций). Её надо перезапустить.
    $stale = @($ours | Where-Object { $jar -and $_.CreationDate -lt $jar.LastWriteTime })

    if ($running.Count -gt 0 -and $ours.Count -eq $running.Count -and $stale.Count -eq 0 -and -not $showQr) {
        Ok 'Агент уже запущен и свежий — значок в трее у часов.'
        return $true
    }
    if ($running.Count -gt 0) {
        # Старая копия из автозапуска, устаревшая версия или нужно заново
        # открыть окно с QR. Перезапуск — пара секунд, телефон переподключится сам.
        if ($ours.Count -ne $running.Count) { Info 'Запущена старая копия агента из автозапуска — заменяю свежей.' }
        elseif ($stale.Count -gt 0) { Info 'Код агента обновился — перезапускаю, чтобы заработали изменения.' }
        foreach ($p in $running) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Milliseconds 800
    }

    # javaw, а не java: без чёрного окна консоли. Весь вывод агента всё равно
    # пишется в файл лога — в трее есть пункт «Открыть лог».
    $agentArgs = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp', "`"$AgentLib\*`"", $MainClass)
    $firstRun = -not (Test-Path $TokenFile)
    if ($showQr -and -not $firstRun) { $agentArgs += '--pair-phone' }
    Start-Process -FilePath $javaw -ArgumentList $agentArgs -WorkingDirectory $AgentDir
    Ok 'Агент запущен — значок в трее у часов.'

    if ($firstRun) {
        Write-Host ''
        Warn 'Этот ПК ещё не подключён к релею. Сейчас откроется окно агента:'
        Info '  1. Нажми «Подключить» — ПК зарегистрируется сам, код не нужен.'
        Info '  2. Нажми «Привязать телефон» и отсканируй QR в приложении Faust.'
    } elseif ($showQr) {
        Info 'Окно с QR откроется через пару секунд — отсканируй его в приложении Faust.'
    }
    return $true
}

function Show-PhoneAddress {
    Step 'Адрес релея для телефона'
    # Отбрасываем то, до чего телефон по Wi-Fi не достучится: loopback,
    # самоназначенные адреса и виртуальные адаптеры WSL/Docker/Hyper-V/Radmin.
    $ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object {
        $_.IPAddress -notmatch '^(127\.|169\.254\.)' -and
        $_.InterfaceAlias -notmatch 'vEthernet|WSL|Docker|VirtualBox|VMware|Loopback|Radmin'
    }
    if (-not $ips) { Warn 'Не нашёл сетевой адрес. Проверь, что ПК подключён к Wi-Fi.'; return }
    foreach ($ip in $ips) { Info ("ws://" + $ip.IPAddress + ":8080/ws   (" + $ip.InterfaceAlias + ")") }
    Info 'Вписывать руками не нужно: адрес зашит в QR, который показывает агент.'
}

function Invoke-Demo([bool]$record) {
    Step ('Демонстрация анимаций' + $(if ($record) { ' (режим записи)' } else { '' }))
    Info 'Три сценария: обычная команда, «Экран ПК», ИИ-задача. ~35 секунд, мышь и клики не трогаются.'
    if (-not (Build-Agent)) { return }
    $javaw = Find-Java21
    if (-not $javaw) { Bad 'Не нашёл Java 21.'; return }
    # java, а не javaw: демо пишет подсказки в эту консоль и ждём его окончания.
    $java = Join-Path (Split-Path $javaw) 'java.exe'
    $demoArgs = @('-Dstdout.encoding=UTF-8', '-cp', "$AgentLib\*", 'com.jarvis.agent.FaustOverlayDemo')
    if ($record) { $demoArgs += 'record' }
    & $java @demoArgs | Where-Object { $_ -notmatch '^Фауст-оверлеи' }
}

function Install-Phone {
    Step 'Приложение на телефон'
    if (-not (Test-Path $Adb)) { Bad "Не найден adb: $Adb (ставится вместе с Android Studio)."; return }
    $phones = Get-PhoneSerials
    if ($phones.Count -eq 0) {
        Bad 'Телефон не найден. Подключи по USB и подтверди на телефоне «Разрешить отладку».'
        return
    }
    Ok ("Телефон: " + ($phones -join ', '))
    Info 'Собираю и ставлю приложение (первый раз — пару минут)...'
    Push-Location $AppDir
    try {
        .\gradlew.bat :androidApp:installDebug --console=plain -q
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($code -ne 0) { Bad 'Не удалось собрать или поставить приложение — см. сообщения выше.'; return }
    # Открыть приложение на телефоне: monkey с одним событием = «нажать на иконку».
    cmd /c "`"$Adb`" shell monkey -p $AppId -c android.intent.category.LAUNCHER 1 >nul 2>&1"
    Ok 'Приложение установлено и открыто на телефоне.'
}

function Show-Status {
    Step 'Состояние'
    if (Test-DockerEngine) {
        Ok 'Docker работает'
        $containers = cmd /c 'docker ps -a --filter "name=jarvis-relay" --format "{{.Names}}: {{.Status}}" 2>nul'
        if ($containers) { foreach ($c in $containers) { Info $c } } else { Warn 'Контейнеры релея не созданы — выбери «Запустить».' }
    } else {
        Warn 'Docker не запущен'
    }
    if (Test-Port 'localhost' 8080) { Ok 'Релей отвечает на порту 8080' } else { Warn 'Релей не отвечает на порту 8080' }

    $agents = @(Get-AgentProcesses)
    if ($agents.Count -gt 0) {
        foreach ($a in $agents) {
            $kind = if (Test-OurAgent $a) { 'из проекта' } else { 'СТАРАЯ копия из автозапуска' }
            Ok ('Агент запущен (PID ' + $a.ProcessId + ', ' + $kind + ')')
        }
    } else {
        Warn 'Агент не запущен'
    }
    if (Test-Path $TokenFile) { Ok 'ПК подключён к релею (пропуск сохранён)' }
    else { Warn 'ПК ещё не подключён к релею — при запуске нажми в окне агента «Подключить»' }
    if (Test-AgentBuildFresh) { Ok 'Сборка агента свежая — старт без Gradle' }
    else { Info 'Код агента менялся — при запуске будет пересборка (~20–40 с)' }
    $javaw = Find-Java21
    if ($javaw) { Ok "Java 21: $javaw" } else { Bad 'Java 21 не найдена' }

    $phones = Get-PhoneSerials
    if ($phones.Count -gt 0) { Ok ('Телефон по USB: ' + ($phones -join ', ')) } else { Info 'Телефон по USB не подключён (для работы по Wi-Fi это и не нужно)' }

    Show-PhoneAddress
}

function Stop-All {
    Step 'Остановка'
    $agents = @(Get-AgentProcesses)
    foreach ($p in $agents) {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        Ok ("Агент остановлен (PID " + $p.ProcessId + ')')
    }
    if ($agents.Count -eq 0) { Info 'Агент и так не запущен' }

    if (Test-DockerEngine) {
        Push-Location $RelayDir
        try { docker compose stop } finally { Pop-Location }
        Ok 'Релей и база остановлены. Данные в базе сохранены.'
    } else {
        Info 'Docker не запущен — релей и так остановлен'
    }
}

# Своя модель разбора фраз живёт в Ollama на этом компьютере (см. jarvis-ml).
# Здесь только проверяем, что Ollama запущена и модель в ней есть. Ничего из
# этого не обязательно: без своей модели релей разбирает фразы через Gemini,
# как раньше, — поэтому функция только сообщает и никогда не останавливает запуск.
# Отвечает ли модель на самом деле: короткий запрос (заодно загружает её в
# видеокарту — первая фраза человека потом не ждёт загрузки). До 45 с — на
# первую загрузку модели с диска хватает с запасом.
function Test-ModelAnswers {
    try {
        $body = '{"model":"faust-intent","prompt":"открой ютуб","stream":false,"options":{"num_predict":8}}'
        Invoke-RestMethod 'http://localhost:11434/api/generate' -Method Post -ContentType 'application/json' `
            -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 45 | Out-Null
        return $true
    } catch {
        return $false
    }
}

function Start-LocalModel {
    Step 'Своя модель разбора фраз'
    if (-not (Get-Command ollama -ErrorAction SilentlyContinue)) {
        Info 'Ollama не установлена — фразы разбирает Gemini (как раньше).'
        return
    }
    if (-not (Test-Port 'localhost' 11434)) {
        $tray = Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama app.exe'
        if (Test-Path $tray) { Start-Process $tray } else { Start-Process ollama -ArgumentList 'serve' -WindowStyle Hidden }
        for ($i = 0; $i -lt 15 -and -not (Test-Port 'localhost' 11434); $i++) { Start-Sleep -Seconds 1 }
    }
    if (-not (Test-Port 'localhost' 11434)) {
        Warn 'Ollama не отвечает — фразы разбирает Gemini.'
        return
    }
    $models = cmd /c "ollama list 2>nul"
    if ($models -match 'faust-intent') {
        # Порт открыт — ещё не значит, что модель отвечает: 06.11 Ollama отдавала
        # список моделей, а на запрос генерации молчала минутами, и каждая фраза
        # кончалась «не понял». Пробный короткий запрос; молчит — перезапуск.
        if (-not (Test-ModelAnswers)) {
            Warn 'Ollama зависла (модель не отвечает) — перезапускаю...'
            Get-Process 'ollama app', 'ollama' -ErrorAction SilentlyContinue | Stop-Process -Force
            Start-Sleep -Seconds 2
            $tray = Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama app.exe'
            if (Test-Path $tray) { Start-Process $tray } else { Start-Process ollama -ArgumentList 'serve' -WindowStyle Hidden }
            for ($i = 0; $i -lt 30 -and -not (Test-Port 'localhost' 11434); $i++) { Start-Sleep -Seconds 1 }
            if (-not (Test-ModelAnswers)) {
                Warn 'Модель не ответила и после перезапуска — фразы разбирает Gemini.'
                return
            }
        }
        Ok 'Модель faust-intent готова: простые команды разбираются на этом ПК, без интернета.'
    } else {
        Info 'Ollama работает, но модели faust-intent в ней нет (обучение: jarvis-ml\README.md) — разбирает Gemini.'
    }
}

function Start-All([bool]$showQr) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    if (-not (Ensure-Docker)) { return }
    if (-not (Start-Relay)) { return }
    Start-LocalModel
    if (-not (Start-Agent $showQr)) { return }
    Write-Host ''
    Ok ('Готово за ' + [int]$sw.Elapsed.TotalSeconds + ' с. Открой Faust на телефоне и отправь команду.')
    Info "Лог агента: $AgentLog"
}

function Show-Menu {
    while ($true) {
        Write-Host ''
        Write-Host '  ============================================' -ForegroundColor DarkYellow
        Write-Host '   ФАУСТ' -ForegroundColor Yellow
        Write-Host '  ============================================' -ForegroundColor DarkYellow
        Write-Host '   1  Запустить                  (каждый день)'
        Write-Host '   2  Показать QR для телефона   (новый телефон или переустановил приложение)'
        Write-Host '   3  Показать анимации          (демо, без телефона и релея)'
        Write-Host '   4  Демо для записи экрана     (OBS / Zoom / скриншоты)'
        Write-Host '   5  Поставить приложение на телефон (USB)'
        Write-Host '   6  Состояние'
        Write-Host '   7  Остановить всё'
        Write-Host '   0  Выход'
        $choice = Read-Host '  Выбор'
        # $null — ввод закончился (скрипт запустили не из консоли): выходим,
        # иначе меню крутилось бы бесконечно, получая пустой ответ.
        if ($null -eq $choice) { return }
        switch ($choice) {
            '1' { Start-All $false }
            '2' { Start-All $true }
            '3' { Invoke-Demo $false }
            '4' { Invoke-Demo $true }
            '5' { Install-Phone }
            '6' { Show-Status }
            '7' { Stop-All }
            '0' { return }
            default { Warn 'Нет такого пункта' }
        }
    }
}

switch ($Command.ToLower()) {
    ''       { Show-Menu }
    'start'  { Start-All $false }
    'qr'     { Start-All $true }
    'demo'   { Invoke-Demo $false }
    'record' { Invoke-Demo $true }
    'phone'  { Install-Phone }
    'status' { Show-Status }
    'stop'   { Stop-All }
    default  {
        Write-Host 'Команды: start | qr | demo | record | phone | status | stop  (без команды — меню)'
    }
}
