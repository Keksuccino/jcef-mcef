#!/bin/bash
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

set -euo pipefail

readonly REMOTE_ROOT='s3://mcef-us-1/java-cef-builds'
readonly -a TARGETS=(
  linux_amd64
  linux_arm64
  macos_amd64
  macos_arm64
  windows_amd64
  windows_arm64
)

TEMP_DIRECTORY=''
S3_CONFIG_PATH=''
S3CMD_PATH=''
REMOTE_PREFIX=''
CHECKSUM_PHASE_STARTED=false
HOME_DIRECTORY=''
S3_CONFIG_CONTENT="${S3_CFG:-}"
unset S3_CFG

die() {
  echo "ERROR: $*" >&2
  exit 1
}

remote_object_exists() {
  local expected_uri="$1"
  local line
  local listed_uri
  while IFS= read -r line; do
    listed_uri="${line##*[[:space:]]}"
    if [ "$listed_uri" = "$expected_uri" ]; then
      return 0
    fi
  done <<< "$REMOTE_LISTING"
  return 1
}

is_canonical_remote_object() {
  local uri="$1"
  local target
  for target in "${TARGETS[@]}"; do
    if [ "$uri" = "${REMOTE_PREFIX}/${target}.tar.gz" ] ||
       [ "$uri" = "${REMOTE_PREFIX}/${target}.tar.gz.sha256" ]; then
      return 0
    fi
  done
  return 1
}

validate_remote_listing() {
  local line
  local listed_uri
  while IFS= read -r line; do
    if [ -z "$line" ]; then
      continue
    fi
    listed_uri="${line##*[[:space:]]}"
    if ! is_canonical_remote_object "$listed_uri"; then
      die "Remote publication contains an unexpected object: ${listed_uri}"
    fi
  done <<< "$REMOTE_LISTING"
}

cleanup() {
  local exit_status=$?
  local target
  trap - EXIT HUP INT TERM
  set +e
  if [ "$CHECKSUM_PHASE_STARTED" = true ] && [ "$exit_status" -ne 0 ] &&
     [ -n "$S3CMD_PATH" ] && [ -n "$S3_CONFIG_PATH" ]; then
    for target in "${TARGETS[@]}"; do
      if ! "$S3CMD_PATH" --config="$S3_CONFIG_PATH" del \
          "${REMOTE_PREFIX}/${target}.tar.gz.sha256" >/dev/null 2>&1; then
        echo "WARNING: Failed to remove remote checksum for ${target}" \
          'during publication cleanup' >&2
      fi
    done
  fi
  if [ -n "$TEMP_DIRECTORY" ]; then
    rm -rf -- "$TEMP_DIRECTORY"
  fi
  exit "$exit_status"
}

trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

if [ "$#" -ne 2 ]; then
  die 'Usage: publish_distributions.sh <40-lowercase-hex-commit-sha> <artifact-directory>'
fi

