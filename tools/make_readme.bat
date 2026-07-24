@echo off
:: Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
:: reserved. Use of this source code is governed by a BSD-style license
:: that can be found in the LICENSE file.

setlocal EnableExtensions DisableDelayedExpansion

if not "%~2" == "" goto usage
if "%~1" == "" goto usage
if /I "%~1" == "windows_amd64" goto platform_valid
if /I "%~1" == "windows_arm64" goto platform_valid
goto usage

:platform_valid
for %%I in ("%~dp0..") do set "ROOT_DIR=%%~fI"
set "DISTRIB_PATH=%ROOT_DIR%\binary_distrib\%~1"
if not exist "%DISTRIB_PATH%" mkdir "%DISTRIB_PATH%"
if errorlevel 1 exit /B 1
python.exe "%~dp0make_readme.py" --output-dir "%DISTRIB_PATH%" --platform "%~1"
exit /B %ERRORLEVEL%

:usage
echo ERROR: Usage: make_readme.bat ^<windows_amd64^|windows_arm64^> 1>&2
exit /B 1
