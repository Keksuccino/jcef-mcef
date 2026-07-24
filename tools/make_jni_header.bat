@echo off
:: Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
:: reserved. Use of this source code is governed by a BSD-style license
:: that can be found in the LICENSE file.

setlocal

if "%~1" == "" (
  echo ERROR: Usage: make_jni_header.bat ^<class-name^> [--verify] 1>&2
  exit /B 1
)
if not "%~2" == "" if not "%~2" == "--verify" (
  echo ERROR: Unknown option: %~2 1>&2
  exit /B 1
)
if not "%~3" == "" (
  echo ERROR: Unexpected argument: %~3 1>&2
  exit /B 1
)

set "VERIFY_ARG="
if "%~2" == "--verify" set "VERIFY_ARG=--verify"
rem javac -h output is platform-independent, so no target argument is accepted.
python "%~dp0make_jni_headers.py" --class-name "%~1" %VERIFY_ARG%
exit /B %ERRORLEVEL%
