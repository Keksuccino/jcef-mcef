#!/bin/bash
# Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "ERROR: Usage: make_jar.sh <linux_amd64|linux_arm64|macos_amd64|macos_arm64>" >&2
  exit 1
fi

PLATFORM="$1"
case "$PLATFORM" in
  linux_amd64|linux_arm64|macos_amd64|macos_arm64) ;;
  *)
    echo "ERROR: Unsupported POSIX target '$PLATFORM'. Expected linux_amd64, linux_arm64, macos_amd64, or macos_arm64" >&2
    exit 1
    ;;
esac

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=distrib/java17_check.sh
source "${SCRIPT_DIR}/distrib/java17_check.sh"
require_java17 jar

ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUT_DIR="${ROOT_DIR}/out/${PLATFORM}"

if [ ! -d "${OUT_DIR}/org" ] || [ ! -d "${OUT_DIR}/tests" ]; then
  echo "ERROR: Compiled classes do not exist in ${OUT_DIR}; run compile.sh first" >&2
  exit 1
fi

"${JAVA_HOME}/bin/jar" --create --file "${OUT_DIR}/jcef.jar" --date=2000-01-01T00:00:00Z --manifest "${ROOT_DIR}/java/manifest/MANIFEST.MF" -C "$OUT_DIR" org
"${JAVA_HOME}/bin/jar" --create --file "${OUT_DIR}/jcef-tests.jar" --date=2000-01-01T00:00:00Z -C "$OUT_DIR" tests
