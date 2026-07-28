#!/bin/bash -p
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

set -euo pipefail
set +x
umask 077

if [[ $- != *p* ]]; then
  echo 'ERROR: execute publish_workflow_run.sh directly so its privileged Bash startup is preserved' >&2
  exit 1
fi
# Privileged startup prevents Bash from evaluating BASH_ENV and importing
# exported functions before line 1. Remove named startup/search variables now;
# Archive tools interpret these environment variables as command-line options.
# Strip them even though exact-commit source materialization uses raw Git blobs,
# so no later child can accidentally inherit caller-selected archive behavior.
unset BASH_ENV ENV CDPATH GLOBIGNORE TAR_OPTIONS TAR_READER_OPTIONS TAR_WRITER_OPTIONS TAPE
export LC_ALL=C

# Capture an optional explicit credential using the same precedence as gh,
# then remove every GitHub credential/host variable before the first child
# process can inherit it. The captured value remains an unexported shell
# variable and is attached only to individual gh calls and the final trusted
# publisher process.
unset ENV_TOKEN_SOURCE ENV_TOKEN_CONTENT
ENV_TOKEN_SOURCE=''
ENV_TOKEN_CONTENT=''
if [ "${GH_TOKEN+x}" = x ]; then
  ENV_TOKEN_SOURCE='GH_TOKEN'
  ENV_TOKEN_CONTENT="$GH_TOKEN"
elif [ "${GITHUB_TOKEN+x}" = x ]; then
  ENV_TOKEN_SOURCE='GITHUB_TOKEN'
  ENV_TOKEN_CONTENT="$GITHUB_TOKEN"
fi
unset GITHUB_TOKEN GH_TOKEN GH_ENTERPRISE_TOKEN GITHUB_ENTERPRISE_TOKEN GH_HOST GH_DEBUG GH_FORCE_TTY GH_PAGER PAGER GH_REPO

readonly REPOSITORY='Keksuccino/jcef-mcef'
readonly REPOSITORY_URL='https://github.com/Keksuccino/jcef-mcef.git'
readonly WORKFLOW_NAME='Build JCEF'
readonly WORKFLOW_FILE='build-jcef.yml'
readonly WORKFLOW_PATH='.github/workflows/build-jcef.yml'
readonly SYSTEM_ENV_PATH='/usr/bin/env'
readonly WRAPPER_PATH='tools/distrib/publish_workflow_run.sh'
readonly PUBLISHER_PATH='tools/distrib/publish_distributions.sh'
readonly VERIFIER_PATH='tools/distrib/verify_distribution_archive.py'
readonly SOURCES_JAR_PATH='tools/distrib/sources_jar.py'
readonly BINARY_JAR_NAME='jcef-mcef.jar'
readonly SOURCES_JAR_NAME='jcef-mcef-sources.jar'
readonly SOURCE_TREE_PATH='java/org/cef'
readonly SOURCE_ARCHIVE_TREE_PATH='org/cef'
readonly MAX_SOURCE_COUNT=4096
readonly MAX_SOURCE_SIZE=$((4 * 1024 * 1024))
readonly MAX_SOURCE_ARCHIVE_SIZE=$((72 * 1024 * 1024))
readonly MAX_SOURCE_ARCHIVE_PATH_SIZE=1024
readonly MAX_SOURCE_ARCHIVE_OVERHEAD=$((MAX_SOURCE_COUNT * (76 + 2 * MAX_SOURCE_ARCHIVE_PATH_SIZE) + 22))
readonly MAX_TOTAL_SOURCE_SIZE=$((MAX_SOURCE_ARCHIVE_SIZE - MAX_SOURCE_ARCHIVE_OVERHEAD))
readonly MAX_SOURCE_TREE_DEPTH=32
readonly MAX_SOURCE_TREE_ENTRY_COUNT=8192
readonly MAX_SOURCE_TREE_LIST_SIZE=$((16 * 1024 * 1024))
readonly -a EXPECTED_JOBS=(
  'Java sources'
  'Linux x86_64'
  'Linux arm64'
  'macOS x86_64'
  'macOS arm64'
  'Windows x86_64'
  'Windows arm64'
)
readonly -a TARGETS=(
  linux_amd64
  linux_arm64
  macos_amd64
  macos_arm64
  windows_amd64
  windows_arm64
)
readonly EXPECTED_JOB_COUNT="${#EXPECTED_JOBS[@]}"
readonly EXPECTED_ARTIFACT_COUNT="$(( ${#TARGETS[@]} * 2 + 2 ))"

GIT_PATH=''
GH_PATH=''
HASH_PATH=''
HASH_KIND=''
WC_PATH=''
TR_PATH=''
CMP_PATH=''
MKDIR_PATH=''
CHMOD_PATH=''
HEAD_PATH=''
REPOSITORY_ROOT=''
REPOSITORY_ID=''
WORKFLOW_ID=''
RUN_ID=''
RUN_SHA=''
RUN_ATTEMPT=''
VALIDATED_HEAD_SHA=''
AUTHENTICATED_LOGIN=''
PRIVATE_ROOT=''
ARTIFACT_DIRECTORY=''
SOURCE_SNAPSHOT_ROOT=''
PUBLISHER_COPY=''
VERIFIER_COPY=''
SOURCES_JAR_COPY=''

JOB_NAMES=()
JOB_IDS=()
ARTIFACT_IDS=()
ARTIFACT_NAMES=()
ARTIFACT_SIZES=()
ARTIFACT_DIGESTS=()
SANITIZED_ENV_ARGS=(-u BASH_ENV -u ENV -u SHELLOPTS -u BASHOPTS -u CDPATH -u GLOBIGNORE -u BASH_XTRACEFD -u PS4 -u ENV_TOKEN_SOURCE -u ENV_TOKEN_CONTENT -u GH_ENTERPRISE_TOKEN -u GITHUB_ENTERPRISE_TOKEN -u GH_DEBUG -u GH_FORCE_TTY -u GH_PAGER -u PAGER -u GH_REPO -u TAR_OPTIONS -u TAR_READER_OPTIONS -u TAR_WRITER_OPTIONS -u TAPE)

