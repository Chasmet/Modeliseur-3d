@echo off
setlocal
set APP_HOME=%~dp0
set GRADLE_VERSION=8.11.1
set CACHE_DIR=%APP_HOME%.gradle-dist
set DIST_DIR=%CACHE_DIR%\gradle-%GRADLE_VERSION%
set ZIP_FILE=%CACHE_DIR%\gradle-%GRADLE_VERSION%-bin.zip
set URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip

if exist "%DIST_DIR%\bin\gradle.bat" goto run
if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"

if not exist "%ZIP_FILE%" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing '%URL%' -OutFile '%ZIP_FILE%'"
  if errorlevel 1 exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%ZIP_FILE%' -DestinationPath '%CACHE_DIR%' -Force"
if errorlevel 1 exit /b 1

:run
call "%DIST_DIR%\bin\gradle.bat" %*
endlocal
