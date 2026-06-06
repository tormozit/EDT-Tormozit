@echo off
cd /d "%~dp0"
set JAVA_HOME=C:\Program Files\Zulu\zulu-17
set PATH=%JAVA_HOME%\bin;%JAVA_HOME%\bin\server;%PATH%

call "%~dp0sync-version.bat"
if errorlevel 1 exit /b 1

echo Building EDT Comfort plugin...
call mvn clean package -f "%~dp0..\pom.xml"

if errorlevel 1 (
    echo.
    echo BUILD FAILED
    pause
    exit /b 1
)

echo.
echo BUILD SUCCESS
echo ZIP: site\target\EDT.Comfort-*.zip
for %%f in ("%~dp0target\*.zip") do echo       %%f
