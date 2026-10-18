@echo off
rem ============================================================
rem  JARVIS (PC) - double-click to set up and start.
rem  First run: creates Python env and installs packages
rem             (speech model ~500MB is downloaded on first start).
rem  Next runs: starts immediately.
rem  Keyboard test mode:  JARVIS.bat --text
rem ============================================================
setlocal
cd /d "%~dp0"
title JARVIS
chcp 65001 >nul
set PYTHONUTF8=1

rem ---------- 1. Python environment ----------
if exist ".venv\Scripts\python.exe" goto deps
echo [1/4] Creating Python environment...
set "PY="
where py >nul 2>nul
if errorlevel 1 goto trypython
py -3.12 -c "pass" >nul 2>nul && set "PY=py -3.12" && goto mkvenv
py -3.11 -c "pass" >nul 2>nul && set "PY=py -3.11" && goto mkvenv
py -3 -c "pass" >nul 2>nul && set "PY=py -3" && goto mkvenv
:trypython
where python >nul 2>nul
if errorlevel 1 goto nopython
set "PY=python"
:mkvenv
%PY% -m venv .venv
if errorlevel 1 goto nopython

:deps
rem ---------- 2. Packages (reinstall only when requirements.txt changed) ----------
fc /b requirements.txt .venv\installed-requirements.txt >nul 2>nul
if not errorlevel 1 goto config
echo [2/4] Installing packages...
".venv\Scripts\python.exe" -m pip install --upgrade pip >nul
".venv\Scripts\python.exe" -m pip install -r requirements.txt
if errorlevel 1 (
  echo.
  echo Package install failed. Check your internet connection and run again.
  pause
  exit /b 1
)
copy /y requirements.txt .venv\installed-requirements.txt >nul

:config
if not exist config.yaml copy config.example.yaml config.yaml >nul

rem ---------- 3. VOICEVOX (voice) ----------
call :voicevox_up
if not errorlevel 1 goto run
set "VV=%LOCALAPPDATA%\Programs\VOICEVOX\VOICEVOX.exe"
if exist "%VV%" (
  echo [3/4] Starting VOICEVOX...
  start "" /min "%VV%"
  timeout /t 8 /nobreak >nul
) else (
  echo [3/4] VOICEVOX not found. Using the Windows voice instead.
  echo       For the JARVIS voice, install VOICEVOX: https://voicevox.hiroshiba.jp/
)

:run
rem ---------- 4. Start ----------
echo [4/4] Starting JARVIS...  ^(close this window to stop^)
echo.
".venv\Scripts\python.exe" -m jarvis.main %*
echo.
pause
exit /b 0

:voicevox_up
powershell -NoProfile -Command "try { Invoke-WebRequest -UseBasicParsing http://127.0.0.1:50021/version -TimeoutSec 2 | Out-Null; exit 0 } catch { exit 1 }"
exit /b %errorlevel%

:nopython
echo.
echo Python was not found. Opening the download page...
echo Install Python 3.12 and CHECK "Add python.exe to PATH", then run JARVIS.bat again.
start "" https://www.python.org/downloads/windows/
pause
exit /b 1
