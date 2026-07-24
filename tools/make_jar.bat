@echo off
:: Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
:: reserved. Use of this source code is governed by a BSD-style license
:: that can be found in the LICENSE file.

setlocal

if not "%~2" == "" goto usage
if "%~1" == "" goto usage

set "PLATFORM=%~1"
if /I "%PLATFORM%" == "windows_amd64" goto platform_valid
if /I "%PLATFORM%" == "windows_arm64" goto platform_valid
goto usage

:platform_valid
call "%~dp0distrib\java17_check.bat" jar
if errorlevel 1 exit /B %ERRORLEVEL%

for %%I in ("%~dp0..") do set "ROOT_DIR=%%~fI"
set "OUT_DIR=%ROOT_DIR%\out\%PLATFORM%"

if not exist "%OUT_DIR%\org" (
  echo ERROR: Compiled production classes do not exist in "%OUT_DIR%"; run compile.bat first 1>&2
  exit /B 1
)
if not exist "%OUT_DIR%\tests" (
  echo ERROR: Compiled test classes do not exist in "%OUT_DIR%"; run compile.bat first 1>&2
  exit /B 1
)

"%JAVA_HOME%\bin\jar.exe" --create --file "%OUT_DIR%\jcef.jar" --date=2000-01-01T00:00:00Z --manifest "%ROOT_DIR%\java\manifest\MANIFEST.MF" -C "%OUT_DIR%" org
if errorlevel 1 exit /B %ERRORLEVEL%
"%JAVA_HOME%\bin\jar.exe" --create --file "%OUT_DIR%\jcef-tests.jar" --date=2000-01-01T00:00:00Z -C "%OUT_DIR%" tests
exit /B %ERRORLEVEL%

:usage
echo ERROR: Usage: make_jar.bat ^<windows_amd64^|windows_arm64^> 1>&2
exit /B 1
