@echo off
rem Builds the app image with a console window attached so JVM launch errors are visible.
call "%~dp0build-exe.bat" skiptests console
