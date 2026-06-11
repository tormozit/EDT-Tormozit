@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bump-release.ps1"
if errorlevel 1 exit /b 1
