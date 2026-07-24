@echo off
:: Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
:: reserved. Use of this source code is governed by a BSD-style license
:: that can be found in the LICENSE file.

setlocal EnableExtensions DisableDelayedExpansion

if not defined JAVA_HOME (
  echo ERROR: JAVA_HOME must point to a JDK 17 installation 1>&2
  exit /B 1
)

if not exist "%JAVA_HOME%\release" (
  echo ERROR: JDK release metadata was not found at "%JAVA_HOME%\release" 1>&2
  exit /B 1
)

set "JAVA_VERSION="
for /F "usebackq tokens=1,* delims==" %%A in ("%JAVA_HOME%\release") do if "%%A" == "JAVA_VERSION" set "JAVA_VERSION=%%~B"
if "%JAVA_VERSION%" == "17" goto version_valid
if "%JAVA_VERSION:~0,3%" == "17." goto version_valid
echo ERROR: JDK 17 is required; JAVA_HOME reports version %JAVA_VERSION% 1>&2
exit /B 1

:version_valid
if "%~1" == "" exit /B 0
echo(%~1| %SystemRoot%\System32\findstr.exe /R /X "[A-Za-z0-9_-][A-Za-z0-9_-]*" >NUL
if errorlevel 1 (
  echo ERROR: Invalid JDK tool name: %~1 1>&2
  exit /B 1
)
if not exist "%JAVA_HOME%\bin\%~1.exe" (
  echo ERROR: %~1 was not found at "%JAVA_HOME%\bin\%~1.exe" 1>&2
  exit /B 1
)
shift
goto version_valid
