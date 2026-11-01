@echo off
rem Double-click entry point for Windows.
rem Runs tools\start-server.sh in the foreground, so the console stays open while
rem the gateway + LAN bridge run. Close this window (or press Ctrl+C) to stop.
setlocal
chcp 65001 >nul
set "HERE=%~dp0"

set "BASH="
if exist "%ProgramFiles%\Git\bin\bash.exe" set "BASH=%ProgramFiles%\Git\bin\bash.exe"
if not defined BASH if exist "%ProgramFiles(x86)%\Git\bin\bash.exe" set "BASH=%ProgramFiles(x86)%\Git\bin\bash.exe"
if not defined BASH if exist "%LocalAppData%\Programs\Git\bin\bash.exe" set "BASH=%LocalAppData%\Programs\Git\bin\bash.exe"
if not defined BASH for /f "delims=" %%I in ('where bash 2^>nul') do if not defined BASH set "BASH=%%I"

if not defined BASH (
  echo Git Bash not found.
  echo Install Git for Windows, then double-click this file again.
  echo Manual: bash tools/start-server.sh --foreground
  pause
  exit /b 1
)

"%BASH%" "%HERE%tools\start-server.sh" --foreground %*
if errorlevel 1 (
  echo.
  echo Startup failed - see the messages above.
  pause
)
endlocal
