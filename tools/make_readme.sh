#!/bin/bash
# Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "ERROR: Usage: make_readme.sh <linux_amd64|linux_arm64|macos_amd64|macos_arm64>" >&2
  exit 1
fi

case "$1" in
  linux_amd64|linux_arm64|macos_amd64|macos_arm64) ;;
  *)
    echo "ERROR: Unsupported POSIX target '$1'" >&2
    exit 1
    ;;
esac

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DISTRIB_PATH="${ROOT_DIR}/binary_distrib/$1"
mkdir -p "$DISTRIB_PATH"
python3 "${SCRIPT_DIR}/make_readme.py" --output-dir "$DISTRIB_PATH" --platform "$1"
