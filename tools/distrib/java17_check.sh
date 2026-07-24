#!/bin/bash
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

# Validate JAVA_HOME from the JDK's release metadata instead of parsing
# vendor-specific command output. Callers pass every JDK tool they require.
require_java17() {
  if [ -z "${JAVA_HOME:-}" ]; then
    echo "ERROR: JAVA_HOME must point to a JDK 17 installation" >&2
    return 1
  fi

  local release_file="${JAVA_HOME}/release"
  if [ ! -f "$release_file" ]; then
    echo "ERROR: JDK release metadata was not found at $release_file" >&2
    return 1
  fi

  local java_version
  java_version="$(sed -n 's/^JAVA_VERSION="\([^"]*\)"$/\1/p' "$release_file" | head -n 1)"
  case "$java_version" in
    17|17.*) ;;
    *)
      echo "ERROR: JDK 17 is required; JAVA_HOME reports version ${java_version:-unknown}" >&2
      return 1
      ;;
  esac

  local tool
  for tool in "$@"; do
    case "$tool" in
      *[!A-Za-z0-9_-]*|'')
        echo "ERROR: Invalid JDK tool name: $tool" >&2
        return 1
        ;;
    esac
    if [ ! -x "${JAVA_HOME}/bin/${tool}" ]; then
      echo "ERROR: ${tool} was not found at ${JAVA_HOME}/bin/${tool}" >&2
      return 1
    fi
  done
}
