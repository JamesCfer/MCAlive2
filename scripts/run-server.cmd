@echo off
REM MCAlive2 Paper server restart-loop launcher (Windows).
REM
REM Copy this file into your Paper SERVER folder (next to paper.jar / your server
REM jar) - not into your MCAlive2 repo checkout - and adjust PAPER_JAR below if your
REM jar has a different filename (e.g. paper-1.21.1-XXX.jar).
REM
REM Run the server through a loop like this one (instead of a bare
REM "java -jar paper.jar") if you want MCAlive2's auto-update.apply-when-empty
REM feature to work: when apply-when-empty is enabled in config.yml and an update
REM has already been staged in plugins/update/, the plugin calls Bukkit's
REM shutdown() once the server has been empty of players long enough, expecting
REM something outside the JVM to relaunch it so Paper applies the staged jar on the
REM next start. Without a restart loop like this one, that shutdown just takes the
REM server down and it stays down until a human starts it again.

setlocal
set PAPER_JAR=paper.jar

:loop
java -Xms2G -Xmx4G -jar %PAPER_JAR% --nogui
echo restarting in 5s...
timeout /t 5
goto loop
