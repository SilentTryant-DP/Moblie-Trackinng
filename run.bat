@echo off
setlocal
cd /d "%~dp0"
title Hybrid Cellular-Bluetooth Mobile Tracker

echo ======================================================================
echo  HYBRID CELLULAR-BLUETOOTH MOBILE TRACKER LAUNCHER
echo ======================================================================
echo.

set "PY_EXE="
if exist "%LOCALAPPDATA%\Python\bin\python.exe" (
    set "PY_EXE=%LOCALAPPDATA%\Python\bin\python.exe"
) else (
    where python >nul 2>nul
    if %ERRORLEVEL% equ 0 (
        set "PY_EXE=python"
    )
)

if "%PY_EXE%"=="" (
    echo [ERROR] Python not found in %LOCALAPPDATA%\Python\bin\python.exe or PATH.
    pause
    exit /b 1
)

echo [*] Starting Mobile Application Server at http://localhost:8080 ...
start "" http://localhost:8080
"%PY_EXE%" app_server.py

pause