COMMIT_SHA="$1"
if [[ ! "$COMMIT_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  die 'Commit SHA must contain exactly 40 lowercase hexadecimal characters'
fi

if [ ! -d "$2" ]; then
  die "Artifact directory does not exist: $2"
fi
ARTIFACT_DIRECTORY="$(cd -- "$2" && pwd -P)"
REMOTE_PREFIX="${REMOTE_ROOT}/${COMMIT_SHA}"

# dotglob makes unexpected hidden entries part of the exact-set validation.
shopt -s dotglob nullglob
ARTIFACT_ENTRIES=("${ARTIFACT_DIRECTORY}"/*)
if [ "${#ARTIFACT_ENTRIES[@]}" -ne 12 ]; then
  die 'Artifact directory must contain exactly the 12 canonical archive and' \
    "checksum files; found ${#ARTIFACT_ENTRIES[@]}"
fi

if command -v sha256sum >/dev/null 2>&1; then
  HASH_COMMAND='sha256sum'
elif command -v shasum >/dev/null 2>&1; then
  HASH_COMMAND='shasum'
else
  die 'sha256sum or shasum is required to validate distribution archives'
fi

for target in "${TARGETS[@]}"; do
  archive_name="${target}.tar.gz"
  archive_path="${ARTIFACT_DIRECTORY}/${archive_name}"
  checksum_path="${archive_path}.sha256"
  if [ ! -f "$archive_path" ] || [ -L "$archive_path" ]; then
    die "Missing canonical regular archive: ${archive_name}"
  fi
  if [ ! -f "$checksum_path" ] || [ -L "$checksum_path" ]; then
    die "Missing canonical regular checksum: ${archive_name}.sha256"
  fi

  if [ "$HASH_COMMAND" = 'sha256sum' ]; then
    hash_output="$(sha256sum -- "$archive_path")" || die "Unable to hash ${archive_name}"
  else
    hash_output="$(shasum -a 256 "$archive_path")" || die "Unable to hash ${archive_name}"
  fi
  actual_hash="${hash_output%%[[:space:]]*}"
  if [[ ! "$actual_hash" =~ ^[0-9a-f]{64}$ ]]; then
    die "Hash tool returned an invalid SHA-256 for ${archive_name}"
  fi

  checksum_line=''
  extra_checksum_line=''
  exec 3< "$checksum_path"
  if ! IFS= read -r checksum_line <&3; then
    exec 3<&-
    die "Checksum must be one newline-terminated line: ${archive_name}.sha256"
  fi
  if IFS= read -r extra_checksum_line <&3 || [ -n "$extra_checksum_line" ]; then
    exec 3<&-
    die "Checksum must contain exactly one line: ${archive_name}.sha256"
  fi
  exec 3<&-
  # Python text output on Windows uses CRLF, which direct artifacts preserve.
  # Strip exactly the CR that precedes the LF already consumed by read; bare CR
  # and missing LF failed the first read above, and any other embedded/trailing
  # CR still fails equality.
  if [[ "$checksum_line" == *$'\r' ]]; then
    checksum_line="${checksum_line%$'\r'}"
  fi
  if [ "$checksum_line" != "${actual_hash}  ${archive_name}" ]; then
    die "SHA-256 validation failed for ${archive_name}"
  fi
done

if [ -z "$S3_CONFIG_CONTENT" ] ||
   [[ ! "$S3_CONFIG_CONTENT" =~ [^[:space:]] ]]; then
  die 'S3_CFG is required for distribution publication'
fi
S3CMD_PATH="$(command -v s3cmd || true)"
if [ -z "$S3CMD_PATH" ]; then
  die 's3cmd is required for distribution publication'
fi

# Credentials must never be written below HOME. Use an explicitly restrictive
# system temporary directory and remove it from the EXIT/signal cleanup path.
umask 077
if [ -n "${HOME:-}" ] && [ -d "$HOME" ]; then
  HOME_DIRECTORY="$(cd -- "$HOME" && pwd -P)"
fi
for temporary_root in /tmp /var/tmp; do
  if [ ! -d "$temporary_root" ] || [ ! -w "$temporary_root" ]; then
    continue
  fi
  candidate="$(mktemp -d "${temporary_root}/jcef-publisher.XXXXXX")" || continue
  candidate="$(cd -- "$candidate" && pwd -P)"
  if [ -n "$HOME_DIRECTORY" ] &&
     [[ "${candidate}/" == "${HOME_DIRECTORY%/}/"* ]]; then
    rm -rf -- "$candidate"
    continue
  fi
  TEMP_DIRECTORY="$candidate"
  break
done
if [ -z "$TEMP_DIRECTORY" ]; then
  die 'Unable to create a credential directory outside HOME'
fi
chmod 700 "$TEMP_DIRECTORY"
S3_CONFIG_PATH="${TEMP_DIRECTORY}/s3cfg"
printf '%s' "$S3_CONFIG_CONTENT" > "$S3_CONFIG_PATH"
chmod 600 "$S3_CONFIG_PATH"
S3_CONFIG_CONTENT=''

if ! REMOTE_LISTING="$("$S3CMD_PATH" --config="$S3_CONFIG_PATH" ls "${REMOTE_PREFIX}/")"; then
  die "Unable to inspect existing publication state for ${COMMIT_SHA}"
fi
validate_remote_listing

remote_checksum_count=0
for target in "${TARGETS[@]}"; do
  if remote_object_exists "${REMOTE_PREFIX}/${target}.tar.gz.sha256"; then
    remote_checksum_count=$((remote_checksum_count + 1))
  fi
done

if [ "$remote_checksum_count" -ne 0 ] && [ "$remote_checksum_count" -ne "${#TARGETS[@]}" ]; then
  die "Remote publication contains only ${remote_checksum_count} of" \
    "${#TARGETS[@]} checksums; refusing to modify it"
fi

if [ "$remote_checksum_count" -eq "${#TARGETS[@]}" ]; then
  for target in "${TARGETS[@]}"; do
    if ! remote_object_exists "${REMOTE_PREFIX}/${target}.tar.gz"; then
      die "Remote checksum exists without its archive: ${target}.tar.gz"
    fi
  done
  remote_object_directory="${TEMP_DIRECTORY}/remote-objects"
  mkdir "$remote_object_directory"
  for target in "${TARGETS[@]}"; do
    checksum_name="${target}.tar.gz.sha256"
    remote_checksum_path="${remote_object_directory}/${checksum_name}"
    if ! "$S3CMD_PATH" --config="$S3_CONFIG_PATH" get \
        "${REMOTE_PREFIX}/${checksum_name}" "$remote_checksum_path"; then
      die "Unable to read existing remote checksum: ${checksum_name}"
    fi
    if ! cmp -s "${ARTIFACT_DIRECTORY}/${checksum_name}" "$remote_checksum_path"; then
      die "Remote checksum does not match the local checksum: ${checksum_name}"
    fi
    archive_name="${target}.tar.gz"
    remote_archive_path="${remote_object_directory}/${archive_name}"
    if ! "$S3CMD_PATH" --config="$S3_CONFIG_PATH" get \
        "${REMOTE_PREFIX}/${archive_name}" "$remote_archive_path"; then
      die "Unable to read existing remote archive: ${archive_name}"
    fi
    if ! cmp -s "${ARTIFACT_DIRECTORY}/${archive_name}" "$remote_archive_path"; then
      die "Remote archive does not match the local archive: ${archive_name}"
    fi
    rm -f -- "$remote_checksum_path" "$remote_archive_path"
  done
  echo "Distribution set for ${COMMIT_SHA} is already published and matches exactly"
  exit 0
fi

# Checksums are the completion markers. Publish every archive first so no
# checksum can advertise a distribution set whose archives were not uploaded.
for target in "${TARGETS[@]}"; do
  archive_name="${target}.tar.gz"
  if ! "$S3CMD_PATH" --config="$S3_CONFIG_PATH" put -P \
      "${ARTIFACT_DIRECTORY}/${archive_name}" \
      "${REMOTE_PREFIX}/${archive_name}"; then
    die "Archive upload failed: ${archive_name}"
  fi
done

# From this point through successful exit, any failure or handled interruption
# removes all completion markers. Archives are intentionally retained so a
# clean retry can overwrite the incomplete publication.
CHECKSUM_PHASE_STARTED=true
for target in "${TARGETS[@]}"; do
  checksum_name="${target}.tar.gz.sha256"
  if ! "$S3CMD_PATH" --config="$S3_CONFIG_PATH" put -P \
      "${ARTIFACT_DIRECTORY}/${checksum_name}" \
      "${REMOTE_PREFIX}/${checksum_name}"; then
    die "Checksum upload failed: ${checksum_name}"
  fi
done

echo "Published all six JCEF distributions for ${COMMIT_SHA}"
