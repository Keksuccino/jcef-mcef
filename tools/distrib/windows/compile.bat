@echo off
:: Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
:: reserved. Use of this source code is governed by a BSD-style license
:: that can be found in the LICENSE file.

setlocal EnableExtensions DisableDelayedExpansion

set "ROOT_DIR=%~dp0"
call "%ROOT_DIR%java17_check.bat" java javac jar
if errorlevel 1 exit /B 1

set "COMPILE_DIR=%TEMP%\jcef-classes-%RANDOM%-%RANDOM%"
set "SOURCE_LIST=%COMPILE_DIR%\sources.args"
mkdir "%COMPILE_DIR%"
if errorlevel 1 exit /B %ERRORLEVEL%

for /R "%ROOT_DIR%tests\detailed" %%F in (*.java) do echo "%%~fF">>"%SOURCE_LIST%"
for /R "%ROOT_DIR%tests\simple" %%F in (*.java) do echo "%%~fF">>"%SOURCE_LIST%"

"%JAVA_HOME%\bin\javac.exe" --release 17 -encoding UTF-8 -cp "%ROOT_DIR%*" -d "%COMPILE_DIR%" "@%SOURCE_LIST%"
if errorlevel 1 goto failed
"%JAVA_HOME%\bin\jar.exe" --create --file "%ROOT_DIR%jcef-tests.jar" --date=2000-01-01T00:00:00Z -C "%COMPILE_DIR%" tests
if errorlevel 1 goto failed

rmdir /S /Q "%COMPILE_DIR%"
exit /B 0

:failed
set "RETURN_CODE=%ERRORLEVEL%"
rmdir /S /Q "%COMPILE_DIR%"
exit /B %RETURN_CODE%
