#!/bin/bash
# Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../java17_check.sh
source "${ROOT_DIR}/java17_check.sh"
require_java17 java javac jar

APP_PATH="${ROOT_DIR}/jcef_app.app"
APP_JAVA_DIR="${APP_PATH}/Contents/Java"
COMPILE_DIR="$(mktemp -d)"
SOURCE_LIST="${COMPILE_DIR}/sources.args"
trap 'rm -rf "$COMPILE_DIR"' EXIT

find "${ROOT_DIR}/tests/detailed" "${ROOT_DIR}/tests/simple" -name '*.java' -type f -print0 | while IFS= read -r -d '' source; do
  printf '"%s"\n' "$source"
done > "$SOURCE_LIST"

"${JAVA_HOME}/bin/javac" --release 17 -encoding UTF-8 -cp "${ROOT_DIR}/*" -d "$COMPILE_DIR" "@${SOURCE_LIST}"
"${JAVA_HOME}/bin/jar" --create --file "${ROOT_DIR}/jcef-tests.jar" --date=2000-01-01T00:00:00Z -C "$COMPILE_DIR" tests
cp -f "${ROOT_DIR}/jcef-tests.jar" "$APP_JAVA_DIR"
/usr/bin/codesign --force --sign - --timestamp=none "$APP_PATH"
/usr/bin/codesign --verify --deep --strict --verbose=2 "$APP_PATH"
