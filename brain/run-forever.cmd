@echo off
REM run-forever.cmd - run this instead of `npm start` to get auto-updates +
REM crash recovery.
REM
REM Loop contract (matches lib/self-update.mjs's restart trigger):
REM   - exit code 75  -> the brain pulled new code and asked to be restarted
REM                      (see BRAIN_UPDATE_CHECK_MIN). Loop again immediately.
REM   - exit code 0   -> deliberate stop. Break the loop, exit 0.
REM   - any other
REM     nonzero code  -> crash. Wait 10 seconds, then loop again.
REM
REM Plain `npm start` still works fine - it just won't restart itself on
REM either an update or a crash. Use this wrapper for anything meant to run
REM unattended.

setlocal
cd /d "%~dp0"

:loop
call npm start
set EXITCODE=%ERRORLEVEL%

if %EXITCODE%==75 (
  echo [run-forever] brain exited 75 ^(update pulled^), restarting immediately...
  goto loop
)

if %EXITCODE%==0 (
  echo [run-forever] brain exited 0 ^(deliberate stop^), not restarting.
  exit /b 0
)

echo [run-forever] brain exited %EXITCODE% ^(crash^), restarting in 10 seconds...
timeout /t 10 /nobreak >nul
goto loop
