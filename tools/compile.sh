#!/bin/bash
# Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "ERROR: Usage: compile.sh <linux_amd64|linux_arm64|macos_amd64|macos_arm64>" >&2
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
require_java17 java javac

if ! command -v ant >/dev/null 2>&1; then
  echo "ERROR: Apache Ant is required to compile JCEF" >&2
  exit 1
fi

ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUT_PATH="${ROOT_DIR}/out/${PLATFORM}"

ant -f "${ROOT_DIR}/build.xml" "-Dout.path=${OUT_PATH}" compile-all
