@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0database-admin.ps1" %*
exit /b %errorlevel%
