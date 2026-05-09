@echo off
cd /d "%~dp0"

echo Building tormozit.edt plugin...
call mvn clean package -f pom.xml

if errorlevel 1 (
    echo.
    echo BUILD FAILED
    pause
    exit /b 1
)

echo.
echo BUILD SUCCESS
echo ZIP: tormozit.edt.site\target\tormozit.edt.site-*.zip
for %%f in (tormozit.edt.site\target\*.zip) do echo       %%f
