@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

if defined JAVA_HOME (
    call :TryJavaHome "%JAVA_HOME%"
    if !JAVA_OK! equ 1 goto :java_ready
)

call :TryJavaHome "C:\Program Files\Zulu\zulu-17"
if !JAVA_OK! equ 1 goto :java_ready

for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do (
    call :TryJavaHome "%%D"
    if !JAVA_OK! equ 1 goto :java_ready
)

for /d %%D in ("C:\Program Files\Microsoft\jdk-17*") do (
    call :TryJavaHome "%%D"
    if !JAVA_OK! equ 1 goto :java_ready
)

for /d %%D in ("C:\Program Files\BellSoft\LibericaJDK-17*") do (
    call :TryJavaHome "%%D"
    if !JAVA_OK! equ 1 goto :java_ready
)

for /d %%D in ("C:\Program Files\Java\jdk-17*") do (
    call :TryJavaHome "%%D"
    if !JAVA_OK! equ 1 goto :java_ready
)

echo.
echo ОШИБКА: не найден рабочий JDK 17.
echo.
if exist "C:\Program Files\Zulu\zulu-17\bin\java.exe" (
    echo Установлен Zulu 17, но он поврежден - java.exe не запускается
    echo ^(отсутствует java.dll^). Переустановите JDK 17:
    echo   https://www.azul.com/downloads/?version=java-17-lts^&os=windows^&architecture=x86_64-bit^&package=jdk
    echo или Eclipse Temurin 17:
    echo   https://adoptium.net/temurin/releases/?version=17
) else (
    echo Установите JDK 17 и задайте переменную JAVA_HOME, либо положите JDK
    echo в одну из стандартных папок ^(Zulu, Eclipse Adoptium, Microsoft, BellSoft^).
)
echo.
pause
exit /b 1

:TryJavaHome
set "JAVA_OK=0"
set "_JH=%~1"
if not exist "%_JH%\bin\java.exe" exit /b 0
"%_JH%\bin\java.exe" -version >nul 2>&1
if errorlevel 1 exit /b 0
set "JAVA_HOME=%_JH%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "JAVA_OK=1"
echo Using JAVA_HOME=%JAVA_HOME%
"%JAVA_HOME%\bin\java.exe" -version
exit /b 0

:java_ready
call "%~dp0sync-version.bat"
if errorlevel 1 exit /b 1

echo.
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
endlocal
exit /b 0
