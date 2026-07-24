@echo off
:: Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
:: reserved. Use of this source code is governed by a BSD-style license
:: that can be found in the LICENSE file.

setlocal

call "%~dp0distrib\java17_check.bat" javadoc
if errorlevel 1 exit /B %ERRORLEVEL%

for %%I in ("%~dp0..") do set "ROOT_DIR=%%~fI"
set "OUT_PATH=%ROOT_DIR%\out\docs"
set "CLASS_PATH=%ROOT_DIR%\third_party\jogamp\jar\gluegen-rt.jar;%ROOT_DIR%\third_party\jogamp\jar\jogl-all.jar"
if not exist "%OUT_PATH%" mkdir "%OUT_PATH%"

"%JAVA_HOME%\bin\javadoc.exe" --release 17 -encoding UTF-8 -docencoding UTF-8 -charset UTF-8 -Xdoclint:none -notimestamp -windowtitle "CEF Java API Docs" -bottom "<center><a href='https://github.com/chromiumembedded/java-cef' target='_top'>Chromium Embedded Framework (CEF)</a> Copyright &copy; 2013 Marshall A. Greenblatt</center>" -nodeprecated -d "%OUT_PATH%" -classpath "%CLASS_PATH%" -sourcepath "%ROOT_DIR%\java" -link https://docs.oracle.com/en/java/javase/17/docs/api/ -subpackages org.cef
exit /B %ERRORLEVEL%
