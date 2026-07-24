#!/bin/bash
# Copyright (c) 2019 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "ERROR: Usage: run_tests.sh <linux_amd64|linux_arm64|macos_amd64|macos_arm64> <Debug|Release> [--headless] [JUnit arguments...]" >&2
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

case "$2" in
  Debug|debug) CONFIGURATION="Debug" ;;
  Release|release) CONFIGURATION="Release" ;;
  *)
    echo "ERROR: Build configuration must be Debug or Release; found $2" >&2
    exit 1
    ;;
esac
shift 2

HEADLESS=false
if [ "${1:-}" = "--headless" ]; then
  HEADLESS=true
  shift
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=distrib/java17_check.sh
source "${SCRIPT_DIR}/distrib/java17_check.sh"
require_java17 java

ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUT_PATH="${ROOT_DIR}/out/${PLATFORM}"
LIB_PATH="${ROOT_DIR}/jcef_build/native/${CONFIGURATION}"
JUNIT_JAR="${ROOT_DIR}/third_party/junit/junit-platform-console-standalone-6.1.2.jar"

if [ ! -d "$LIB_PATH" ]; then
  echo "ERROR: Native build output path does not exist: $LIB_PATH" >&2
  exit 1
fi
if [ ! -d "$OUT_PATH" ]; then
  echo "ERROR: Java build output path does not exist: $OUT_PATH" >&2
  exit 1
fi
if [ ! -f "$JUNIT_JAR" ]; then
  echo "ERROR: JUnit ConsoleLauncher was not found: $JUNIT_JAR" >&2
  exit 1
fi

JAVA_OPTIONS=(--enable-native-access=ALL-UNNAMED "-Djava.awt.headless=${HEADLESS}" "-Djcef.path=${LIB_PATH}" -Djcef.external_message_pump=false)
case "$(uname -s)" in
  Linux)
    case "$PLATFORM" in
      linux_amd64|linux_arm64) ;;
      *)
        echo "ERROR: Platform '$PLATFORM' is not a supported Linux test output" >&2
        exit 1
        ;;
    esac
    if [ ! -f "${LIB_PATH}/libcef.so" ] || [ ! -f "${LIB_PATH}/libjcef.so" ]; then
      echo "ERROR: Matching Linux CEF/JCEF libraries were not found in ${LIB_PATH}" >&2
      exit 1
    fi
    JAVA_LIBRARY_PATH="$LIB_PATH"
    JAVA_OPTIONS+=("-Djava.library.path=${JAVA_LIBRARY_PATH}")
    export LD_LIBRARY_PATH="${LIB_PATH}${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
    export LD_PRELOAD="${LIB_PATH}/libcef.so${LD_PRELOAD:+:${LD_PRELOAD}}"
    ;;
  Darwin)
    case "$PLATFORM" in
      macos_amd64|macos_arm64) ;;
      *)
        echo "ERROR: Platform '$PLATFORM' is not a supported macOS test output" >&2
        exit 1
        ;;
    esac
    APP_BUNDLE="${LIB_PATH}/jcef_app.app"
    APP_JAVA_PATH="${APP_BUNDLE}/Contents/Java"
    CEF_FRAMEWORK="${APP_BUNDLE}/Contents/Frameworks/Chromium Embedded Framework.framework"
    if [ ! -f "${APP_JAVA_PATH}/libjcef.dylib" ] || [ ! -d "$CEF_FRAMEWORK" ]; then
      echo "ERROR: Matching macOS JCEF app and CEF framework were not found in ${LIB_PATH}" >&2
      exit 1
    fi
    JAVA_LIBRARY_PATH="${APP_JAVA_PATH}:${LIB_PATH}"
    JAVA_OPTIONS=(--add-opens=java.desktop/sun.awt=ALL-UNNAMED --add-opens=java.desktop/sun.lwawt=ALL-UNNAMED --add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED "${JAVA_OPTIONS[@]}" "-Djava.library.path=${JAVA_LIBRARY_PATH}")
    # Headless suites must not claim AppKit's first process thread. Native GUI
    # suites retain the CEF/AWT first-thread contract used by the app bundle.
    if [ "$HEADLESS" = false ]; then
      JAVA_OPTIONS=(-XstartOnFirstThread "${JAVA_OPTIONS[@]}")
    fi
    export DYLD_LIBRARY_PATH="${APP_JAVA_PATH}${DYLD_LIBRARY_PATH:+:${DYLD_LIBRARY_PATH}}"
    ;;
  *)
    echo "ERROR: run_tests.sh supports Linux and macOS; use run_tests.bat on Windows" >&2
    exit 1
    ;;
esac

HAS_SELECTOR=false
for ARGUMENT in "$@"; do
  case "$ARGUMENT" in
    --select-*|--scan-*|-u|-u=*|-f|-f=*|-d|-d=*|-o|-o=*|-p|-p=*|-c|-c=*|-m|-m=*|-r|-r=*|-i|-i=*|@*) HAS_SELECTOR=true ;;
  esac
done

CLASS_PATH="${OUT_PATH}:${ROOT_DIR}/third_party/jogamp/jar/gluegen-rt.jar:${ROOT_DIR}/third_party/jogamp/jar/jogl-all.jar"
JUNIT_ARGUMENTS=(execute --disable-ansi-colors --disable-banner --details=summary --fail-if-no-tests --class-path "$CLASS_PATH")
if [ "$HAS_SELECTOR" = true ]; then
  JUNIT_ARGUMENTS+=("$@")
else
  JUNIT_ARGUMENTS+=(--select-package=tests.junittests "$@")
fi

echo "Running JUnit 6.1.2 for ${PLATFORM}/${CONFIGURATION} (headless=${HEADLESS})"
if [ "$(uname -s)" = Darwin ] && [ "$HEADLESS" = false ]; then
  # AppKit must continue running on the process first thread while JUnit and
  # native CEF execute on the launcher's worker thread.
  exec "${JAVA_HOME}/bin/java" "${JAVA_OPTIONS[@]}" -cp "${JUNIT_JAR}:${CLASS_PATH}" tests.junittests.MacJUnitLauncher "${JUNIT_ARGUMENTS[@]}"
fi
exec "${JAVA_HOME}/bin/java" "${JAVA_OPTIONS[@]}" -jar "$JUNIT_JAR" "${JUNIT_ARGUMENTS[@]}"
