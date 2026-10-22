#Requires -Version 5.1
<#
    Убирает агента из автозапуска: удаляет ярлык из автозагрузки, останавливает
    запущенного агента и стирает копию приложения в %LOCALAPPDATA%\Jarvis\agent.

    Настройки, лог и токен ($HOME\.jarvis-agent) НЕ трогает — после повторной
    установки ПК останется привязанным, заново парить не придётся.

    Запуск:  powershell -NoProfile -ExecutionPolicy Bypass -File uninstall-autostart.ps1
#>

$ErrorActionPreference = 'Stop'

$AppName    = 'Jarvis Agent'
$MainClass  = 'com.jarvis.agent.AgentMain'
$InstallDir = Join-Path $env:LOCALAPPDATA 'Jarvis\agent'
$LinkPath   = Join-Path ([Environment]::GetFolderPath('Startup')) "$AppName.lnk"

if (Test-Path $LinkPath) {
    Remove-Item $LinkPath -Force
    Write-Host "Убрал ярлык автозапуска: $LinkPath"
} else {
    Write-Host "Ярлыка автозапуска не было: $LinkPath"
}

Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine -match [regex]::Escape($MainClass) } |
    ForEach-Object {
        Write-Host "Останавливаю запущенного агента (PID $($_.ProcessId))"
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }

if (Test-Path $InstallDir) {
    Remove-Item $InstallDir -Recurse -Force
    Write-Host "Удалил копию приложения: $InstallDir"
}

Write-Host ""
Write-Host "Готово. Токен и настройки остались в $env:USERPROFILE\.jarvis-agent"
