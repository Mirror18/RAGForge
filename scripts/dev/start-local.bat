@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "RAGFORGE_SCRIPT_ROOT=%SCRIPT_DIR%"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& ([ScriptBlock]::Create([IO.File]::ReadAllText('%SCRIPT_DIR%start-local.ps1', [Text.Encoding]::UTF8))) %*"
exit /b %ERRORLEVEL%
