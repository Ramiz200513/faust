# ============================================================================
#  build-installer.ps1 — собирает готовое приложение для ПК: папку с
#  "Jarvis Agent.exe" и встроенной JRE (или .msi, если установлен WiX).
#  Человеку, который его запускает, Java на компьютере не нужна.
#
#  Что нужно на машине СБОРКИ (не на машине, куда ставят):
#    * JDK 21 — jpackage берётся из него же. Скрипт ищет его сам:
#      JAVA_HOME -> JDK, скачанный Gradle toolchain -> Program Files.
#    * WiX Toolset (v4/v5, https://wixtoolset.org/) — ТОЛЬКО если нужен .msi.
#      Без него собирается app-image: обычная папка с .exe, которую можно
#      просто заархивировать и выложить на скачивание.
#
#  Запуск:  .\scripts\build-installer.ps1
#           .\scripts\build-installer.ps1 -Type msi     (потребует WiX)
#           .\scripts\build-installer.ps1 -Type app-image
# ============================================================================

param(
    # auto -> msi, если WiX найден, иначе app-image
    [ValidateSet('auto', 'exe', 'msi', 'app-image')]
    [string]$Type = 'auto'
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot -Parent   # C:\diplom\jarvis-agent

function Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }

# --- Ищем jpackage именно из JDK 21 ------------------------------------------
# Версия важна: агент компилируется под 21, и jpackage от JDK 17 вложил бы в
# пакет JRE 17 — приложение бы установилось и тут же упало на UnsupportedClassVersionError.
function Find-JPackage {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\jpackage.exe') }
    $gradleJdks = Join-Path $env:USERPROFILE '.gradle\jdks'
    if (Test-Path $gradleJdks) {
        $candidates += (Get-ChildItem $gradleJdks -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName 'bin\jpackage.exe' })
    }
    $candidates += (Get-ChildItem 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java' `
        -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'bin\jpackage.exe' })
    $onPath = Get-Command jpackage -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }

    foreach ($exe in $candidates) {
        if (-not (Test-Path $exe)) { continue }
        $version = (& $exe --version) 2>$null
        if ($version -and $version.ToString().StartsWith('21')) {
            return $exe
        }
    }
    throw "Не нашёл jpackage из JDK 21. Поставь JDK 21 и задай JAVA_HOME, либо собери проект ./gradlew build — Gradle скачает нужный JDK сам."
}

Push-Location $Root
try {
    Step 'Собираю jar и зависимости (installDist)'
    & (Join-Path $Root 'gradlew.bat') installDist --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'installDist упал' }

    $Version = (Select-String -Path 'build.gradle.kts' -Pattern 'version = "(.+)"').Matches[0].Groups[1].Value
    $MainJar = "jarvis-agent-$Version.jar"
    $LibDir  = Join-Path $Root 'build\install\jarvis-agent\lib'
    $OutDir  = Join-Path $Root 'build\installer'

    if (-not (Test-Path (Join-Path $LibDir $MainJar))) {
        throw "Не нашёл $MainJar в $LibDir — проверь версию в build.gradle.kts"
    }

    $JPackage = Find-JPackage
    Write-Host "jpackage: $JPackage"

    # WiX нужен для .exe и .msi. Сразу после установки он уже есть на диске, но
    # ещё не в PATH текущей консоли (PATH читается при её запуске), а jpackage
    # ищет candle.exe/light.exe именно в PATH — поэтому добавляем его сами.
    $hasWix = [bool](Get-Command candle, light -ErrorAction SilentlyContinue)
    if (-not $hasWix) {
        $wixDirs = @(
            "${env:ProgramFiles(x86)}\WiX Toolset v3.14\bin",
            "$env:ProgramFiles\WiX Toolset v3.14\bin",
            "${env:ProgramFiles(x86)}\WiX Toolset v3.11\bin",
            "$env:ProgramFiles\WiX Toolset v3.11\bin"
        )
        foreach ($dir in $wixDirs) {
            if (Test-Path (Join-Path $dir 'candle.exe')) {
                $env:PATH = "$dir;$env:PATH"
                Write-Host "WiX: $dir"
                $hasWix = $true
                break
            }
        }
    }
    $resolvedType = $Type
    if ($Type -eq 'auto') {
        if ($hasWix) { $resolvedType = 'exe' } else { $resolvedType = 'app-image' }
    }
    if (($resolvedType -eq 'msi' -or $resolvedType -eq 'exe') -and -not $hasWix) {
        throw "Для -Type $resolvedType нужен WiX Toolset 3.14 (https://wixtoolset.org/). Без него используй -Type app-image."
    }
    Write-Host "тип пакета: $resolvedType"

    if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }

    $jpArgs = @(
        '--type', $resolvedType,
        '--name', 'Jarvis Agent',
        '--app-version', $Version,
        '--input', $LibDir,
        '--main-jar', $MainJar,
        '--main-class', 'com.jarvis.agent.AgentMain',
        '--dest', $OutDir,
        '--vendor', 'Jarvis diploma project',
        # Иначе на русской Windows лог агента пишется не в той кодировке —
        # то же, что applicationDefaultJvmArgs в build.gradle.kts, который
        # jpackage не читает.
        '--java-options', '-Dstdout.encoding=UTF-8',
        '--java-options', '-Dstderr.encoding=UTF-8'
    )
    # Ярлык на рабочем столе и пункт в меню "Пуск" бывают только у установщика.
    if ($resolvedType -ne 'app-image') {
        $jpArgs += @('--win-shortcut', '--win-menu')
    }

    Step 'Собираю приложение (jpackage)'
    & $JPackage @jpArgs
    if ($LASTEXITCODE -ne 0) { throw 'jpackage упал' }

    if ($resolvedType -eq 'msi' -or $resolvedType -eq 'exe') {
        $installer = Get-ChildItem $OutDir -Filter "*.$resolvedType" | Select-Object -First 1
        Write-Host "`nГотово: $($installer.FullName)" -ForegroundColor Green
    } else {
        $AppDir = Join-Path $OutDir 'Jarvis Agent'
        $Zip    = Join-Path $OutDir "JarvisAgent-$Version-windows.zip"
        Step 'Упаковываю в zip для раздачи'
        Compress-Archive -Path $AppDir -DestinationPath $Zip -Force
        Write-Host "`nГотово:" -ForegroundColor Green
        Write-Host "  запуск здесь : $AppDir\Jarvis Agent.exe"
        Write-Host "  для раздачи  : $Zip"
    }
}
finally {
    Pop-Location
}
