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
call "%~dp0distrib\java17_check.bat" java javac
if errorlevel 1 exit /B 1

where ant >NUL 2>NUL
if errorlevel 1 (
  echo ERROR: Apache Ant is required to compile JCEF 1>&2
  exit /B 1
)

for %%I in ("%~dp0..") do set "ROOT_DIR=%%~fI"
set "OUT_PATH=%ROOT_DIR%\out\%PLATFORM%"

call ant -f "%ROOT_DIR%\build.xml" "-Dout.path=%OUT_PATH%" compile-all
exit /B %ERRORLEVEL%

:usage
echo ERROR: Usage: compile.bat ^<windows_amd64^|windows_arm64^> 1>&2
exit /B 1
