@echo off
:: Copyright (c) 2019 The Chromium Embedded Framework Authors. All rights
:: reserved. Use of this source code is governed by a BSD-style license
:: that can be found in the LICENSE file.

setlocal

if "%~2" == "" (
  echo ERROR: Usage: run_tests.bat ^<windows_amd64^|windows_arm64^> ^<Debug^|Release^> [--headless] [JUnit arguments...] 1>&2
  exit /B 1
)

call "%~dp0distrib\java17_check.bat" java
if errorlevel 1 exit /B 1

set "PLATFORM=%~1"
if /I not "%PLATFORM%" == "windows_amd64" if /I not "%PLATFORM%" == "windows_arm64" (
  echo ERROR: Platform '%PLATFORM%' is not a supported Windows test output 1>&2
  exit /B 1
)

set "CONFIGURATION="
if /I "%~2" == "Debug" set "CONFIGURATION=Debug"
if /I "%~2" == "Release" set "CONFIGURATION=Release"
if not defined CONFIGURATION (
  echo ERROR: Build configuration must be Debug or Release; found %~2 1>&2
  exit /B 1
)

for %%I in ("%~dp0..") do set "ROOT_DIR=%%~fI"
set "OUT_PATH=%ROOT_DIR%\out\%PLATFORM%"
set "LIB_PATH=%ROOT_DIR%\jcef_build\native\%CONFIGURATION%"
set "JUNIT_JAR=%ROOT_DIR%\third_party\junit\junit-platform-console-standalone-6.1.2.jar"

if not exist "%LIB_PATH%" (
  echo ERROR: Native build output path does not exist: "%LIB_PATH%" 1>&2
  exit /B 1
)
if not exist "%OUT_PATH%" (
  echo ERROR: Java build output path does not exist: "%OUT_PATH%" 1>&2
  exit /B 1
)
if not exist "%JUNIT_JAR%" (
  echo ERROR: JUnit ConsoleLauncher was not found: "%JUNIT_JAR%" 1>&2
  exit /B 1
)
if not exist "%LIB_PATH%\libcef.dll" (
  echo ERROR: Matching Windows CEF library was not found: "%LIB_PATH%\libcef.dll" 1>&2
  exit /B 1
)
if not exist "%LIB_PATH%\jcef.dll" (
  echo ERROR: Matching Windows JCEF library was not found: "%LIB_PATH%\jcef.dll" 1>&2
  exit /B 1
)

set "HAS_SELECTOR="
set "REST_ARGS="
set "HEADLESS=false"
shift
shift
if /I "%~1" == "--headless" (
  set "HEADLESS=true"
  shift
)
:collect_args
if "%~1" == "" goto collected_args
call :check_selector "%~1"
set REST_ARGS=%REST_ARGS% "%~1"
shift
goto collect_args

:collected_args
set "DEFAULT_SELECTOR="
if not defined HAS_SELECTOR set "DEFAULT_SELECTOR=--select-package=tests.junittests"

:run
set "CLASS_PATH=%OUT_PATH%;%ROOT_DIR%\third_party\jogamp\jar\gluegen-rt.jar;%ROOT_DIR%\third_party\jogamp\jar\jogl-all.jar"
set "PATH=%LIB_PATH%;%JAVA_HOME%\bin;%PATH%"
echo Running JUnit 6.1.2 for %PLATFORM%/%CONFIGURATION% ^(headless=%HEADLESS%^)
"%JAVA_HOME%\bin\java.exe" --enable-native-access=ALL-UNNAMED "-Djava.awt.headless=%HEADLESS%" "-Djava.library.path=%LIB_PATH%" "-Djcef.path=%LIB_PATH%" -Djcef.external_message_pump=false -jar "%JUNIT_JAR%" execute --disable-ansi-colors --disable-banner --details=summary --fail-if-no-tests --class-path "%CLASS_PATH%" %DEFAULT_SELECTOR% %REST_ARGS%
set "TEST_EXIT_CODE=%ERRORLEVEL%"
exit /B %TEST_EXIT_CODE%

:check_selector
set "CURRENT_ARGUMENT=%~1"
if "%CURRENT_ARGUMENT:~0,1%" == "@" set "HAS_SELECTOR=1"
if /I "%CURRENT_ARGUMENT:~0,9%" == "--select-" set "HAS_SELECTOR=1"
if /I "%CURRENT_ARGUMENT:~0,7%" == "--scan-" set "HAS_SELECTOR=1"
for %%S in (-u -f -d -o -p -c -m -r -i) do if /I "%CURRENT_ARGUMENT%" == "%%S" set "HAS_SELECTOR=1"
for %%S in (-u -f -d -o -p -c -m -r -i) do if /I "%CURRENT_ARGUMENT:~0,3%" == "%%S=" set "HAS_SELECTOR=1"
exit /B 0
