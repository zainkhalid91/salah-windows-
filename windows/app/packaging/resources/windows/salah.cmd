@echo off
rem salah: the Salah command line tool. Runs the CLI on the Java runtime bundled with Salah.
setlocal
for /f "tokens=2 delims=:" %%a in ('chcp') do set "_salah_cp=%%a"
set "_salah_cp=%_salah_cp: =%"
set "_salah_cp=%_salah_cp:.=%"
rem UTF-8 so the box drawing renders, then restore the console code page.
chcp 65001 >nul
"%~dp0..\..\runtime\bin\java.exe" -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:auto -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "%~dp0..\*" salah.cli.MainKt %*
set "_salah_rc=%ERRORLEVEL%"
if defined _salah_cp chcp %_salah_cp% >nul
exit /b %_salah_rc%
