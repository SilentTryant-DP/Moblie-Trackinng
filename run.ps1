# Hybrid Mobile Tracker PowerShell Launcher
$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $ScriptDir

$pyExe = "$env:LOCALAPPDATA\Python\bin\python.exe"
if (-not (Test-Path $pyExe)) {
    $cmd = Get-Command python -ErrorAction SilentlyContinue
    if ($cmd) {
        $pyExe = $cmd.Source
    } else {
        Write-Error "Python executable not found."
    }
}

Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host " HYBRID CELLULAR-BLUETOOTH TRACKING ECOSYSTEM" -ForegroundColor Green
Write-Host " Running Application Server on http://localhost:8080" -ForegroundColor Yellow
Write-Host "======================================================================" -ForegroundColor Cyan

Start-Process "http://localhost:8080"
& $pyExe app_server.py
