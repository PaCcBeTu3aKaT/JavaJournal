@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
echo ==========================================
echo      JavaJournal - Windows package
echo ==========================================
echo.
where java >nul 2>&1
if errorlevel 1 (
    echo ERROR: Java not found. Install JDK 21.
    goto :fail
)
call "%~dp0gradlew.bat" clean :core:test :desktop:packageWindows
if errorlevel 1 goto :fail
echo.
echo SUCCESS: desktop\build\distributions\
echo.
explorer "%~dp0desktop\build\distributions"
pause
exit /b 0
:fail
echo.
echo Build failed. Check the output above and Gradle JVM / JAVA_HOME.
pause
exit /b 1
