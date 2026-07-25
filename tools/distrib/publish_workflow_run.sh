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
# credential-bearing children also receive no raw BASH_FUNC_* entries.
unset BASH_ENV ENV CDPATH GLOBIGNORE

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
readonly -a EXPECTED_JOBS=(
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

GIT_PATH=''
GH_PATH=''
HASH_PATH=''
HASH_KIND=''
WC_PATH=''
TR_PATH=''
CMP_PATH=''
MKDIR_PATH=''
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
PUBLISHER_COPY=''

JOB_NAMES=()
JOB_IDS=()
ARTIFACT_IDS=()
ARTIFACT_NAMES=()
ARTIFACT_SIZES=()
ARTIFACT_DIGESTS=()
SANITIZED_ENV_ARGS=(-u BASH_ENV -u ENV -u SHELLOPTS -u BASHOPTS -u CDPATH -u GLOBIGNORE -u BASH_XTRACEFD -u PS4 -u ENV_TOKEN_SOURCE -u ENV_TOKEN_CONTENT -u GH_ENTERPRISE_TOKEN -u GITHUB_ENTERPRISE_TOKEN -u GH_DEBUG -u GH_FORCE_TTY -u GH_PAGER -u PAGER -u GH_REPO)

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
    esac
  done <<< "$environment_output"
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
  for relative_path in "$WRAPPER_PATH" "$PUBLISHER_PATH"; do
    if [ ! -f "$REPOSITORY_ROOT/$relative_path" ] || [ -L "$REPOSITORY_ROOT/$relative_path" ] || [ ! -x "$REPOSITORY_ROOT/$relative_path" ]; then
      die "Publication script must be an executable regular file: ${relative_path}"
    fi
    if ! "$GIT_PATH" -C "$REPOSITORY_ROOT" ls-files --error-unmatch -- "$relative_path" >/dev/null 2>&1; then
      die "Publication script is not tracked by HEAD: ${relative_path}"
    fi
    if ! "$GIT_PATH" -C "$REPOSITORY_ROOT" diff --quiet HEAD -- "$relative_path"; then
      die "Publication script does not match HEAD: ${relative_path}"
    fi
    # Compare the actual bytes as well because Git's working-tree diff honors
    # assume-unchanged and skip-worktree hints that must not bypass publication.
    if ! "$GIT_PATH" -C "$REPOSITORY_ROOT" show "HEAD:${relative_path}" | "$CMP_PATH" -s - "$REPOSITORY_ROOT/$relative_path"; then
      die "Publication script bytes do not match HEAD: ${relative_path}"
    fi
  done
}

prepare_private_directory() {
  PRIVATE_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/jcef-publish.XXXXXX")" || die 'Unable to create a private publication directory'
  chmod 700 "$PRIVATE_ROOT"
  # Keep the trusted publisher outside this child directory. The publisher
  # enables dotglob and intentionally rejects anything beyond 12 artifacts.
  ARTIFACT_DIRECTORY="${PRIVATE_ROOT}/artifacts"
  if ! "$MKDIR_PATH" -m 700 -- "$ARTIFACT_DIRECTORY"; then
    die 'Unable to create the private artifact directory'
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
  if [[ ! "$VALIDATED_HEAD_SHA" =~ ^[0-9a-f]{40}$ ]]; then
    die 'Validated HEAD is unavailable for the trusted publisher copy'
  fi
  if ! "$GIT_PATH" -C "$REPOSITORY_ROOT" show "${VALIDATED_HEAD_SHA}:${PUBLISHER_PATH}" > "$PUBLISHER_COPY"; then
    die 'Unable to copy the publisher from HEAD'
  fi
  if [ ! -f "$PUBLISHER_COPY" ] || [ -L "$PUBLISHER_COPY" ]; then
    die 'The trusted publisher copy is not a regular file'
  fi
  if ! "$GIT_PATH" -C "$REPOSITORY_ROOT" show "${VALIDATED_HEAD_SHA}:${PUBLISHER_PATH}" | "$CMP_PATH" -s - "$PUBLISHER_COPY"; then
    die 'The trusted publisher copy does not byte-match HEAD'
  fi
  chmod 400 "$PUBLISHER_COPY"
}

invoke_trusted_publisher() {
  if [ -z "$PUBLISHER_COPY" ] || [ ! -f "$PUBLISHER_COPY" ] || [ -L "$PUBLISHER_COPY" ] || [ ! -r "$PUBLISHER_COPY" ]; then
    die 'Trusted publisher copy is unavailable'
  fi
  # Give Bash the private path so it owns and protects its parser input. An
  # inherited descriptor would remain shared with publisher descendants, which
  # could advance the shared open-file offset while Bash is still parsing.
  if [ -n "$ENV_TOKEN_SOURCE" ]; then
    GH_TOKEN="$ENV_TOKEN_CONTENT" GH_HOST=github.com GH_PROMPT_DISABLED=1 GH_NO_UPDATE_NOTIFIER=1 "$SYSTEM_ENV_PATH" "${SANITIZED_ENV_ARGS[@]}" /bin/bash -p "$PUBLISHER_COPY" "$RUN_SHA" "$ARTIFACT_DIRECTORY"
  else
    GH_HOST=github.com GH_PROMPT_DISABLED=1 GH_NO_UPDATE_NOTIFIER=1 "$SYSTEM_ENV_PATH" "${SANITIZED_ENV_ARGS[@]}" /bin/bash -p "$PUBLISHER_COPY" "$RUN_SHA" "$ARTIFACT_DIRECTORY"
  fi
}

