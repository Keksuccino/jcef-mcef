#!/bin/bash
# Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "ERROR: Usage: make_distrib.sh <linux_amd64|linux_arm64|macos_amd64|macos_arm64>" >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: Python 3 is required to create a JCEF distribution" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec python3 "${SCRIPT_DIR}/distrib/make_distrib.py" "$1"
