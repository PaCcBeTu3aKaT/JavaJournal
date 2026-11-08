@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
call "%~dp0gradlew.bat" :desktop:run
if errorlevel 1 (
    echo.
    echo JavaJournal failed to start. Check JDK 21 and the error log above.
)
echo.
pause
