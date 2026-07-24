#!/bin/bash
# Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

set -euo pipefail

if [ "$#" -lt 3 ]; then
  echo "ERROR: Usage: run.sh <linux_amd64|linux_arm64> <Debug|Release> <detailed|simple> [arguments...]" >&2
  exit 1
fi

PLATFORM="$1"
case "$PLATFORM" in
  linux_amd64|linux_arm64) ;;
  *)
    echo "ERROR: Unsupported Linux target '$PLATFORM'. Expected linux_amd64 or linux_arm64" >&2
    exit 1
    ;;
esac

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=distrib/java17_check.sh
source "${SCRIPT_DIR}/distrib/java17_check.sh"
require_java17 java

ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUT_PATH="${ROOT_DIR}/out/${PLATFORM}"
LIB_PATH="${ROOT_DIR}/jcef_build/native/$2"

if [ ! -d "$LIB_PATH" ]; then
  echo "ERROR: Native build output path does not exist: $LIB_PATH" >&2
  exit 1
fi
if [ ! -d "$OUT_PATH" ]; then
  echo "ERROR: Java build output path does not exist: $OUT_PATH" >&2
  exit 1
fi

export LD_LIBRARY_PATH="${LIB_PATH}${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
PRELOAD="${LIB_PATH}/libcef.so${LD_PRELOAD:+:${LD_PRELOAD}}"
CLASS_PATH="${ROOT_DIR}/third_party/jogamp/jar/*:${OUT_PATH}"
RUN_TYPE="$3"
shift 3

exec env LD_PRELOAD="$PRELOAD" "${JAVA_HOME}/bin/java" --enable-native-access=ALL-UNNAMED -cp "$CLASS_PATH" "-Djava.library.path=${LIB_PATH}" "-Djcef.path=${LIB_PATH}" "tests.${RUN_TYPE}.MainFrame" "$@"
