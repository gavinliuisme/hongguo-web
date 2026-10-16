@echo off
@setlocal DisableDelayedExpansion
@title hongguo-web

@rem ================================================================
@rem  Launch the API server. That is ALL this script does.
@rem
@rem  Design rule: the batch layer must stay trivial. cmd.exe parses
@rem  a batch file with a fixed-size buffer and tracks its position by
@rem  byte offset; a multi-byte character split at a buffer boundary,
@rem  or a mid-run "chcp" changing the console code page, makes that
@rem  bookkeeping desynchronise. The same bytes then get read twice,
@rem  so lines print twice and the command echo leaks back as
@rem  "C:\...>echo." garbage. Every non-trivial branch kept in cmd is
@rem  a branch that can trip over that.
@rem
@rem  So this file is 100% ASCII and contains no "chcp". It never
@rem  parses JSON, never passes a port on the command line, and never
@rem  decides anything. Configuration lives in
@rem  server\config\config.json and is read by the Node side.
@rem
@rem  Responsibilities kept here, because they cannot live in Node:
@rem    1. locate a Node runtime -- node is needed before any code runs
@rem    2. start server\src\main.js
@rem
@rem  The release package ships a matching Node runtime, so step 1
@rem  normally hits node\ on the first try. A repository checkout
@rem  carries no runtime and a machine without any Node is told how to
@rem  fix that, then stops.
@rem
@rem  Switches are passed straight through to main.js; see --help.
@rem ================================================================

@cd /d "%~dp0.."

set "ENTRY=server\src\main.js"

@rem --- locate a Node runtime ---------------------------------------
@rem The order mirrors the Node side: the bundled node\ wins over
@rem NODE_HOME, which wins over PATH. Preferring the bundled copy
@rem keeps a stale system Node (14/16) from being picked first.
@rem The Windows Node zip puts node.exe at the root of node\ (there is
@rem no bin\ subdirectory, unlike the Unix tarball).
set "NODE_BIN="
if exist "node\node.exe" set "NODE_BIN=%CD%\node\node.exe"
if not defined NODE_BIN if defined NODE_HOME if exist "%NODE_HOME%\node.exe" set "NODE_BIN=%NODE_HOME%\node.exe"
if not defined NODE_BIN for %%p in (node.exe) do set "NODE_BIN=%%~$PATH:p"
if not defined NODE_BIN goto :need_node

@rem --- entry present? ----------------------------------------------
if not exist "%ENTRY%" goto :no_entry
goto :run

@rem --- run ------------------------------------------------------------
@rem No encoding flags here on purpose: Node always writes UTF-8 and the
@rem console handles it; there is nothing to force.
:run
"%NODE_BIN%" "%ENTRY%" %*
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" goto :exited
echo.
echo   Service stopped normally.
pause
exit /b 0

@rem --- no Node: explain how to get one, then stop ---------------------
:need_node
echo.
echo   No Node runtime found on this machine.
echo.
echo   Release packages include a matching Node, so this normally means
echo   you are running from a source checkout. Either:
echo.
echo     1) Install Node.js 18+ from https://nodejs.org/  ^(recommended^)
echo     2) Unpack any Node 18+ into the node\ folder   ^(needs node.exe^)
echo.
echo   Then run this script again.
echo.
pause
exit /b 1

@rem --- failure exits -------------------------------------------------
:no_entry
echo.
echo   Missing %CD%\%ENTRY%
echo.
echo   The release package ships the full source, so it is missing here
echo   because the package was extracted incompletely.
echo.
pause
exit /b 1

:exited
echo.
echo   Service exited with code %RC%.
echo   Scroll up for the reason.
echo.
pause
exit /b %RC%
