#Requires -Version 5.1
<#
    Ставит агента на автозапуск при входе в Windows.

    Что делает:
      1. берёт готовую сборку build\install\jarvis-agent (её создаёт gradlew installDist);
      2. копирует её в %LOCALAPPDATA%\Jarvis\agent — чтобы автозапуск не зависел
         от папки с исходниками и пережил пересборку проекта;
      3. кладёт ярлык в папку автозагрузки, который стартует агента через javaw.exe
         (именно javaw, а не java — иначе при каждом входе вылезало бы чёрное окно консоли).

    Настройки и токен агента ($HOME\.jarvis-agent) скрипт не трогает.
    Первичная привязка самого ПК по-прежнему делается через start-jarvis.bat.

    Запуск:  powershell -NoProfile -ExecutionPolicy Bypass -File install-autostart.ps1
#>

$ErrorActionPreference = 'Stop'

$AppName     = 'Jarvis Agent'
$MainClass   = 'com.jarvis.agent.AgentMain'
$MinJavaMajor = 21

$ProjectDir = Split-Path -Parent $PSScriptRoot
$DistDir    = Join-Path $ProjectDir 'build\install\jarvis-agent'
$InstallDir = Join-Path $env:LOCALAPPDATA 'Jarvis\agent'
$LinkPath   = Join-Path ([Environment]::GetFolderPath('Startup')) "$AppName.lnk"

# --- 1. Сборка на месте? -----------------------------------------------------

if (-not (Test-Path (Join-Path $DistDir 'lib'))) {
    throw "Не нашёл сборку: $DistDir`nСначала выполни в папке проекта:  .\gradlew installDist"
}

# --- 2. Ищем Java нужной версии ---------------------------------------------
# Агент собран под Java $MinJavaMajor. Если подсунуть более старую, javaw молча
# упадёт с UnsupportedClassVersionError — окна-то нет, ошибку никто не увидит.
# Поэтому версию проверяем заранее и по файлу release внутри JDK.

function Get-JavaMajor([string]$javaHome) {
    $release = Join-Path $javaHome 'release'
    if (-not (Test-Path $release)) { return 0 }
    $line = Get-Content $release | Where-Object { $_ -match '^JAVA_VERSION="([^"]+)"' }
    if (-not $line) { return 0 }
    return [int](($matches[1] -split '[._]')[0])
}

$candidates = New-Object System.Collections.Generic.List[string]
if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }

$javaOnPath = Get-Command java -ErrorAction SilentlyContinue
if ($javaOnPath) { $candidates.Add((Split-Path -Parent (Split-Path -Parent $javaOnPath.Source))) }

# JDK, который скачал себе сам Gradle под toolchain проекта — зачастую это
# единственная установленная на машине Java 21.
Get-ChildItem "$env:USERPROFILE\.gradle\jdks" -Directory -ErrorAction SilentlyContinue |
    ForEach-Object { $candidates.Add($_.FullName) }

Get-ChildItem 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue |
    ForEach-Object { $candidates.Add($_.FullName) }

$javawPath = $null
foreach ($javaHome in $candidates) {
    $candidateJavaw = Join-Path $javaHome 'bin\javaw.exe'
    if ((Test-Path $candidateJavaw) -and ((Get-JavaMajor $javaHome) -ge $MinJavaMajor)) {
        $javawPath = $candidateJavaw
        break
    }
}

if (-not $javawPath) {
    throw "Не нашёл Java $MinJavaMajor или новее. Установи JDK $MinJavaMajor (например, Eclipse Temurin) и запусти скрипт снова."
}
Write-Host "Java для автозапуска: $javawPath"

# --- 3. Останавливаем уже запущенного агента (иначе файлы заняты) ------------

Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine -match [regex]::Escape($MainClass) } |
    ForEach-Object {
        Write-Host "Останавливаю запущенного агента (PID $($_.ProcessId))"
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        # Stop-Process возвращается сразу, а Windows освобождает jar-файлы агента
        # с задержкой — без ожидания следующий Remove-Item падает с
        # "file is being used by another process".
        try { Wait-Process -Id $_.ProcessId -Timeout 15 -ErrorAction Stop } catch {}
        Start-Sleep -Milliseconds 500
    }

# --- 4. Копируем сборку ------------------------------------------------------

if (Test-Path $InstallDir) { Remove-Item $InstallDir -Recurse -Force }
New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
Copy-Item (Join-Path $DistDir '*') $InstallDir -Recurse -Force
Write-Host "Скопировал агента в: $InstallDir"

# --- 5. Ярлык в автозагрузке -------------------------------------------------

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($LinkPath)
$shortcut.TargetPath       = $javawPath
$shortcut.Arguments        = "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp `"$InstallDir\lib\*`" $MainClass"
$shortcut.WorkingDirectory = $InstallDir
$shortcut.Description      = 'Jarvis: агент управления ПК'
$shortcut.Save()

Write-Host ""
Write-Host "Готово. Ярлык автозапуска: $LinkPath"
Write-Host "Проверка: запусти ярлык двойным кликом — в трее появится значок Jarvis,"
Write-Host "а лог будет писаться в $env:USERPROFILE\.jarvis-agent\agent.log"
Write-Host "Убрать автозапуск: scripts\uninstall-autostart.bat"
