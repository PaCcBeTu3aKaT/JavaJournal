@echo off
setlocal
cd /d "%~dp0android-studio"
call gradlew.bat :android:assembleDebug
set "result=%errorlevel%"
if not "%result%"=="0" (
    echo Android APK build failed. Check the Gradle output above.
    pause
)
exit /b %result%
