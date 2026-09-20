@echo off
rem Обёртка для запуска проверки релиза двойным щелчком или из cmd.
rem Все аргументы пробрасываются в tools\verify-release.ps1, например:
rem   verify-release.cmd
rem   verify-release.cmd -AabPath "D:\build\app-release.aab"
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0verify-release.ps1" %*
set EXITCODE=%ERRORLEVEL%
echo.
if not "%EXITCODE%"=="0" echo Проверка нашла проблемы (код %EXITCODE%).
pause
exit /b %EXITCODE%
