@echo off
rem ============================================================
rem  JARVIS (PC) - double-click to set up and start.
rem  First run: prepares Python 3.12 (installs it automatically if needed),
rem             creates the environment and installs packages.
rem  Next runs: starts immediately.
rem  Keyboard test mode:  JARVIS.bat --text
rem ============================================================
setlocal
cd /d "%~dp0"
title JARVIS
chcp 65001 >nul
set PYTHONUTF8=1

rem ---------- 1. Python environment (needs Python 3.10 - 3.13) ----------
if not exist ".venv\Scripts\python.exe" goto findpy
".venv\Scripts\python.exe" -c "import sys; sys.exit(0 if (3,10) <= sys.version_info[:2] <= (3,13) else 1)" >nul 2>nul
if not errorlevel 1 goto deps
echo [1/4] The environment uses an unsupported Python version. Recreating it with Python 3.12...
rmdir /s /q .venv

:findpy
set "PY="
call :trypy 3.12
if defined PY goto mkvenv
call :trypy 3.11
if defined PY goto mkvenv
call :trypy 3.13
if defined PY goto mkvenv
call :trypy 3.10
if defined PY goto mkvenv
if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set "PY="%LOCALAPPDATA%\Programs\Python\Python312\python.exe"" & goto mkvenv

echo [1/4] Python 3.12 was not found. Installing it automatically (one time only)...
where py >nul 2>nul
if not errorlevel 1 (
  py install 3.12 <nul
  call :trypy 3.12
  if defined PY goto mkvenv
)
where winget >nul 2>nul
if errorlevel 1 goto nopython
winget install -e --id Python.Python.3.12 --scope user --silent --accept-package-agreements --accept-source-agreements
if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set "PY="%LOCALAPPDATA%\Programs\Python\Python312\python.exe"" & goto mkvenv
call :trypy 3.12
if defined PY goto mkvenv
goto nopython

:mkvenv
echo [1/4] Creating Python environment with %PY% ...
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

rem ---------- helpers ----------
:trypy
where py >nul 2>nul
if errorlevel 1 exit /b 0
py -%1 -c "import sys; sys.exit(0 if sys.version_info[:2] == tuple(map(int, '%1'.split('.'))) else 1)" <nul >nul 2>nul
if not errorlevel 1 set "PY=py -%1"
exit /b 0

:voicevox_up
powershell -NoProfile -Command "try { Invoke-WebRequest -UseBasicParsing http://127.0.0.1:50021/version -TimeoutSec 2 | Out-Null; exit 0 } catch { exit 1 }"
exit /b %errorlevel%

:nopython
echo.
echo Could not prepare Python 3.12 automatically.
echo Please install Python 3.12 from the page that opens (check "Add python.exe to PATH"),
echo then double-click JARVIS.bat again. Your current Python can stay installed.
start "" https://www.python.org/downloads/release/python-3129/
pause
exit /b 1
