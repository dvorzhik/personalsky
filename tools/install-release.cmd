@echo off
rem Обёртка для запуска установки release-APK двойным щелчком или из cmd.
rem Все аргументы пробрасываются в tools\install-release.ps1, например:
rem   install-release.cmd -Force
rem   install-release.cmd -Build -Serial 37281FDJH0033N
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-release.ps1" %*
set EXITCODE=%ERRORLEVEL%
echo.
if not "%EXITCODE%"=="0" echo Скрипт завершился с кодом %EXITCODE%.
pause
exit /b %EXITCODE%
