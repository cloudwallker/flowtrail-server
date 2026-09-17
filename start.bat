@echo off
setlocal
chcp 65001 >nul
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0bin\launch.ps1" %*
set "launchExit=%errorlevel%"
if not "%launchExit%"=="0" if /I not "%~1"=="-Check" pause
exit /b %launchExit%