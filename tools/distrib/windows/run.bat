@echo off
:: Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
:: reserved. Use of this source code is governed by a BSD-style license
:: that can be found in the LICENSE file.

setlocal EnableExtensions DisableDelayedExpansion

set "ROOT_DIR=%~dp0"
call "%ROOT_DIR%java17_check.bat" java
if errorlevel 1 exit /B 1

"%JAVA_HOME%\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp "%ROOT_DIR%;%ROOT_DIR%*" "-Djava.library.path=%ROOT_DIR%" "-Djcef.path=%ROOT_DIR%" -Djcef.external_message_pump=false tests.detailed.MainFrame %*
exit /B %ERRORLEVEL%
