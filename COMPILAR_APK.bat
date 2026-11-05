@echo off
cd /d "%~dp0"
echo Compilando Android Cleaner...
call gradlew.bat assembleDebug
if errorlevel 1 (
  echo.
  echo Falha na compilacao. Verifique Android SDK e JDK 17.
  pause
  exit /b 1
)
echo.
echo APK criada em:
echo %~dp0app\build\outputs\apk\debug\app-debug.apk
explorer "%~dp0app\build\outputs\apk\debug"
pause