@echo off
rem Скрипт очистки сайта перед сборкой новой версии.
rem Запускать из папки tormozit.edt.site перед нажатием "Build All".

cd /d "%~dp0"
echo Cleaning old build artifacts...
if exist content.jar del /f content.jar
if exist artifacts.jar del /f artifacts.jar
if exist features rmdir /s /q features
if exist plugins rmdir /s /q plugins
echo Done. Now run "Build All" in Eclipse Update Site editor.
