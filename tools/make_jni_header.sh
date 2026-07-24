#!/bin/bash
# Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "ERROR: Usage: make_jni_header.sh <class-name> [--verify]" >&2
  exit 1
fi
if [ "$#" -eq 2 ] && [ "$2" != "--verify" ]; then
  echo "ERROR: Unknown option: $2" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# javac -h generates platform-independent headers directly from production
# sources, so no target argument is accepted.
ARGS=(--class-name "$1")
if [ "$#" -eq 2 ]; then
  ARGS+=(--verify)
fi

python3 "${SCRIPT_DIR}/make_jni_headers.py" "${ARGS[@]}"
