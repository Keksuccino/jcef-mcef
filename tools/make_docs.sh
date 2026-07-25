#!/bin/bash
# Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=distrib/java17_check.sh
source "${SCRIPT_DIR}/distrib/java17_check.sh"
require_java17 javadoc

ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUT_PATH="${ROOT_DIR}/out/docs"
CLASS_PATH="${ROOT_DIR}/third_party/jogamp/jar/gluegen-rt.jar:${ROOT_DIR}/third_party/jogamp/jar/jogl-all.jar"

mkdir -p "$OUT_PATH"
"${JAVA_HOME}/bin/javadoc" --release 17 -encoding UTF-8 -docencoding UTF-8 -charset UTF-8 -Xdoclint:none -Werror -notimestamp -windowtitle "CEF Java API Docs" -bottom "<center><a href='https://github.com/chromiumembedded/java-cef' target='_top'>Chromium Embedded Framework (CEF)</a> Copyright &copy; 2013 Marshall A. Greenblatt</center>" -nodeprecated -d "$OUT_PATH" -classpath "$CLASS_PATH" -sourcepath "${ROOT_DIR}/java" -link https://docs.oracle.com/en/java/javase/17/docs/api/ -subpackages org.cef
