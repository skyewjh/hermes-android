@echo off
rem Double-click entry point for Windows: stops the gateway and LAN bridge that
rem start-server.bat launched.
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
  pause
  exit /b 1
)

"%BASH%" "%HERE%tools\start-server.sh" --stop
echo.
echo Done.
pause
endlocal
