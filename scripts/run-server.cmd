@echo off
REM MCAlive2 Paper server restart-loop launcher (Windows).
REM
REM Copy this file into your Paper SERVER folder (next to your server jar) - not
REM into your MCAlive2 repo checkout - and start the server by running THIS file
REM instead of a bare "java -jar ...". The jar is auto-detected (server.jar,
REM paper.jar, or paper-*.jar); set SERVER_JAR below only if yours is named
REM something else entirely.
REM
REM Why a loop: Paper only swaps in a staged update from plugins/update/ at
REM startup, so applying an update means the server has to stop and come back.
REM Bukkit's shutdown() only stops the JVM - something outside it must relaunch
REM it. That is this loop.
REM
REM The loop also stamps a sentinel file (restart-loop.active) immediately before
REM each launch. MCAlive2's auto-restart refuses to shut the server down unless
REM that sentinel exists AND was written for the current launch - so if you ever
REM start the server WITHOUT this loop, nothing will shut it down and strand you.
REM The sentinel is cleared whenever the JVM exits.

setlocal
set SENTINEL=restart-loop.active

REM ---- pick the server jar (override by uncommenting the next line) ----
REM set SERVER_JAR=my-custom-name.jar
if not defined SERVER_JAR if exist "server.jar" set SERVER_JAR=server.jar
if not defined SERVER_JAR if exist "paper.jar" set SERVER_JAR=paper.jar
if not defined SERVER_JAR for %%J in (paper-*.jar) do if not defined SERVER_JAR set SERVER_JAR=%%J
if not defined SERVER_JAR (
  echo.
  echo Could not find a server jar in this folder:
  echo   %CD%
  echo Put run-server.cmd next to your server jar, or edit SERVER_JAR above.
  echo.
  pause
  exit /b 1
)

echo Starting %SERVER_JAR% under the MCAlive2 restart loop.
echo Close this window to stop the server for good.
echo.

:loop
REM Stamp the sentinel fresh for this launch (proof a supervisor is watching).
echo mcalive2 restart loop active > "%SENTINEL%"
java -Xms2G -Xmx4G -jar "%SERVER_JAR%" --nogui
REM Server exited (crash, /stop, or an update restart). Clear the sentinel while
REM nothing is running, so a bare manual launch is never mistaken for supervised.
del /q "%SENTINEL%" 2>nul
echo.
echo Server stopped - restarting in 5s. Close this window to stop for good.
timeout /t 5
goto loop
