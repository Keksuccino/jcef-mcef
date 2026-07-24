@echo off
:: Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
:: reserved. Use of this source code is governed by a BSD-style license
:: that can be found in the LICENSE file.

setlocal EnableExtensions DisableDelayedExpansion

if not "%~2" == "" goto usage
if "%~1" == "" goto usage

where python.exe >NUL 2>NUL
if errorlevel 1 (
  echo ERROR: Python 3 is required to create a JCEF distribution 1>&2
  exit /B 1
)

python.exe "%~dp0distrib\make_distrib.py" "%~1"
exit /B %ERRORLEVEL%

:usage
echo ERROR: Usage: make_distrib.bat ^<windows_amd64^|windows_arm64^> 1>&2
exit /B 1
