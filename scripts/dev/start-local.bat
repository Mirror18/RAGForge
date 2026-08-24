@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "RAGFORGE_SCRIPT_ROOT=%SCRIPT_DIR%"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& ([ScriptBlock]::Create([IO.File]::ReadAllText('%SCRIPT_DIR%start-local.ps1', [Text.Encoding]::UTF8))) %*"
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" (
    echo.
    echo Local startup failed. Review the error above, then press any key to close this window.
    pause >nul
)
exit /b %EXIT_CODE%
