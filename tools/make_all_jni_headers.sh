#!/bin/bash
# Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

set -euo pipefail

if [ "$#" -gt 1 ]; then
  echo "ERROR: Usage: make_all_jni_headers.sh [--verify]" >&2
  exit 1
fi
if [ "$#" -eq 1 ] && [ "$1" != "--verify" ]; then
  echo "ERROR: Unknown option: $1" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# javac -h generates platform-independent headers directly from production
# sources, so accepting a target would imply an architecture distinction that
# does not exist.
if [ "$#" -eq 1 ]; then
  python3 "${SCRIPT_DIR}/make_jni_headers.py" --verify
else
  python3 "${SCRIPT_DIR}/make_jni_headers.py"
fi
