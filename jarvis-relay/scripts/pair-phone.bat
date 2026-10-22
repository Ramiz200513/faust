@echo off
rem Double-click: generate phone pairing codes
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0pair-phone.ps1"
pause
