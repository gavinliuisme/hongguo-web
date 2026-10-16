@echo off
@setlocal DisableDelayedExpansion
@title hongguo-web - stop

@rem ================================================================
@rem  Stop the running services. That is ALL this script does.
@rem
@rem  Design rule: see the long note at the top of start.bat. In
@rem  short -- the batch layer stays trivial and 100% ASCII, because
@rem  every non-trivial branch in cmd is a branch that can trip over
@rem  the byte-offset bookkeeping and start printing lines twice.
@rem
@rem  So this file does not parse JSON, does not scan processes, and
@rem  does not check ports. It hands "--stop" to main.js and lets the
@rem  Node side do the work: the ports it reports on come from
@rem  server\config\config.json, and reading that here would mean
@rem  keeping a second copy of the config parser in cmd.
@rem ================================================================

@cd /d "%~dp0.."

set "ENTRY=server\src\main.js"

@rem --- locate a Node runtime ---------------------------------------
set "NODE_BIN="
if exist "node\node.exe" set "NODE_BIN=%CD%\node\node.exe"
if not defined NODE_BIN if defined NODE_HOME if exist "%NODE_HOME%\node.exe" set "NODE_BIN=%NODE_HOME%\node.exe"
if not defined NODE_BIN for %%p in (node.exe) do set "NODE_BIN=%%~$PATH:p"
if not defined NODE_BIN goto :need_node

if not exist "%ENTRY%" goto :no_entry
goto :run

:run
@rem --stop always exits 0: "stopped it" and "nothing was running"
@rem are both normal outcomes of an idempotent stop.
"%NODE_BIN%" "%ENTRY%" --stop
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" goto :failed
echo.
echo   Stop request finished.
pause
exit /b 0

:failed
echo.
echo   Stop failed with code %RC%.
echo   Scroll up for the reason.
echo.
pause
exit /b %RC%

:need_node
echo.
echo   No Node runtime found, so there is no way to run the stop command.
echo   If services are running you can end them by hand: open Task Manager,
echo   select any "node.exe" started from this folder, and end the task.
echo.
pause
exit /b 1

:no_entry
echo.
echo   Missing %CD%\%ENTRY%
echo.
echo   Nothing to stop through this script. If the service is running from
echo   another copy of the project, stop it there instead.
echo.
pause
exit /b 1
