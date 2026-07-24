#!/bin/bash
# Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../java17_check.sh
source "${ROOT_DIR}/java17_check.sh"
require_java17 java

EXAMPLE="${1:-detailed}"
if [ "$#" -gt 0 ]; then shift; fi
case "$EXAMPLE" in
  detailed|simple) ;;
  *) echo "ERROR: Example must be detailed or simple; found $EXAMPLE" >&2; exit 1 ;;
esac

export LD_LIBRARY_PATH="${ROOT_DIR}${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
PRELOAD="${ROOT_DIR}/libcef.so${LD_PRELOAD:+:${LD_PRELOAD}}"
exec env LD_PRELOAD="$PRELOAD" "${JAVA_HOME}/bin/java" --enable-native-access=ALL-UNNAMED -cp "${ROOT_DIR}:${ROOT_DIR}/*" "-Djava.library.path=${ROOT_DIR}" "-Djcef.path=${ROOT_DIR}" -Djcef.external_message_pump=false "tests.${EXAMPLE}.MainFrame" "$@"