refresh_and_validate_master() {
  local origin_url
  local head_sha
  local origin_master_sha
  origin_url="$("$GIT_PATH" -C "$REPOSITORY_ROOT" remote get-url origin)" || die 'Unable to inspect the origin remote'
  if [ "$origin_url" != "$REPOSITORY_URL" ]; then
    die "origin must be ${REPOSITORY_URL}; found ${origin_url:-no URL}"
  fi
  require_pristine_publication_scripts
  if ! "$GIT_PATH" -C "$REPOSITORY_ROOT" fetch --quiet --no-tags origin '+refs/heads/master:refs/remotes/origin/master'; then
    die 'Unable to fetch origin/master'
  fi
  head_sha="$("$GIT_PATH" -C "$REPOSITORY_ROOT" rev-parse --verify 'HEAD^{commit}')" || die 'Unable to resolve HEAD'
  origin_master_sha="$("$GIT_PATH" -C "$REPOSITORY_ROOT" rev-parse --verify 'refs/remotes/origin/master^{commit}')" || die 'Unable to resolve freshly fetched origin/master'
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
  head_sha="$("$GIT_PATH" -C "$REPOSITORY_ROOT" rev-parse --verify 'HEAD^{commit}')" || die 'Unable to resolve HEAD'
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
      if [ "$meta_seen" != false ] || [ "$job_id" != "${#EXPECTED_JOBS[@]}" ] || [ -n "$job_name" ] || [ -n "$job_status" ] || [ -n "$job_conclusion" ] || [ -n "$job_attempt" ] || [ -n "$job_run_id" ] || [ -n "$job_head_sha" ] || [ -n "$job_head_branch" ] || [ -n "$job_workflow_name" ] || [ -n "$extra" ]; then
        die "Workflow run ${RUN_ID} must contain exactly six jobs"
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
    die "Workflow run ${RUN_ID} does not contain the exact six successful platform jobs"
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
      if [ "$meta_seen" != false ] || [ "$artifact_id" != 12 ] || [ -n "$artifact_name" ] || [ -n "$artifact_size" ] || [ -n "$artifact_digest" ] || [ -n "$artifact_expired" ] || [ -n "$workflow_run_id" ] || [ -n "$repository_id" ] || [ -n "$head_repository_id" ] || [ -n "$head_branch" ] || [ -n "$head_sha" ] || [ -n "$extra" ]; then
        die "Workflow run ${RUN_ID} must contain exactly 12 artifacts"
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
  if [ "$meta_seen" != true ] || [ "${#ARTIFACT_NAMES[@]}" -ne 12 ]; then
    die "Workflow run ${RUN_ID} does not contain the exact 12 canonical artifacts"
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
if [ -z "$GIT_PATH" ] || [ -z "$GH_PATH" ] || [ -z "$WC_PATH" ] || [ -z "$TR_PATH" ] || [ -z "$CMP_PATH" ] || [ -z "$MKDIR_PATH" ]; then
  die 'git, gh, cmp, mkdir, wc and tr are required for workflow-run publication'
fi
if HASH_PATH="$(resolve_executable sha256sum)"; then
  HASH_KIND=sha256sum
elif HASH_PATH="$(resolve_executable shasum)"; then
  HASH_KIND=shasum
else
  die 'sha256sum or shasum is required for artifact validation'
fi

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "$0")" && pwd -P)"
REPOSITORY_ROOT="$("$GIT_PATH" -C "$SCRIPT_DIRECTORY/../.." rev-parse --show-toplevel)" || die 'Unable to resolve the repository root'
EXPECTED_ROOT="$(cd -- "$SCRIPT_DIRECTORY/../.." && pwd -P)"
if [ "$REPOSITORY_ROOT" != "$EXPECTED_ROOT" ]; then
  die 'Publication wrapper must run from its tracked repository location'
fi

# Trust boundary: the local caller chooses the wrapper bytes that /bin/bash
# begins interpreting, so a running script cannot retroactively authenticate
# its own startup. Before exposing credentials we verify the checked-out
# wrapper and publisher against HEAD and create a private artifact directory.
# The publisher is copied from HEAD only at the final delegation boundary so
# no intermediary child can replace it before validation.
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
# before delegation, then copy the HEAD-derived publisher immediately before
# invocation. Later worktree replacement cannot change the private copy.
refresh_and_validate_master
revalidate_run_identity
prepare_trusted_publisher
echo "Publishing validated workflow run ${RUN_ID} for ${RUN_SHA} as the authenticated GitHub user"
invoke_trusted_publisher