die() {
  echo "ERROR: $*" >&2
  exit 1
}

resolve_executable() {
  local executable
  executable="$(builtin type -P "$1" || true)"
  if [[ "$executable" != /* ]] || [ ! -f "$executable" ] || [ ! -x "$executable" ]; then
    return 1
  fi
  printf '%s\n' "$executable"
}

prepare_sanitized_environment() {
  local environment_output
  local entry
  local variable_name
  if [ ! -f "$SYSTEM_ENV_PATH" ] || [ ! -x "$SYSTEM_ENV_PATH" ]; then
    die "Required system environment tool is unavailable: ${SYSTEM_ENV_PATH}"
  fi
  environment_output="$("$SYSTEM_ENV_PATH")" || die 'Unable to inspect the caller environment safely'
  while IFS= read -r entry; do
    case "$entry" in
      BASH_FUNC_*%%=*)
        variable_name="${entry%%=*}"
        SANITIZED_ENV_ARGS+=(-u "$variable_name")
        ;;
      GIT_*=*)
        # Git assigns behavior to a broad and evolving GIT_* namespace. Strip
        # every exported member instead of maintaining a bypass-prone allowlist.
        variable_name="${entry%%=*}"
        SANITIZED_ENV_ARGS+=(-u "$variable_name")
        if [[ "$variable_name" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
          unset "$variable_name"
        fi
        ;;
    esac
  done <<< "$environment_output"
}

git_command() {
  # Keep this as the only Git boundary. Replacement refs can redefine an exact
  # commit locally, while global/system configuration can inject helpers or
  # transport behavior before the trusted source snapshot is materialized.
  "$SYSTEM_ENV_PATH" "${SANITIZED_ENV_ARGS[@]}" GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 GIT_OPTIONAL_LOCKS=0 GIT_TERMINAL_PROMPT=0 "$GIT_PATH" --no-replace-objects "$@"
}

gh_api() {
  if [ -n "$ENV_TOKEN_SOURCE" ]; then
    GH_TOKEN="$ENV_TOKEN_CONTENT" GH_HOST=github.com GH_PROMPT_DISABLED=1 GH_NO_UPDATE_NOTIFIER=1 "$SYSTEM_ENV_PATH" "${SANITIZED_ENV_ARGS[@]}" "$GH_PATH" api --hostname github.com --method GET "$@"
  else
    GH_HOST=github.com GH_PROMPT_DISABLED=1 GH_NO_UPDATE_NOTIFIER=1 "$SYSTEM_ENV_PATH" "${SANITIZED_ENV_ARGS[@]}" "$GH_PATH" api --hostname github.com --method GET "$@"
  fi
}

cleanup() {
  if [ -n "$PRIVATE_ROOT" ] && [ -d "$PRIVATE_ROOT" ]; then
    if [ -n "$CHMOD_PATH" ] && [ -x "$CHMOD_PATH" ] && [ -n "$SOURCE_SNAPSHOT_ROOT" ] && [ -d "$SOURCE_SNAPSHOT_ROOT" ]; then
      "$CHMOD_PATH" -R u+w "$SOURCE_SNAPSHOT_ROOT" || true
    fi
    rm -rf -- "$PRIVATE_ROOT"
  fi
}

hash_file() {
  local path="$1"
  local output
  local digest
  if [ "$HASH_KIND" = sha256sum ]; then
    output="$("$HASH_PATH" -- "$path")" || return 1
  else
    output="$("$HASH_PATH" -a 256 "$path")" || return 1
  fi
  digest="${output%%[[:space:]]*}"
  if [[ ! "$digest" =~ ^[0-9a-f]{64}$ ]]; then
    return 1
  fi
  printf '%s\n' "$digest"
}

file_size() {
  local path="$1"
  local size
  size="$("$WC_PATH" -c < "$path" | "$TR_PATH" -d '[:space:]')" || return 1
  case "$size" in
    ''|*[!0-9]*) return 1 ;;
  esac
  printf '%s\n' "$size"
}

job_name_is_expected() {
  local actual_name="$1"
  local expected_name
  for expected_name in "${EXPECTED_JOBS[@]}"; do
    if [ "$actual_name" = "$expected_name" ]; then
      return 0
    fi
  done
  return 1
}

artifact_name_is_expected() {
  local actual_name="$1"
  local target
  if [ "$actual_name" = "$BINARY_JAR_NAME" ] || [ "$actual_name" = "$SOURCES_JAR_NAME" ]; then
    return 0
  fi
  for target in "${TARGETS[@]}"; do
    if [ "$actual_name" = "${target}.tar.gz" ] || [ "$actual_name" = "${target}.tar.gz.sha256" ]; then
      return 0
    fi
  done
  return 1
}

require_unique_value() {
  local candidate="$1"
  shift
  local existing
  for existing in "$@"; do
    if [ "$candidate" = "$existing" ]; then
      return 1
    fi
  done
  return 0
}

require_pristine_publication_scripts() {
  local relative_path
  for relative_path in "$WRAPPER_PATH" "$PUBLISHER_PATH" "$VERIFIER_PATH" "$SOURCES_JAR_PATH"; do
    if [ ! -f "$REPOSITORY_ROOT/$relative_path" ] || [ -L "$REPOSITORY_ROOT/$relative_path" ] || [ ! -r "$REPOSITORY_ROOT/$relative_path" ]; then
      die "Publication helper must be a readable regular file: ${relative_path}"
    fi
    if { [ "$relative_path" = "$WRAPPER_PATH" ] || [ "$relative_path" = "$PUBLISHER_PATH" ]; } && [ ! -x "$REPOSITORY_ROOT/$relative_path" ]; then
      die "Publication script must be executable: ${relative_path}"
    fi
    if ! git_command -C "$REPOSITORY_ROOT" ls-files --error-unmatch -- "$relative_path" >/dev/null 2>&1; then
      die "Publication helper is not tracked by HEAD: ${relative_path}"
    fi
    if ! git_command -C "$REPOSITORY_ROOT" diff --quiet HEAD -- "$relative_path"; then
      die "Publication helper does not match HEAD: ${relative_path}"
    fi
    # Compare the actual bytes as well because Git's working-tree diff honors
    # assume-unchanged and skip-worktree hints that must not bypass publication.
    if ! git_command -C "$REPOSITORY_ROOT" show "HEAD:${relative_path}" | "$CMP_PATH" -s - "$REPOSITORY_ROOT/$relative_path"; then
      die "Publication helper bytes do not match HEAD: ${relative_path}"
    fi
  done
}

prepare_private_directory() {
  PRIVATE_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/jcef-publish.XXXXXX")" || die 'Unable to create a private publication directory'
  "$CHMOD_PATH" 700 "$PRIVATE_ROOT"
  # Keep the trusted publisher outside this child directory. The publisher
  # enables dotglob and intentionally rejects anything beyond the exact
  # canonical artifact count.
  ARTIFACT_DIRECTORY="${PRIVATE_ROOT}/artifacts"
  SOURCE_SNAPSHOT_ROOT="${PRIVATE_ROOT}/source-snapshot"
  if ! "$MKDIR_PATH" -m 700 -- "$ARTIFACT_DIRECTORY"; then
    die 'Unable to create the private artifact directory'
  fi
}

prepare_source_snapshot() {
  local tree_listing
  local tree_listing_size
  local tree_entry
  local metadata
  local source_mode
  local source_type
  local object_id
  local object_size
  local metadata_extra
  local source_path
  local relative_path
  local archive_path
  local -a path_parts
  local maximum_path_components
  local destination_path
  local destination_parent
  local materialized_size
  local source_index
  local source_entry_count=0
  local java_source_count=0
  local total_source_size=0
  local -a source_object_ids=()
  local -a source_object_sizes=()
  local -a source_paths=()
  if [ -z "$PRIVATE_ROOT" ] || [ ! -d "$PRIVATE_ROOT" ] || [ -L "$PRIVATE_ROOT" ]; then
    die 'Private publication directory is unavailable for the source snapshot'
  fi
  if [ -z "$SOURCE_SNAPSHOT_ROOT" ] || [ "${SOURCE_SNAPSHOT_ROOT%/*}" != "$PRIVATE_ROOT" ] || [ -e "$SOURCE_SNAPSHOT_ROOT" ] || [ -L "$SOURCE_SNAPSHOT_ROOT" ]; then
    die 'Private source snapshot destination is invalid or already exists'
  fi
  if [[ ! "$VALIDATED_HEAD_SHA" =~ ^[0-9a-f]{40}$ ]] || [ "$VALIDATED_HEAD_SHA" != "$RUN_SHA" ]; then
    die 'Validated HEAD is unavailable for the source snapshot'
  fi
  if ! "$MKDIR_PATH" -m 700 -- "$SOURCE_SNAPSHOT_ROOT"; then
    die 'Unable to create the private source snapshot directory'
  fi
  tree_listing="${PRIVATE_ROOT}/source-tree.entries"
  # `git archive` is intentionally forbidden here because export-ignore and
  # export-subst can be redefined by mutable $GIT_DIR/info/attributes. Enumerate
  # the committed tree and read each blob by object ID so only exact Git object
  # bytes can become the release-verification oracle.
  if ! git_command -C "$REPOSITORY_ROOT" ls-tree -r -t -z -l --full-tree "$VALIDATED_HEAD_SHA" -- "$SOURCE_TREE_PATH" | "$SYSTEM_ENV_PATH" "${SANITIZED_ENV_ARGS[@]}" "$HEAD_PATH" -c "$((MAX_SOURCE_TREE_LIST_SIZE + 1))" > "$tree_listing"; then
    die "Unable to enumerate ${SOURCE_TREE_PATH} from validated HEAD ${VALIDATED_HEAD_SHA}"
  fi
  if ! tree_listing_size="$(file_size "$tree_listing")" || [ "$tree_listing_size" -gt "$MAX_SOURCE_TREE_LIST_SIZE" ]; then
    die 'Validated HEAD source-tree metadata exceeds its size limit'
  fi
  while IFS= read -r -d '' tree_entry; do
    metadata="${tree_entry%%$'\t'*}"
    source_path="${tree_entry#*$'\t'}"
    if [ "$metadata" = "$tree_entry" ] || [ "$source_path" = "$tree_entry" ]; then
      die 'Validated HEAD source tree contains malformed Git metadata'
    fi
    IFS=' ' read -r source_mode source_type object_id object_size metadata_extra <<< "$metadata"
    if [[ ! "$object_id" =~ ^[0-9a-f]{40}$ ]] || [ -n "$metadata_extra" ]; then
      die "Validated HEAD source tree contains malformed Git metadata: ${source_path:-unknown}"
    fi
    if [ "$source_type" = tree ]; then
      if [ "$source_mode" != 040000 ] || [ "$object_size" != - ]; then
        die "Validated HEAD source tree contains a malformed directory: ${source_path:-unknown}"
      fi
      case "$source_path" in
        java|java/org|"$SOURCE_TREE_PATH") continue ;;
      esac
      maximum_path_components="$MAX_SOURCE_TREE_DEPTH"
    elif [ "$source_type" = blob ]; then
      if { [ "$source_mode" != 100644 ] && [ "$source_mode" != 100755 ]; } || [[ ! "$object_size" =~ ^(0|[1-9][0-9]{0,15})$ ]]; then
        die "Validated HEAD source tree contains a non-regular entry: ${source_path:-unknown}"
      fi
      maximum_path_components="$((MAX_SOURCE_TREE_DEPTH + 1))"
    else
      die "Validated HEAD source tree contains a non-regular entry: ${source_path:-unknown}"
    fi
    if [[ "$source_path" != "$SOURCE_TREE_PATH/"* ]] || [[ "$source_path" == *\\* ]] || [[ "$source_path" =~ [[:cntrl:]] ]]; then
      die "Validated HEAD source tree contains an unsafe path: ${source_path:-unknown}"
    fi
    case "/${source_path}/" in
      *//*|*/./*|*/../*) die "Validated HEAD source tree contains a noncanonical path: ${source_path}" ;;
    esac
    relative_path="${source_path#"$SOURCE_TREE_PATH/"}"
    archive_path="${SOURCE_ARCHIVE_TREE_PATH}/${relative_path}"
    IFS='/' read -r -a path_parts <<< "$relative_path"
    # LC_ALL=C makes Bash's string length a raw byte length, matching the
    # helper's UTF-8 archive-path cap even when a committed path is non-ASCII.
    if [ -z "$relative_path" ] || [ "${#path_parts[@]}" -gt "$maximum_path_components" ] || [ "${#archive_path}" -gt "$MAX_SOURCE_ARCHIVE_PATH_SIZE" ]; then
      die "Validated HEAD source tree path exceeds its limits: ${source_path}"
    fi
    ((source_entry_count += 1))
    if [ "$source_entry_count" -gt "$MAX_SOURCE_TREE_ENTRY_COUNT" ]; then
      die 'Validated HEAD source tree exceeds its entry-count limit'
    fi
    if [ "$source_type" = tree ]; then
      continue
    fi
    if [[ "$source_path" != *.java ]]; then
      continue
    fi
    ((java_source_count += 1))
    if [ "$java_source_count" -gt "$MAX_SOURCE_COUNT" ] || [ "$object_size" -gt "$MAX_SOURCE_SIZE" ]; then
      die "Validated HEAD Java source exceeds its count or file-size limit: ${source_path}"
    fi
    total_source_size=$((total_source_size + object_size))
    if [ "$total_source_size" -gt "$MAX_TOTAL_SOURCE_SIZE" ]; then
      die 'Validated HEAD Java sources exceed the total size limit'
    fi
    source_object_ids+=("$object_id")
    source_object_sizes+=("$object_size")
    source_paths+=("$source_path")
  done < "$tree_listing"
  if [ "$java_source_count" -eq 0 ]; then
    die "Validated HEAD does not contain Java sources under ${SOURCE_TREE_PATH}"
  fi
  if [ "${#source_object_ids[@]}" -ne "$java_source_count" ] || [ "${#source_object_sizes[@]}" -ne "$java_source_count" ] || [ "${#source_paths[@]}" -ne "$java_source_count" ]; then
    die 'Validated HEAD Java source metadata collection is misaligned'
  fi

  # Materialize only after the complete committed tree has passed every bound.
  # This prevents an oversized late tree entry from consuming directories,
  # subprocesses, or blob storage before the snapshot is rejected.
  for ((source_index = 0; source_index < java_source_count; source_index++)); do
    object_id="${source_object_ids[$source_index]}"
    object_size="${source_object_sizes[$source_index]}"
    source_path="${source_paths[$source_index]}"
    destination_path="${SOURCE_SNAPSHOT_ROOT}/${source_path}"
    destination_parent="${destination_path%/*}"
    if ! "$MKDIR_PATH" -p -- "$destination_parent"; then
      die "Unable to create source snapshot directory for ${source_path}"
    fi
    if [ -e "$destination_path" ] || [ -L "$destination_path" ]; then
      die "Validated HEAD source tree contains a duplicate path: ${source_path}"
    fi
    if ! git_command -C "$REPOSITORY_ROOT" cat-file blob "$object_id" > "$destination_path"; then
      die "Unable to materialize source blob for ${source_path}"
    fi
    if [ ! -f "$destination_path" ] || [ -L "$destination_path" ]; then
      die "Materialized source is not a regular file: ${source_path}"
    fi
    if ! materialized_size="$(file_size "$destination_path")" || [ "$materialized_size" != "$object_size" ]; then
      die "Materialized source size does not match its Git object: ${source_path}"
    fi
  done
  if [ ! -d "$SOURCE_SNAPSHOT_ROOT/java" ] || [ -L "$SOURCE_SNAPSHOT_ROOT/java" ] || [ ! -d "$SOURCE_SNAPSHOT_ROOT/java/org" ] || [ -L "$SOURCE_SNAPSHOT_ROOT/java/org" ] || [ ! -d "$SOURCE_SNAPSHOT_ROOT/$SOURCE_TREE_PATH" ] || [ -L "$SOURCE_SNAPSHOT_ROOT/$SOURCE_TREE_PATH" ]; then
    die 'Validated HEAD source snapshot does not contain the canonical production source tree'
  fi
  # Freeze the materialized tree before credentials reach the publisher. Cleanup
  # restores owner write access only so the private tree can be removed.
  if ! "$CHMOD_PATH" -R a-w "$SOURCE_SNAPSHOT_ROOT"; then
    die 'Unable to make the validated HEAD source snapshot read-only'
  fi
}

prepare_trusted_publisher() {
  if [ -z "$PRIVATE_ROOT" ] || [ ! -d "$PRIVATE_ROOT" ]; then
    die 'Private publication directory is unavailable for the trusted publisher copy'
  fi
  if [ -z "$ARTIFACT_DIRECTORY" ] || [ ! -d "$ARTIFACT_DIRECTORY" ]; then
    die 'Private artifact directory is unavailable for the trusted publisher copy'
  fi
  PUBLISHER_COPY="${PRIVATE_ROOT}/publish_distributions.sh"
  VERIFIER_COPY="${PRIVATE_ROOT}/verify_distribution_archive.py"
  SOURCES_JAR_COPY="${PRIVATE_ROOT}/sources_jar.py"
  if [[ ! "$VALIDATED_HEAD_SHA" =~ ^[0-9a-f]{40}$ ]]; then
    die 'Validated HEAD is unavailable for the trusted publisher copy'
  fi
  if ! git_command -C "$REPOSITORY_ROOT" show "${VALIDATED_HEAD_SHA}:${PUBLISHER_PATH}" > "$PUBLISHER_COPY"; then
    die 'Unable to copy the publisher from HEAD'
  fi
  if ! git_command -C "$REPOSITORY_ROOT" show "${VALIDATED_HEAD_SHA}:${VERIFIER_PATH}" > "$VERIFIER_COPY"; then
    die 'Unable to copy the distribution verifier from HEAD'
  fi
  if ! git_command -C "$REPOSITORY_ROOT" show "${VALIDATED_HEAD_SHA}:${SOURCES_JAR_PATH}" > "$SOURCES_JAR_COPY"; then
    die 'Unable to copy the sources JAR helper from HEAD'
  fi
  if [ ! -f "$PUBLISHER_COPY" ] || [ -L "$PUBLISHER_COPY" ] || [ ! -f "$VERIFIER_COPY" ] || [ -L "$VERIFIER_COPY" ] || [ ! -f "$SOURCES_JAR_COPY" ] || [ -L "$SOURCES_JAR_COPY" ]; then
    die 'The trusted publication copies are not regular files'
  fi
  if ! git_command -C "$REPOSITORY_ROOT" show "${VALIDATED_HEAD_SHA}:${PUBLISHER_PATH}" | "$CMP_PATH" -s - "$PUBLISHER_COPY"; then
    die 'The trusted publisher copy does not byte-match HEAD'
  fi
  if ! git_command -C "$REPOSITORY_ROOT" show "${VALIDATED_HEAD_SHA}:${VERIFIER_PATH}" | "$CMP_PATH" -s - "$VERIFIER_COPY"; then
    die 'The trusted distribution verifier copy does not byte-match HEAD'
  fi
  if ! git_command -C "$REPOSITORY_ROOT" show "${VALIDATED_HEAD_SHA}:${SOURCES_JAR_PATH}" | "$CMP_PATH" -s - "$SOURCES_JAR_COPY"; then
    die 'The trusted sources JAR helper copy does not byte-match HEAD'
  fi
  "$CHMOD_PATH" 400 "$PUBLISHER_COPY"
  "$CHMOD_PATH" 400 "$VERIFIER_COPY"
  "$CHMOD_PATH" 400 "$SOURCES_JAR_COPY"
}

invoke_trusted_publisher() {
  if [ -z "$PUBLISHER_COPY" ] || [ ! -f "$PUBLISHER_COPY" ] || [ -L "$PUBLISHER_COPY" ] || [ ! -r "$PUBLISHER_COPY" ]; then
    die 'Trusted publisher copy is unavailable'
  fi
  if [ -z "$VERIFIER_COPY" ] || [ ! -f "$VERIFIER_COPY" ] || [ -L "$VERIFIER_COPY" ] || [ ! -r "$VERIFIER_COPY" ] || [ "${VERIFIER_COPY%/*}" != "${PUBLISHER_COPY%/*}" ]; then
    die 'Trusted sibling distribution verifier copy is unavailable'
  fi
  if [ -z "$SOURCES_JAR_COPY" ] || [ ! -f "$SOURCES_JAR_COPY" ] || [ -L "$SOURCES_JAR_COPY" ] || [ ! -r "$SOURCES_JAR_COPY" ] || [ "${SOURCES_JAR_COPY%/*}" != "${PUBLISHER_COPY%/*}" ]; then
    die 'Trusted sibling sources JAR helper copy is unavailable'
  fi
  if [ -z "$SOURCE_SNAPSHOT_ROOT" ] || [ ! -d "$SOURCE_SNAPSHOT_ROOT/$SOURCE_TREE_PATH" ] || [ -L "$SOURCE_SNAPSHOT_ROOT" ] || [ -L "$SOURCE_SNAPSHOT_ROOT/java" ] || [ -L "$SOURCE_SNAPSHOT_ROOT/java/org" ] || [ -L "$SOURCE_SNAPSHOT_ROOT/$SOURCE_TREE_PATH" ]; then
    die 'Trusted validated-HEAD source snapshot is unavailable'
  fi
  # Give Bash the private path so it owns and protects its parser input. An
  # inherited descriptor would remain shared with publisher descendants, which
  # could advance the shared open-file offset while Bash is still parsing.
  if [ -n "$ENV_TOKEN_SOURCE" ]; then
    GH_TOKEN="$ENV_TOKEN_CONTENT" GH_HOST=github.com GH_PROMPT_DISABLED=1 GH_NO_UPDATE_NOTIFIER=1 "$SYSTEM_ENV_PATH" "${SANITIZED_ENV_ARGS[@]}" /bin/bash -p "$PUBLISHER_COPY" "$RUN_SHA" "$ARTIFACT_DIRECTORY" "$SOURCE_SNAPSHOT_ROOT"
  else
    GH_HOST=github.com GH_PROMPT_DISABLED=1 GH_NO_UPDATE_NOTIFIER=1 "$SYSTEM_ENV_PATH" "${SANITIZED_ENV_ARGS[@]}" /bin/bash -p "$PUBLISHER_COPY" "$RUN_SHA" "$ARTIFACT_DIRECTORY" "$SOURCE_SNAPSHOT_ROOT"
  fi
}

refresh_and_validate_master() {
  local origin_url
  local head_sha
  local origin_master_sha
  origin_url="$(git_command -C "$REPOSITORY_ROOT" remote get-url origin)" || die 'Unable to inspect the origin remote'
  if [ "$origin_url" != "$REPOSITORY_URL" ]; then
    die "origin must be ${REPOSITORY_URL}; found ${origin_url:-no URL}"
  fi
  require_pristine_publication_scripts
  if ! git_command -C "$REPOSITORY_ROOT" fetch --quiet --no-tags origin '+refs/heads/master:refs/remotes/origin/master'; then
    die 'Unable to fetch origin/master'
  fi
  head_sha="$(git_command -C "$REPOSITORY_ROOT" rev-parse --verify 'HEAD^{commit}')" || die 'Unable to resolve HEAD'
  origin_master_sha="$(git_command -C "$REPOSITORY_ROOT" rev-parse --verify 'refs/remotes/origin/master^{commit}')" || die 'Unable to resolve freshly fetched origin/master'
  if [[ ! "$head_sha" =~ ^[0-9a-f]{40}$ ]] || [ "$head_sha" != "$origin_master_sha" ]; then
    die "HEAD must exactly match freshly fetched origin/master; HEAD=${head_sha:-unknown}, origin/master=${origin_master_sha:-unknown}"
  fi
  if [ -n "$RUN_SHA" ] && [ "$head_sha" != "$RUN_SHA" ]; then
    die "HEAD changed or no longer matches workflow run ${RUN_ID}: ${head_sha}"
  fi
  VALIDATED_HEAD_SHA="$head_sha"
}

load_repository_identity() {
  if ! REPOSITORY_ID="$(gh_api "repos/${REPOSITORY}" --jq 'if ((.id | type) == "number" and .id > 0 and .full_name == "Keksuccino/jcef-mcef" and .default_branch == "master") then (.id | tostring) else "invalid" end')"; then
    die "Unable to inspect repository ${REPOSITORY}"
  fi
  case "$REPOSITORY_ID" in
    ''|0|invalid|*[!0-9]*) die "Repository identity is invalid for ${REPOSITORY}" ;;
  esac
}

load_workflow_identity() {
  if ! WORKFLOW_ID="$(gh_api "repos/${REPOSITORY}/actions/workflows/${WORKFLOW_FILE}" --jq 'if ((.id | type) == "number" and .id > 0 and .name == "Build JCEF" and .path == ".github/workflows/build-jcef.yml" and .state == "active") then (.id | tostring) else "invalid" end')"; then
    die "Unable to inspect workflow ${WORKFLOW_PATH}"
  fi
  case "$WORKFLOW_ID" in
    ''|0|invalid|*[!0-9]*) die "Workflow identity is invalid for ${WORKFLOW_PATH}" ;;
  esac
}

load_run_identity() {
  local run_status
  local run_extra
  if ! run_status="$(gh_api "repos/${REPOSITORY}/actions/runs/${RUN_ID}" --jq "if ((.id | type) == \"number\" and .id == ${RUN_ID} and .name == \"${WORKFLOW_NAME}\" and .path == \"${WORKFLOW_PATH}\" and .workflow_id == ${WORKFLOW_ID} and .status == \"completed\" and .conclusion == \"success\" and .head_branch == \"master\" and (.event == \"push\" or .event == \"workflow_dispatch\") and .repository.id == ${REPOSITORY_ID} and .repository.full_name == \"${REPOSITORY}\" and .head_repository.id == ${REPOSITORY_ID} and .head_repository.full_name == \"${REPOSITORY}\" and (.run_attempt | type) == \"number\" and .run_attempt > 0 and (.head_sha | type) == \"string\") then [.head_sha, (.run_attempt | tostring)] | join(\"|\") else \"invalid\" end")"; then
    die "Unable to inspect workflow run ${RUN_ID}"
  fi
  IFS='|' read -r RUN_SHA RUN_ATTEMPT run_extra <<< "$run_status"
  if [ -n "$run_extra" ] || [[ ! "$RUN_SHA" =~ ^[0-9a-f]{40}$ ]]; then
    die "Workflow run ${RUN_ID} is not the exact completed successful master build"
  fi
  case "$RUN_ATTEMPT" in
    ''|0|*[!0-9]*) die "Workflow run ${RUN_ID} has an invalid attempt" ;;
  esac
  local head_sha
  head_sha="$(git_command -C "$REPOSITORY_ROOT" rev-parse --verify 'HEAD^{commit}')" || die 'Unable to resolve HEAD'
  if [ "$RUN_SHA" != "$head_sha" ]; then
    die "Workflow run ${RUN_ID} targets ${RUN_SHA}, not current HEAD ${head_sha}"
  fi
}

revalidate_run_identity() {
  local validated_sha="$RUN_SHA"
  local validated_attempt="$RUN_ATTEMPT"
  load_run_identity
  if [ "$RUN_SHA" != "$validated_sha" ] || [ "$RUN_ATTEMPT" != "$validated_attempt" ]; then
    die "Workflow run ${RUN_ID} changed while artifacts were being downloaded"
  fi
}

preflight_publication_access() {
  local immutable_status
  if ! immutable_status="$(gh_api "repos/${REPOSITORY}/immutable-releases" --jq '[(.enabled | type), (.enabled | tostring)] | join("|")')"; then
    die "Unable to inspect immutable-release configuration for ${REPOSITORY}"
  fi
  if [ "$immutable_status" != 'boolean|true' ]; then
    die "Immutable releases must be enabled for ${REPOSITORY}; received ${immutable_status:-no valid status}"
  fi
  if ! AUTHENTICATED_LOGIN="$(gh_api user --jq 'if ((.login | type) == "string") then .login else "" end')"; then
    die 'Unable to determine the authenticated GitHub login'
  fi
  if [[ ! "$AUTHENTICATED_LOGIN" =~ ^[A-Za-z0-9][A-Za-z0-9-]*(\[bot\])?$ ]]; then
    die 'Authenticated GitHub login is missing or malformed'
  fi
}

load_and_validate_jobs() {
  local jobs_output
  local kind
  local job_id
  local job_name
  local job_status
  local job_conclusion
  local job_attempt
  local job_run_id
  local job_head_sha
  local job_head_branch
  local job_workflow_name
  local extra
  local meta_seen=false
  if ! jobs_output="$(gh_api "repos/${REPOSITORY}/actions/runs/${RUN_ID}/attempts/${RUN_ATTEMPT}/jobs?per_page=100" --jq '(if ((.total_count | type) == "number" and (.jobs | type) == "array" and .total_count == (.jobs | length)) then "meta|" + (.total_count | tostring) else "invalid" end), (.jobs[] | if ((.id | type) == "number" and .id > 0 and (.name | type) == "string" and (.status | type) == "string" and (.conclusion | type) == "string" and (.run_attempt | type) == "number" and (.run_id | type) == "number" and (.head_sha | type) == "string" and (.head_branch | type) == "string" and (.workflow_name | type) == "string") then ["job", (.id | tostring), .name, .status, .conclusion, (.run_attempt | tostring), (.run_id | tostring), .head_sha, .head_branch, .workflow_name] | join("|") else "invalid" end)' )"; then
    die "Unable to inspect jobs for workflow run ${RUN_ID}"
  fi
  while IFS='|' read -r kind job_id job_name job_status job_conclusion job_attempt job_run_id job_head_sha job_head_branch job_workflow_name extra; do
    if [ "$kind" = meta ]; then
      if [ "$meta_seen" != false ] || [ "$job_id" != "$EXPECTED_JOB_COUNT" ] || [ -n "$job_name" ] || [ -n "$job_status" ] || [ -n "$job_conclusion" ] || [ -n "$job_attempt" ] || [ -n "$job_run_id" ] || [ -n "$job_head_sha" ] || [ -n "$job_head_branch" ] || [ -n "$job_workflow_name" ] || [ -n "$extra" ]; then
        die "Workflow run ${RUN_ID} must contain exactly ${EXPECTED_JOB_COUNT} jobs"
      fi
      meta_seen=true
      continue
    fi
    if [ "$kind" != job ] || [ "$job_status" != completed ] || [ "$job_conclusion" != success ] || [ "$job_attempt" != "$RUN_ATTEMPT" ] || [ "$job_run_id" != "$RUN_ID" ] || [ "$job_head_sha" != "$RUN_SHA" ] || [ "$job_head_branch" != master ] || [ "$job_workflow_name" != "$WORKFLOW_NAME" ] || [ -n "$extra" ]; then
      die "Workflow run ${RUN_ID} contains an incomplete or unsuccessful job"
    fi
    case "$job_id" in
      ''|0|*[!0-9]*) die "Workflow run ${RUN_ID} contains an invalid job identifier" ;;
    esac
    if ! job_name_is_expected "$job_name"; then
      die "Workflow run ${RUN_ID} contains an unexpected or duplicate job: ${job_name:-unknown}"
    fi
    if [ "${#JOB_NAMES[@]}" -gt 0 ] && { ! require_unique_value "$job_name" "${JOB_NAMES[@]}" || ! require_unique_value "$job_id" "${JOB_IDS[@]}"; }; then
      die "Workflow run ${RUN_ID} contains an unexpected or duplicate job: ${job_name:-unknown}"
    fi
    JOB_NAMES+=("$job_name")
    JOB_IDS+=("$job_id")
  done <<< "$jobs_output"
  if [ "$meta_seen" != true ] || [ "${#JOB_NAMES[@]}" -ne "${#EXPECTED_JOBS[@]}" ]; then
    die "Workflow run ${RUN_ID} does not contain the exact ${EXPECTED_JOB_COUNT} successful build jobs"
  fi
}

load_and_validate_artifacts() {
  local artifacts_output
  local kind
  local artifact_id
  local artifact_name
  local artifact_size
  local artifact_digest
  local artifact_expired
  local workflow_run_id
  local repository_id
  local head_repository_id
  local head_branch
  local head_sha
  local extra
  local meta_seen=false
  # GitHub's run-artifacts endpoint has no attempt selector and its artifact
  # workflow_run object exposes no run_attempt. Exact cardinality, canonical
  # unique names, and current run/SHA provenance therefore form the safe
  # boundary: if GitHub retains artifacts from an earlier attempt, publication
  # intentionally fails closed instead of guessing which set is current.
  if ! artifacts_output="$(gh_api "repos/${REPOSITORY}/actions/runs/${RUN_ID}/artifacts?per_page=100" --jq '(if ((.total_count | type) == "number" and (.artifacts | type) == "array" and .total_count == (.artifacts | length)) then "meta|" + (.total_count | tostring) else "invalid" end), (.artifacts[] | if ((.id | type) == "number" and .id > 0 and (.name | type) == "string" and (.size_in_bytes | type) == "number" and (.digest | type) == "string" and (.expired | type) == "boolean" and (.workflow_run.id | type) == "number" and (.workflow_run.repository_id | type) == "number" and (.workflow_run.head_repository_id | type) == "number" and (.workflow_run.head_branch | type) == "string" and (.workflow_run.head_sha | type) == "string") then ["artifact", (.id | tostring), .name, (.size_in_bytes | tostring), .digest, (.expired | tostring), (.workflow_run.id | tostring), (.workflow_run.repository_id | tostring), (.workflow_run.head_repository_id | tostring), .workflow_run.head_branch, .workflow_run.head_sha] | join("|") else "invalid" end)' )"; then
    die "Unable to inspect artifacts for workflow run ${RUN_ID}"
  fi
  while IFS='|' read -r kind artifact_id artifact_name artifact_size artifact_digest artifact_expired workflow_run_id repository_id head_repository_id head_branch head_sha extra; do
    if [ "$kind" = meta ]; then
      if [ "$meta_seen" != false ] || [ "$artifact_id" != "$EXPECTED_ARTIFACT_COUNT" ] || [ -n "$artifact_name" ] || [ -n "$artifact_size" ] || [ -n "$artifact_digest" ] || [ -n "$artifact_expired" ] || [ -n "$workflow_run_id" ] || [ -n "$repository_id" ] || [ -n "$head_repository_id" ] || [ -n "$head_branch" ] || [ -n "$head_sha" ] || [ -n "$extra" ]; then
        die "Workflow run ${RUN_ID} must contain exactly ${EXPECTED_ARTIFACT_COUNT} artifacts"
      fi
      meta_seen=true
      continue
    fi
    case "$artifact_id" in
      ''|0|*[!0-9]*) die "Workflow run ${RUN_ID} contains an invalid artifact identifier" ;;
    esac
    case "$artifact_size" in
      ''|0|*[!0-9]*) die "Workflow run ${RUN_ID} contains an invalid artifact size" ;;
    esac
    if [ "$kind" != artifact ] || ! artifact_name_is_expected "$artifact_name" || [[ ! "$artifact_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || [ "$artifact_expired" != false ] || [ "$workflow_run_id" != "$RUN_ID" ] || [ "$repository_id" != "$REPOSITORY_ID" ] || [ "$head_repository_id" != "$REPOSITORY_ID" ] || [ "$head_branch" != master ] || [ "$head_sha" != "$RUN_SHA" ] || [ -n "$extra" ]; then
      die "Workflow run ${RUN_ID} contains an invalid or mismatched artifact: ${artifact_name:-unknown}"
    fi
    if [ "${#ARTIFACT_NAMES[@]}" -gt 0 ] && { ! require_unique_value "$artifact_name" "${ARTIFACT_NAMES[@]}" || ! require_unique_value "$artifact_id" "${ARTIFACT_IDS[@]}"; }; then
      die "Workflow run ${RUN_ID} contains a duplicate artifact name or identifier"
    fi
    ARTIFACT_IDS+=("$artifact_id")
    ARTIFACT_NAMES+=("$artifact_name")
    ARTIFACT_SIZES+=("$artifact_size")
    ARTIFACT_DIGESTS+=("${artifact_digest#sha256:}")
  done <<< "$artifacts_output"
  if [ "$meta_seen" != true ] || [ "${#ARTIFACT_NAMES[@]}" -ne "$EXPECTED_ARTIFACT_COUNT" ]; then
    die "Workflow run ${RUN_ID} does not contain the exact ${EXPECTED_ARTIFACT_COUNT} canonical artifacts"
  fi
}

download_and_validate_artifacts() {
  local index
  local artifact_id
  local artifact_name
  local expected_size
  local expected_digest
  local partial_path
  local final_path
  local actual_size
  local actual_digest
  if [ -z "$ARTIFACT_DIRECTORY" ] || [ ! -d "$ARTIFACT_DIRECTORY" ]; then
    die 'Private artifact directory is unavailable'
  fi
  for ((index = 0; index < ${#ARTIFACT_IDS[@]}; index++)); do
    artifact_id="${ARTIFACT_IDS[$index]}"
    artifact_name="${ARTIFACT_NAMES[$index]}"
    expected_size="${ARTIFACT_SIZES[$index]}"
    expected_digest="${ARTIFACT_DIGESTS[$index]}"
    partial_path="${ARTIFACT_DIRECTORY}/.${artifact_id}.partial"
    final_path="${ARTIFACT_DIRECTORY}/${artifact_name}"
    if ! gh_api "repos/${REPOSITORY}/actions/artifacts/${artifact_id}/zip" > "$partial_path"; then
      die "Unable to download artifact ${artifact_name} by ID ${artifact_id}"
    fi
    if ! actual_size="$(file_size "$partial_path")" || ! actual_digest="$(hash_file "$partial_path")"; then
      die "Unable to validate downloaded artifact ${artifact_name}"
    fi
    if [ "$actual_size" != "$expected_size" ] || [ "$actual_digest" != "$expected_digest" ]; then
      die "Downloaded artifact ${artifact_name} does not match its GitHub size and SHA-256 metadata"
    fi
    mv -- "$partial_path" "$final_path"
  done
}

trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

if [ "$#" -ne 1 ]; then
  die 'Usage: publish_workflow_run.sh <positive-numeric-workflow-run-id>'
fi
RUN_ID="$1"
if [[ ! "$RUN_ID" =~ ^[1-9][0-9]*$ ]]; then
  die 'Workflow run ID must be a positive decimal integer'
fi
if [ -n "$ENV_TOKEN_SOURCE" ] && { [ -z "$ENV_TOKEN_CONTENT" ] || [[ ! "$ENV_TOKEN_CONTENT" =~ [^[:space:]] ]]; }; then
  die "${ENV_TOKEN_SOURCE} must contain a non-whitespace token when set"
fi

prepare_sanitized_environment
GIT_PATH="$(resolve_executable git || true)"
GH_PATH="$(resolve_executable gh || true)"
WC_PATH="$(resolve_executable wc || true)"
TR_PATH="$(resolve_executable tr || true)"
CMP_PATH="$(resolve_executable cmp || true)"
MKDIR_PATH="$(resolve_executable mkdir || true)"
CHMOD_PATH="$(resolve_executable chmod || true)"
HEAD_PATH="$(resolve_executable head || true)"
if [ -z "$GIT_PATH" ] || [ -z "$GH_PATH" ] || [ -z "$WC_PATH" ] || [ -z "$TR_PATH" ] || [ -z "$CMP_PATH" ] || [ -z "$MKDIR_PATH" ] || [ -z "$CHMOD_PATH" ] || [ -z "$HEAD_PATH" ]; then
  die 'git, gh, chmod, cmp, head, mkdir, wc and tr are required for workflow-run publication'
fi
if HASH_PATH="$(resolve_executable sha256sum)"; then
  HASH_KIND=sha256sum
elif HASH_PATH="$(resolve_executable shasum)"; then
  HASH_KIND=shasum
else
  die 'sha256sum or shasum is required for artifact validation'
fi

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "$0")" && pwd -P)"
REPOSITORY_ROOT="$(git_command -C "$SCRIPT_DIRECTORY/../.." rev-parse --show-toplevel)" || die 'Unable to resolve the repository root'
EXPECTED_ROOT="$(cd -- "$SCRIPT_DIRECTORY/../.." && pwd -P)"
if [ "$REPOSITORY_ROOT" != "$EXPECTED_ROOT" ]; then
  die 'Publication wrapper must run from its tracked repository location'
fi

# Trust boundary: the local caller chooses the wrapper bytes that /bin/bash
# begins interpreting, so a running script cannot retroactively authenticate
# its own startup. Before exposing credentials we verify the checked-out
# wrapper and publication helpers against HEAD and create a private publication
# directory. The source snapshot, publisher, and verification helpers are
# materialized from validated HEAD only at the final delegation boundary so no
# mutable worktree file can redefine release verification.
refresh_and_validate_master
prepare_private_directory
load_repository_identity
load_workflow_identity
load_run_identity
preflight_publication_access
load_and_validate_jobs
load_and_validate_artifacts
download_and_validate_artifacts

# Artifact downloads may be long-running. Repeat the fetch and source checks
# before delegation, then materialize the HEAD-derived source snapshot and
# publication helpers immediately before invocation. Later worktree replacement
# cannot change the private copies.
refresh_and_validate_master
revalidate_run_identity
prepare_source_snapshot
prepare_trusted_publisher
echo "Publishing validated workflow run ${RUN_ID} for ${RUN_SHA} as the authenticated GitHub user"
invoke_trusted_publisher
