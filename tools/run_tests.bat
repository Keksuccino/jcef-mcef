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
set "JVM_LAUNCH_DIRECTORY=%CD%"
rem HotSpot uses Windows GetTempPath for its last file fallback, whose environment search order is
rem TMP, TEMP, USERPROFILE, then the Windows directory.
set "JVM_TEMP_PATH=%TMP%"
if not defined JVM_TEMP_PATH set "JVM_TEMP_PATH=%TEMP%"
if not defined JVM_TEMP_PATH set "JVM_TEMP_PATH=%USERPROFILE%"
if not defined JVM_TEMP_PATH set "JVM_TEMP_PATH=%SystemRoot%"
:prepare_crash_report
set "JVM_CRASH_REPORT_ID=%RANDOM%_%RANDOM%_%RANDOM%_%RANDOM%"
if exist "%OUT_PATH%\hs_err_pid*_%JVM_CRASH_REPORT_ID%.log" goto prepare_crash_report
if exist "%OUT_PATH%\jvm_pid_*_%JVM_CRASH_REPORT_ID%.log" goto prepare_crash_report
rem The doubled percent reaches HotSpot as %p. The random suffix isolates this invocation while
rem retaining the hs_err_pid prefix consumed by the workflow's crash-report artifact glob.
set "JVM_CRASH_REPORT=%OUT_PATH%\hs_err_pid%%p_%JVM_CRASH_REPORT_ID%.log"
rem Unified logging creates this sidecar during JVM startup, giving the batch process the exact PID
rem needed to distinguish HotSpot's unsuffixed working-directory and OS-temp fallback reports.
set "JVM_PID_LOG=%OUT_PATH%\jvm_pid_%%p_%JVM_CRASH_REPORT_ID%.log"
set "JUNIT_LAUNCHER_OPTION=-jar"
set "JUNIT_LAUNCHER_PATH=%JUNIT_JAR%"
set "JUNIT_LAUNCHER_CLASS="
set "CHROMIUM_PROCESS_ARGUMENT="
if /I "%PLATFORM%" == "windows_arm64" (
  rem Chromium constructs its browser ThreadPool before CEF's command-line callback. Keep this
  rem switch on java.exe's original process command line, then remove it in the wrapper before
  rem JUnit parses application arguments.
  set "JUNIT_LAUNCHER_OPTION=-cp"
  set "JUNIT_LAUNCHER_PATH=%JUNIT_JAR%;%CLASS_PATH%"
  set "JUNIT_LAUNCHER_CLASS=tests.junittests.WindowsJUnitLauncher"
  set "CHROMIUM_PROCESS_ARGUMENT=--disable-best-effort-tasks"
)
echo Running JUnit 6.1.2 for %PLATFORM%/%CONFIGURATION% ^(headless=%HEADLESS%^)
"%JAVA_HOME%\bin\java.exe" --enable-native-access=ALL-UNNAMED --add-opens=java.desktop/sun.awt=ALL-UNNAMED "-XX:ErrorFile=%JVM_CRASH_REPORT%" "-Xlog:os=info:file=%JVM_PID_LOG%:none:filecount=0" "-Djava.awt.headless=%HEADLESS%" "-Djava.library.path=%LIB_PATH%" "-Djcef.path=%LIB_PATH%" -Djcef.external_message_pump=false %JUNIT_LAUNCHER_OPTION% "%JUNIT_LAUNCHER_PATH%" %JUNIT_LAUNCHER_CLASS% %CHROMIUM_PROCESS_ARGUMENT% execute --disable-ansi-colors --disable-banner --details=summary --fail-if-no-tests --class-path "%CLASS_PATH%" %DEFAULT_SELECTOR% %REST_ARGS%
set "TEST_EXIT_CODE=%ERRORLEVEL%"
set "JVM_CRASH_REPORT_CREATED="
rem A fatal error during VM Exit can retain status zero, so the report itself is authoritative.
for %%F in ("%OUT_PATH%\hs_err_pid*_%JVM_CRASH_REPORT_ID%.log") do if exist "%%~fF" (
  echo ERROR: JVM fatal error report was created: "%%~fF" 1>&2
  set "JVM_CRASH_REPORT_CREATED=1"
)
set "JVM_PROCESS_ID="
set "JVM_PID_LOG_CREATED="
for %%F in ("%OUT_PATH%\jvm_pid_*_%JVM_CRASH_REPORT_ID%.log") do if exist "%%~fF" call :capture_jvm_process_id "%%~fF" "%%~nF"
if not defined JVM_PROCESS_ID (
  echo ERROR: JVM PID sidecar was not created; fallback crash reports cannot be ruled out 1>&2
  if "%TEST_EXIT_CODE%" == "0" set "TEST_EXIT_CODE=1"
)
rem Exact PID matching excludes concurrent JVMs. A stale file from a reused PID intentionally fails
rem closed because accepting a possible fatal report would make a retained zero status ambiguous.
if defined JVM_PROCESS_ID call :detect_jvm_fallback_crash_reports
if defined JVM_PID_LOG_CREATED del /Q "%JVM_PID_LOG_CREATED%" >nul 2>&1
if defined JVM_CRASH_REPORT_CREATED if "%TEST_EXIT_CODE%" == "0" set "TEST_EXIT_CODE=1"
exit /B %TEST_EXIT_CODE%

:capture_jvm_process_id
set "JVM_PID_LOG_CREATED=%~1"
for /F "tokens=3 delims=_" %%P in ("%~2") do set "JVM_PROCESS_ID=%%P"
for /F "delims=0123456789" %%N in ("%JVM_PROCESS_ID%") do set "JVM_PROCESS_ID="
exit /B 0

:detect_jvm_fallback_crash_reports
call :record_jvm_crash_report "%JVM_LAUNCH_DIRECTORY%\hs_err_pid%JVM_PROCESS_ID%.log"
if defined JVM_TEMP_PATH call :record_jvm_crash_report "%JVM_TEMP_PATH%\hs_err_pid%JVM_PROCESS_ID%.log"
exit /B 0

:record_jvm_crash_report
if not exist "%~1" exit /B 0
echo ERROR: JVM fatal error report was created: "%~f1" 1>&2
set "JVM_CRASH_REPORT_CREATED=1"
for %%D in ("%~1") do set "JVM_CRASH_REPORT_DIRECTORY=%%~dpD"
if /I not "%JVM_CRASH_REPORT_DIRECTORY%" == "%OUT_PATH%\" (
  copy /Y "%~1" "%OUT_PATH%\%~nx1" >nul
  if errorlevel 1 echo WARNING: Failed to copy JVM fatal error report into "%OUT_PATH%" 1>&2
)
exit /B 0

:check_selector
set "CURRENT_ARGUMENT=%~1"
if "%CURRENT_ARGUMENT:~0,1%" == "@" set "HAS_SELECTOR=1"
if /I "%CURRENT_ARGUMENT:~0,9%" == "--select-" set "HAS_SELECTOR=1"
if /I "%CURRENT_ARGUMENT:~0,7%" == "--scan-" set "HAS_SELECTOR=1"
for %%S in (-u -f -d -o -p -c -m -r -i) do if /I "%CURRENT_ARGUMENT%" == "%%S" set "HAS_SELECTOR=1"
for %%S in (-u -f -d -o -p -c -m -r -i) do if /I "%CURRENT_ARGUMENT:~0,3%" == "%%S=" set "HAS_SELECTOR=1"
exit /B 0
