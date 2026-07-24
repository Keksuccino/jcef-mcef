@echo off
:: Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
:: reserved. Use of this source code is governed by a BSD-style license
:: that can be found in the LICENSE file.

setlocal

if "%~3" == "" (
  echo ERROR: Usage: run.bat ^<windows_amd64^|windows_arm64^> ^<Debug^|Release^> ^<detailed^|simple^> [arguments...] 1>&2
  exit /B 1
)

set "PLATFORM=%~1"
if /I not "%PLATFORM%" == "windows_amd64" if /I not "%PLATFORM%" == "windows_arm64" (
  echo ERROR: Unsupported Windows target '%PLATFORM%'. Expected windows_amd64 or windows_arm64 1>&2
  exit /B 1
)

call "%~dp0distrib\java17_check.bat" java
if errorlevel 1 exit /B 1

for %%I in ("%~dp0..") do set "ROOT_DIR=%%~fI"
set "OUT_PATH=%ROOT_DIR%\out\%PLATFORM%"
set "LIB_PATH=%ROOT_DIR%\jcef_build\native\%~2"
set "RUN_TYPE=%~3"

if not exist "%LIB_PATH%" (
  echo ERROR: Native build output path does not exist: "%LIB_PATH%" 1>&2
  exit /B 1
)
if not exist "%OUT_PATH%" (
  echo ERROR: Java build output path does not exist: "%OUT_PATH%" 1>&2
  exit /B 1
)

set "REST_ARGS="
shift
shift
shift
:collect_args
if "%~1" == "" goto run
set REST_ARGS=%REST_ARGS% "%~1"
shift
goto collect_args

:run
set "CLASS_PATH=%ROOT_DIR%\third_party\jogamp\jar\*;%OUT_PATH%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
"%JAVA_HOME%\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp "%CLASS_PATH%" "-Djava.library.path=%LIB_PATH%" "-Djcef.path=%LIB_PATH%" "tests.%RUN_TYPE%.MainFrame" %REST_ARGS%
exit /B %ERRORLEVEL%
