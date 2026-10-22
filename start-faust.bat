@echo off
rem Faust launcher. Double-click to open the menu.
rem From a console:  start-faust.bat [start ^| demo ^| record ^| phone ^| status ^| stop]
rem All the logic lives in faust.ps1 next to this file.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0faust.ps1" %*
if not "%~1"=="" pause
