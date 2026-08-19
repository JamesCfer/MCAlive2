@echo off
REM MCAlive2 Paper server restart-loop launcher (Windows).
REM
REM Copy this file into your Paper SERVER folder (next to paper.jar / your server
REM jar) - not into your MCAlive2 repo checkout - and adjust PAPER_JAR below if your
REM jar has a different filename (e.g. paper-1.21.1-XXX.jar). Then start the server
REM by running THIS file instead of a bare "java -jar paper.jar".
REM
REM Why: Paper only swaps in a staged update from plugins/update/ at startup, so
REM applying an update means the server has to stop and come back. Bukkit's
REM shutdown() only stops the JVM - something outside it must relaunch. That is
REM this loop.
REM
REM The loop also writes a sentinel file (restart-loop.active) immediately before
REM each launch. MCAlive2's auto-restart refuses to shut the server down unless
REM that sentinel exists AND was written for the current launch - so if you ever
REM start the server WITHOUT this loop, nothing will ever shut it down and leave
REM it stranded. The sentinel is removed when the loop exits cleanly.

setlocal
set PAPER_JAR=paper.jar
set SENTINEL=restart-loop.active

:loop
REM Stamp the sentinel fresh for this launch (proof a supervisor is watching).
echo mcalive2 restart loop active > "%SENTINEL%"
java -Xms2G -Xmx4G -jar %PAPER_JAR% --nogui
REM Server exited (crash, /stop, or an update restart). Clear the sentinel while
REM nothing is running, so a bare manual launch is never mistaken for supervised.
del /q "%SENTINEL%" 2>nul
echo restarting in 5s...  (close this window to stop the server for good)
timeout /t 5
goto loop
