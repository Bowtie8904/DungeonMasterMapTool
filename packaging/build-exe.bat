@echo off
rem Builds a local Windows app image (DungeonMasterMapTool.exe with bundled Java) into dist\DungeonMasterMapTool.
rem Double-click to run, or pass "skiptests" for a faster build and/or "console" to show a console window with JVM errors.
setlocal
cd /d "%~dp0.."

set MVN_ARGS=-B --no-transfer-progress package
set TESTS=with tests
if /i "%~1"=="skiptests" (
    set MVN_ARGS=%MVN_ARGS% -DskipTests
    set TESTS=without tests
)

set JP_EXTRA=
if /i "%~1"=="console" set JP_EXTRA=--win-console
if /i "%~2"=="console" set JP_EXTRA=--win-console

echo [1/3] Building the application %TESTS% (this can take a few minutes)...
call mvn %MVN_ARGS%
if errorlevel 1 goto :failed

echo.
echo [2/3] Preparing the package input...
if exist jpackage-input rmdir /s /q jpackage-input
if exist dist rmdir /s /q dist
mkdir jpackage-input
for %%F in (target\DungeonMasterMapTool-*-all.jar) do copy /y "%%F" jpackage-input\DungeonMasterMapTool.jar >nul

echo [3/3] Creating the app image with jpackage...
jpackage --type app-image --name DungeonMasterMapTool --input jpackage-input --main-jar DungeonMasterMapTool.jar --main-class dmmt.DungeonMasterMapToolLauncher --icon packaging\icon.ico --add-modules java.base,java.desktop,java.prefs,java.sql,jdk.jfr,jdk.unsupported,jdk.httpserver --dest dist %JP_EXTRA%
if errorlevel 1 goto :failed
rmdir /s /q jpackage-input

echo.
echo Done: %CD%\dist\DungeonMasterMapTool\DungeonMasterMapTool.exe
pause
exit /b 0

:failed
echo.
echo Build failed.
if exist jpackage-input rmdir /s /q jpackage-input
pause
exit /b 1