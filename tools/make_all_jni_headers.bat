@echo off
:: Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
:: reserved. Use of this source code is governed by a BSD-style license
:: that can be found in the LICENSE file.

setlocal

if not "%~2" == "" (
  echo ERROR: Usage: make_all_jni_headers.bat [--verify] 1>&2
  exit /B 1
)
if not "%~1" == "" if not "%~1" == "--verify" (
  echo ERROR: Unknown option: %~1 1>&2
  exit /B 1
)

set "VERIFY_ARG="
if "%~1" == "--verify" set "VERIFY_ARG=--verify"
rem javac -h output is platform-independent, so no target argument is accepted.
python "%~dp0make_jni_headers.py" %VERIFY_ARG%
exit /B %ERRORLEVEL%
