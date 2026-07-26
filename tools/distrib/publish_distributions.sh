#!/bin/bash -p
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

set -euo pipefail
set +x

if [[ $- != *p* ]]; then
  echo 'ERROR: execute publish_distributions.sh directly so its privileged Bash startup is preserved' >&2
  exit 1
fi
# Privileged startup ignores caller-controlled startup files and imported
# functions. Named startup/search variables are removed now, and raw exported
# function entries are stripped from every credential-bearing child.
unset BASH_ENV ENV CDPATH GLOBIGNORE

readonly REPOSITORY_OWNER='Keksuccino'
readonly REPOSITORY_NAME='jcef-mcef'
readonly REPOSITORY="${REPOSITORY_OWNER}/${REPOSITORY_NAME}"
readonly RELEASE_MARKER='managed-by=tools/distrib/publish_distributions.sh;schema=1'
readonly SYSTEM_ENV_PATH='/usr/bin/env'
# Release assets use a different GitHub origin. A relative `gh api` endpoint
# targets api.github.com, while `--hostname uploads.github.com` would construct
# the invalid api.uploads.github.com host. Keep this absolute URL: gh still
# applies GitHub.com authentication through its subdomain normalization.
readonly RELEASE_UPLOAD_BASE_URL="https://uploads.github.com/repos/${REPOSITORY}"
readonly LATEST_RELEASE_QUERY="query(\$owner:String!,\$name:String!){repository(owner:\$owner,name:\$name){latestRelease{tagName}}}"
readonly RELEASE_VISIBILITY_MAX_ATTEMPTS=6
readonly -a TARGETS=(
  linux_amd64
  linux_arm64
  macos_amd64
  macos_arm64
  windows_amd64
  windows_arm64
)

# Keep credentials out of every local validation subprocess. An explicitly
# supplied token is captured and exposed only to the resolved gh executable
# after all local artifacts validate. With no token environment variable, gh
# uses the maintainer's authenticated credential store instead.
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

GH_PATH=''
HASH_PATH=''
HASH_COMMAND=''
CMP_PATH=''
WC_PATH=''
TR_PATH=''
PYTHON_PATH=''
SLEEP_PATH=''
SCRIPT_DIRECTORY=''
VERIFIER_PATH=''
COMMIT_SHA=''
ARTIFACT_DIRECTORY=''
TAG_NAME=''
RELEASE_TITLE=''
RELEASE_BODY=''
RELEASE_IDS=''
DRAFT_RELEASE_ID=''
TAG_REFS=''
RELEASE_ASSETS=''
METADATA_TAG=''
METADATA_ID=''
METADATA_TARGET=''
METADATA_DRAFT=''
METADATA_IMMUTABLE=''
METADATA_PRERELEASE=''
METADATA_TITLE=''
METADATA_BODY=''
METADATA_AUTHOR=''
RELEASE_AUTHOR=''
TAG_CREATED=false
RELEASE_ABSENCE_RECONCILED=false

ASSET_NAMES=()
ASSET_SIZES=()
ASSET_DIGESTS=()
ARCHIVE_PATHS=()
CHECKSUM_PATHS=()
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

gh_command() {
  if [ -n "$ENV_TOKEN_SOURCE" ]; then
    GH_TOKEN="$ENV_TOKEN_CONTENT" GH_HOST=github.com GH_PROMPT_DISABLED=1 GH_NO_UPDATE_NOTIFIER=1 "$SYSTEM_ENV_PATH" "${SANITIZED_ENV_ARGS[@]}" "$GH_PATH" "$@"
  else
    GH_HOST=github.com GH_PROMPT_DISABLED=1 GH_NO_UPDATE_NOTIFIER=1 "$SYSTEM_ENV_PATH" "${SANITIZED_ENV_ARGS[@]}" "$GH_PATH" "$@"
  fi
}

preserve_mutation_interruption_status() {
  local status="$1"
  case "$status" in
    129|130|143) exit "$status" ;;
  esac
}

hash_file() {
  local path="$1"
  local output
  local digest
  if [ "$HASH_COMMAND" = 'sha256sum' ]; then
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

append_asset() {
  ASSET_NAMES+=("$1")
  ASSET_SIZES+=("$2")
  ASSET_DIGESTS+=("$3")
}

asset_name_is_expected() {
  local actual_name="$1"
  local expected_name
  for expected_name in "${ASSET_NAMES[@]}"; do
    if [ "$actual_name" = "$expected_name" ]; then
      return 0
    fi
  done
  return 1
}

require_immutable_releases() {
  local immutable_status
  if ! immutable_status="$(gh_command api "repos/${REPOSITORY}/immutable-releases" --jq '[(.enabled | type), (.enabled | tostring)] | join("|")')"; then
    die "Unable to inspect immutable-release configuration for ${REPOSITORY}"
  fi
  if [ "$immutable_status" != 'boolean|true' ]; then
    die "Immutable releases must be enabled for ${REPOSITORY}; received ${immutable_status:-no valid status}"
  fi
}

resolve_release_author() {
  local authenticated_login
  if ! authenticated_login="$(gh_command api user --jq 'if ((.login | type) == "string") then .login else "" end')"; then
    die 'Unable to determine the authenticated GitHub login'
  fi
  if [[ ! "$authenticated_login" =~ ^[A-Za-z0-9][A-Za-z0-9-]*(\[bot\])?$ ]]; then
    die 'Authenticated GitHub login is missing or malformed'
  fi
  RELEASE_AUTHOR="$authenticated_login"
}

ensure_release_is_not_latest() {
  local latest_status
  local latest_tag
  if ! latest_status="$(gh_command api graphql -f "query=${LATEST_RELEASE_QUERY}" -F "owner=${REPOSITORY_OWNER}" -F "name=${REPOSITORY_NAME}" --jq 'if (.errors != null or (.data.repository | type) != "object" or (.data.repository | has("latestRelease") | not)) then "invalid" elif .data.repository.latestRelease == null then "null" elif ((.data.repository.latestRelease | type) == "object" and (.data.repository.latestRelease.tagName | type) == "string") then "tag|" + .data.repository.latestRelease.tagName else "invalid" end')"; then
    die "Unable to inspect the latest release for ${REPOSITORY}"
  fi
  if [ "$latest_status" = null ]; then
    return 0
  fi
  case "$latest_status" in
    tag\|?*) latest_tag="${latest_status#tag|}" ;;
    *) die "Latest-release query returned malformed state for ${REPOSITORY}: ${latest_status:-no valid state}" ;;
  esac
  if [ "$latest_tag" = "$TAG_NAME" ]; then
    die "Release ${TAG_NAME} is unexpectedly marked as latest"
  fi
}

try_query_release_ids() {
  local release_ids
  # A successful, paginated list query is required to prove absence. A failed
  # tag lookup must never be mistaken for permission or network-safe absence.
  if ! release_ids="$(gh_command api --paginate "repos/${REPOSITORY}/releases?per_page=100" --jq "def valid_release: (type == \"object\") and ((.tag_name | type) == \"string\") and ((.id | type) == \"number\") and (.id > 0) and (.id == (.id | floor)); if ((type != \"array\") or any(.[]; (valid_release | not))) then \"invalid\" else .[] | select(.tag_name == \"${TAG_NAME}\") | (.id | tostring) end")"; then
    return 1
  fi
  if [[ "$release_ids" == *$'\n'* ]]; then
    die "Multiple releases unexpectedly use tag ${TAG_NAME}"
  fi
  if [ -n "$release_ids" ]; then
    case "$release_ids" in
      0|*[!0-9]*) die "Release query returned an invalid identifier for ${TAG_NAME}" ;;
    esac
  fi
  RELEASE_IDS="$release_ids"
}

query_release_ids() {
  if ! try_query_release_ids; then
    die "Unable to inspect releases for ${TAG_NAME}"
  fi
}

try_refresh_release_metadata() {
  local release_id="$1"
  local metadata
  local metadata_extra
  METADATA_ID=''
  METADATA_TAG=''
  METADATA_TARGET=''
  METADATA_DRAFT=''
  METADATA_IMMUTABLE=''
  METADATA_PRERELEASE=''
  METADATA_TITLE=''
  METADATA_BODY=''
  METADATA_AUTHOR=''
  # The single-quoted program is evaluated by jq; $strings is not a shell variable.
  # shellcheck disable=SC2016
  if ! metadata="$(gh_command api "repos/${REPOSITORY}/releases/${release_id}" --jq 'if ((.id | type) == "number" and .id > 0 and (.tag_name | type) == "string" and (.target_commitish | type) == "string" and (.draft | type) == "boolean" and (.immutable | type) == "boolean" and (.prerelease | type) == "boolean" and (.name | type) == "string" and (.body | type) == "string" and (.author.login | type) == "string") then [.tag_name, .target_commitish, .name, .body, .author.login] as $strings | if (($strings | map(select(contains("|") or contains("\r") or contains("\n"))) | length) == 0) then [(.id | tostring), .tag_name, .target_commitish, (.draft | tostring), (.immutable | tostring), (.prerelease | tostring), .name, .body, .author.login] | join("|") else "invalid" end else "invalid" end')"; then
    return 1
  fi
  IFS='|' read -r METADATA_ID METADATA_TAG METADATA_TARGET METADATA_DRAFT METADATA_IMMUTABLE METADATA_PRERELEASE METADATA_TITLE METADATA_BODY METADATA_AUTHOR metadata_extra <<< "$metadata"
  if [ -n "$metadata_extra" ]; then
    die "Release metadata query returned malformed state for ${TAG_NAME}"
  fi
}

refresh_release_metadata() {
  if ! try_refresh_release_metadata "$1"; then
    die "Unable to inspect release metadata for ${TAG_NAME}"
  fi
}

validate_release_identity() {
  local expected_id="$1"
  if [ "$METADATA_ID" != "$expected_id" ]; then
    die "Release identifier mismatch for ${TAG_NAME}"
  fi
  if [ "$METADATA_TAG" != "$TAG_NAME" ]; then
    die "Release tag mismatch for ${TAG_NAME}"
  fi
  if [ "$METADATA_TARGET" != "$COMMIT_SHA" ]; then
    die "Release target mismatch for ${TAG_NAME}"
  fi
  if [ "$METADATA_PRERELEASE" != false ]; then
    die "Release prerelease state mismatch for ${TAG_NAME}"
  fi
  if [ "$METADATA_TITLE" != "$RELEASE_TITLE" ] || [ "$METADATA_BODY" != "$RELEASE_BODY" ]; then
    die "Release ownership marker mismatch for ${TAG_NAME}"
  fi
  if [ "$METADATA_AUTHOR" != "$RELEASE_AUTHOR" ]; then
    die "Release author mismatch for ${TAG_NAME}"
  fi
  if [ "$METADATA_DRAFT" != true ] && [ "$METADATA_DRAFT" != false ]; then
    die "Release draft state is invalid for ${TAG_NAME}"
  fi
  if [ "$METADATA_IMMUTABLE" != true ] && [ "$METADATA_IMMUTABLE" != false ]; then
    die "Release immutable state is invalid for ${TAG_NAME}"
  fi
}

sleep_before_release_retry() {
  local delay_seconds="$1"
  if ! "$SLEEP_PATH" "$delay_seconds"; then
    die "Unable to wait before retrying release inspection for ${TAG_NAME}"
  fi
}

resolve_initial_release_state() {
  local attempt=1
  local delay_seconds=1
  local unresolved_release_id=''
  while [ "$attempt" -le "$RELEASE_VISIBILITY_MAX_ATTEMPTS" ]; do
    query_release_ids
    if [ -n "$RELEASE_IDS" ]; then
      unresolved_release_id="$RELEASE_IDS"
      if try_refresh_release_metadata "$RELEASE_IDS"; then
        return 0
      fi
      # A visible collection entry can precede a readable exact resource after
      # POST, or outlive it after DELETE. Observe both directions without
      # assuming which mutation won.
      release_id_is_absent "$unresolved_release_id" || true
    elif [ -z "$unresolved_release_id" ]; then
      return 0
    elif release_id_is_absent "$unresolved_release_id"; then
      RELEASE_ABSENCE_RECONCILED=true
      return 0
    fi
    if [ "$attempt" -eq "$RELEASE_VISIBILITY_MAX_ATTEMPTS" ]; then
      break
    fi
    sleep_before_release_retry "$delay_seconds"
    delay_seconds=$((delay_seconds * 2))
    attempt=$((attempt + 1))
  done
  die "Release list and exact metadata did not converge for ${TAG_NAME}"
}

wait_for_release_state() {
  local expected_id="$1"
  local expected_draft="$2"
  local expected_immutable="$3"
  local attempt=1
  local delay_seconds=1
  while [ "$attempt" -le "$RELEASE_VISIBILITY_MAX_ATTEMPTS" ]; do
    if try_refresh_release_metadata "$expected_id"; then
      validate_release_identity "$expected_id"
      if [ "$METADATA_DRAFT" = "$expected_draft" ] && [ "$METADATA_IMMUTABLE" = "$expected_immutable" ] && try_query_release_ids && [ "$RELEASE_IDS" = "$expected_id" ]; then
        return 0
      fi
    fi
    if [ "$attempt" -eq "$RELEASE_VISIBILITY_MAX_ATTEMPTS" ]; then
      break
    fi
    sleep_before_release_retry "$delay_seconds"
    delay_seconds=$((delay_seconds * 2))
    attempt=$((attempt + 1))
  done
  return 1
}

release_id_is_absent() {
  local release_id="$1"
  local response_headers
  local status_line
  if response_headers="$(gh_command api --include --silent "repos/${REPOSITORY}/releases/${release_id}" 2>/dev/null)"; then
    return 1
  fi
  status_line="${response_headers%%$'\n'*}"
  case "$status_line" in
    HTTP/*' 404 '*) return 0 ;;
  esac
  return 1
}

wait_for_release_absence() {
  local release_id="$1"
  local failure_message="$2"
  local attempt=1
  local delay_seconds=1
  while [ "$attempt" -le "$RELEASE_VISIBILITY_MAX_ATTEMPTS" ]; do
    if release_id_is_absent "$release_id" && try_query_release_ids && [ -z "$RELEASE_IDS" ]; then
      RELEASE_ABSENCE_RECONCILED=true
      return 0
    fi
    if [ "$attempt" -eq "$RELEASE_VISIBILITY_MAX_ATTEMPTS" ]; then
      break
    fi
    sleep_before_release_retry "$delay_seconds"
    delay_seconds=$((delay_seconds * 2))
    attempt=$((attempt + 1))
  done
  die "${failure_message}: ${TAG_NAME}"
}

require_mutable_draft() {
  if [ "$METADATA_DRAFT" != true ]; then
    die "Release is not a recoverable draft: ${TAG_NAME}"
  fi
  if [ "$METADATA_IMMUTABLE" != false ]; then
    die "Draft release is unexpectedly immutable: ${TAG_NAME}"
  fi
}

require_immutable_published_release() {
  if [ "$METADATA_DRAFT" != false ]; then
    die "Release remained a draft after publication: ${TAG_NAME}"
  fi
  if [ "$METADATA_IMMUTABLE" != true ]; then
    die "Published release is not immutable: ${TAG_NAME}"
  fi
}

query_tag_refs() {
  if ! TAG_REFS="$(gh_command api --paginate "repos/${REPOSITORY}/git/matching-refs/tags/${TAG_NAME}" --jq ".[] | select(.ref == \"refs/tags/${TAG_NAME}\") | .ref")"; then
    die "Unable to inspect tag ${TAG_NAME}"
  fi
  if [[ "$TAG_REFS" == *$'\n'* ]]; then
    die "Multiple exact refs unexpectedly match tag ${TAG_NAME}"
  fi
}

ensure_exact_tag() {
  local allow_create="$1"
  local mutation_status
  local resolved_sha
  query_tag_refs
  if [ -z "$TAG_REFS" ]; then
    if [ "$allow_create" != true ]; then
      die "Required tag does not exist: ${TAG_NAME}"
    fi
    # Create a lightweight tag explicitly so gh can never infer the default
    # branch tip. Existing refs are only validated and are never retargeted.
    if gh_command api --method POST "repos/${REPOSITORY}/git/refs" -f "ref=refs/tags/${TAG_NAME}" -f "sha=${COMMIT_SHA}" >/dev/null; then
      :
    else
      mutation_status=$?
      preserve_mutation_interruption_status "$mutation_status"
      die "Unable to create exact tag ${TAG_NAME}"
    fi
    TAG_CREATED=true
    query_tag_refs
  fi
  if [ "$TAG_REFS" != "refs/tags/${TAG_NAME}" ]; then
    die "Exact tag lookup failed for ${TAG_NAME}"
  fi
  if ! resolved_sha="$(gh_command api "repos/${REPOSITORY}/commits/${TAG_NAME}" --jq '.sha')"; then
    die "Unable to resolve tag ${TAG_NAME}"
  fi
  if [ "$resolved_sha" != "$COMMIT_SHA" ]; then
    die "Tag ${TAG_NAME} resolves to ${resolved_sha:-unknown}, not ${COMMIT_SHA}"
  fi
}

refresh_release_assets() {
  local release_id="$1"
  if ! RELEASE_ASSETS="$(gh_command api "repos/${REPOSITORY}/releases/${release_id}" --jq 'def line_safe_string: (type == "string") and ((contains("|") or contains("\r") or contains("\n")) | not); def valid_asset: (type == "object") and (.name | line_safe_string) and ((.size | type) == "number") and (.size >= 0) and (.size == (.size | floor)) and (.state | line_safe_string) and ((.digest == null) or (.digest | line_safe_string)); if (((.assets | type) != "array") or any(.assets[]; (valid_asset | not))) then "invalid" else .assets[] | [.name, (.size | tostring), .state, (.digest // "")] | join("|") end')"; then
    die "Unable to inspect release assets for ${TAG_NAME}"
  fi
}

release_assets_are_canonical_subset() {
  local name
  local size
  local state
  local digest
  local extra
  while IFS='|' read -r name size state digest extra; do
    if [ -z "$name" ] && [ -z "$size" ] && [ -z "$state" ] && [ -z "$digest" ] && [ -z "$extra" ]; then
      continue
    fi
    if [ -n "$extra" ] || ! asset_name_is_expected "$name"; then
      return 1
    fi
  done <<< "$RELEASE_ASSETS"
  return 0
}

release_assets_match() {
  local -a seen=()
  local name
  local size
  local state
  local digest
  local extra
  local row_count=0
  local match_index
  local index
  for ((index = 0; index < ${#ASSET_NAMES[@]}; index++)); do
    seen[index]=0
  done
  while IFS='|' read -r name size state digest extra; do
    if [ -z "$name" ] && [ -z "$size" ] && [ -z "$state" ] && [ -z "$digest" ] && [ -z "$extra" ]; then
      continue
    fi
    if [ -n "$extra" ]; then
      return 1
    fi
    match_index=-1
    for ((index = 0; index < ${#ASSET_NAMES[@]}; index++)); do
      if [ "$name" = "${ASSET_NAMES[$index]}" ]; then
        match_index=$index
        break
      fi
    done
    if [ "$match_index" -lt 0 ] || [ "${seen[$match_index]}" -ne 0 ]; then
      return 1
    fi
    if [ "$state" != uploaded ] || [ "$size" != "${ASSET_SIZES[$match_index]}" ] || [ "$digest" != "sha256:${ASSET_DIGESTS[$match_index]}" ]; then
      return 1
    fi
    seen[match_index]=1
    row_count=$((row_count + 1))
  done <<< "$RELEASE_ASSETS"
  if [ "$row_count" -ne "${#ASSET_NAMES[@]}" ]; then
    return 1
  fi
  for ((index = 0; index < ${#ASSET_NAMES[@]}; index++)); do
    if [ "${seen[$index]}" -ne 1 ]; then
      return 1
    fi
  done
  return 0
}

upload_release_asset() {
  local asset_name="$1"
  local asset_path="$2"
  local expected_size="$3"
  local expected_digest="$4"
  local upload_metadata
  local upload_status
  if [ -z "$DRAFT_RELEASE_ID" ] || [[ ! "$DRAFT_RELEASE_ID" =~ ^[1-9][0-9]*$ ]]; then
    die 'Validated draft release ID is unavailable for asset upload'
  fi
  if ! asset_name_is_expected "$asset_name" || [ "${asset_path##*/}" != "$asset_name" ]; then
    die "Refusing noncanonical release asset upload: ${asset_name}"
  fi
  if upload_metadata="$(gh_command api --method POST "${RELEASE_UPLOAD_BASE_URL}/releases/${DRAFT_RELEASE_ID}/assets?name=${asset_name}" -H 'Content-Type: application/octet-stream' --input "$asset_path" --jq 'if ((.name | type) == "string" and (.size | type) == "number" and .size > 0 and (.state | type) == "string" and (.digest | type) == "string") then [.name, (.size | tostring), .state, .digest] | join("|") else "invalid" end')"; then
    :
  else
    upload_status=$?
    preserve_mutation_interruption_status "$upload_status"
    return 1
  fi
  if [ "$upload_metadata" != "${asset_name}|${expected_size}|uploaded|sha256:${expected_digest}" ]; then
    return 1
  fi
}

upload_release_assets() {
  local index
  local asset_index
  for ((index = 0; index < ${#ARCHIVE_PATHS[@]}; index++)); do
    asset_index=$((index * 2))
    if ! upload_release_asset "${ASSET_NAMES[$asset_index]}" "${ARCHIVE_PATHS[$index]}" "${ASSET_SIZES[$asset_index]}" "${ASSET_DIGESTS[$asset_index]}"; then
      die "Archive upload failed for draft ${TAG_NAME}"
    fi
  done
  for ((index = 0; index < ${#CHECKSUM_PATHS[@]}; index++)); do
    asset_index=$((index * 2 + 1))
    if ! upload_release_asset "${ASSET_NAMES[$asset_index]}" "${CHECKSUM_PATHS[$index]}" "${ASSET_SIZES[$asset_index]}" "${ASSET_DIGESTS[$asset_index]}"; then
      die "Checksum upload failed for draft ${TAG_NAME}"
    fi
  done
}

reconcile_release_before_creation() {
  local attempt=1
  local delay_seconds=1
  local all_queries_succeeded=true
  local release_observed=false
  while [ "$attempt" -le "$RELEASE_VISIBILITY_MAX_ATTEMPTS" ]; do
    if try_query_release_ids; then
      if [ -n "$RELEASE_IDS" ]; then
        release_observed=true
        if try_refresh_release_metadata "$RELEASE_IDS"; then
          validate_release_identity "$RELEASE_IDS"
          if [ "$METADATA_DRAFT" = true ] && [ "$METADATA_IMMUTABLE" = false ]; then
            refresh_release_assets "$RELEASE_IDS"
            if [ -z "$RELEASE_ASSETS" ]; then
              # The create response can be lost after GitHub commits the draft.
              # An exact, empty, owned draft is the only mutable state safe to
              # adopt without another mutation.
              DRAFT_RELEASE_ID="$RELEASE_IDS"
              ensure_exact_tag false
              return 0
            fi
          elif [ "$METADATA_DRAFT" = false ] && [ "$METADATA_IMMUTABLE" = true ]; then
            # A prior PATCH may also have committed while its response was lost.
            # A matching immutable release makes the rerun idempotently complete.
            refresh_release_assets "$RELEASE_IDS"
            if ! release_assets_match; then
              die "Published release does not exactly match local assets: ${TAG_NAME}"
            fi
            ensure_exact_tag false
            ensure_release_is_not_latest
            echo "GitHub Release ${TAG_NAME} is already published and matches exactly"
            exit 0
          fi
        fi
      fi
    else
      all_queries_succeeded=false
    fi
    if [ "$attempt" -eq "$RELEASE_VISIBILITY_MAX_ATTEMPTS" ]; then
      break
    fi
    sleep_before_release_retry "$delay_seconds"
    delay_seconds=$((delay_seconds * 2))
    attempt=$((attempt + 1))
  done
  if [ "$all_queries_succeeded" = true ] && [ "$release_observed" = false ]; then
    RELEASE_ABSENCE_RECONCILED=true
    return 1
  fi
  return 2
}

create_empty_draft() {
  local created_release_id
  local mutation_status
  local reconciliation_status
  if [ "$TAG_CREATED" = false ] && [ "$RELEASE_ABSENCE_RECONCILED" = false ]; then
    if reconcile_release_before_creation; then
      created_release_id="$DRAFT_RELEASE_ID"
    else
      reconciliation_status=$?
      if [ "$reconciliation_status" -ne 1 ]; then
        die "Unable to reconcile release state before creation: ${TAG_NAME}"
      fi
    fi
  fi
  if [ -z "$created_release_id" ]; then
    if created_release_id="$(gh_command api --method POST "repos/${REPOSITORY}/releases" -f "tag_name=${TAG_NAME}" -f "target_commitish=${COMMIT_SHA}" -f "name=${RELEASE_TITLE}" -f "body=${RELEASE_BODY}" -F draft=true -F prerelease=false -f make_latest=false --jq 'if ((.id | type) == "number" and .id > 0) then (.id | tostring) else "invalid" end')"; then
      :
    else
      mutation_status=$?
      preserve_mutation_interruption_status "$mutation_status"
      if reconcile_release_before_creation; then
        created_release_id="$DRAFT_RELEASE_ID"
      else
        die "Unable to create draft release ${TAG_NAME}"
      fi
    fi
  fi
  case "$created_release_id" in
    ''|0|invalid|*[!0-9]*) die "Draft creation returned an invalid release identifier for ${TAG_NAME}" ;;
  esac
  DRAFT_RELEASE_ID="$created_release_id"
  if ! wait_for_release_state "$DRAFT_RELEASE_ID" true false; then
    if [ "$METADATA_ID" = "$DRAFT_RELEASE_ID" ]; then
      require_mutable_draft
    fi
    die "Draft release was not visible after creation: ${TAG_NAME}"
  fi
  require_mutable_draft
  ensure_exact_tag false
  refresh_release_assets "$DRAFT_RELEASE_ID"
  if [ -n "$RELEASE_ASSETS" ]; then
    die "New draft release unexpectedly contains assets: ${TAG_NAME}"
  fi
}

delete_incomplete_owned_draft() {
  local mutation_status
  # The earlier recovery inspection is not deletion authority. Resolve the
  # collection again, then revalidate ownership, mutability, tag and assets by
  # the same stable ID immediately before deleting exactly that ID.
  require_immutable_releases
  resolve_release_author
  query_release_ids
  if [ -z "$DRAFT_RELEASE_ID" ] || [ "$RELEASE_IDS" != "$DRAFT_RELEASE_ID" ]; then
    die "Draft release identity changed before deletion: ${TAG_NAME}"
  fi
  refresh_release_metadata "$DRAFT_RELEASE_ID"
  validate_release_identity "$DRAFT_RELEASE_ID"
  require_mutable_draft
  ensure_exact_tag false
  refresh_release_assets "$DRAFT_RELEASE_ID"
  if ! release_assets_are_canonical_subset; then
    die "Draft release contains an unexpected asset; refusing deletion: ${TAG_NAME}"
  fi
  if release_assets_match; then
    die "Draft release became complete before deletion: ${TAG_NAME}"
  fi
  if gh_command api --method DELETE "repos/${REPOSITORY}/releases/${DRAFT_RELEASE_ID}" >/dev/null; then
    :
  else
    mutation_status=$?
    preserve_mutation_interruption_status "$mutation_status"
    # A transport failure does not reveal whether GitHub committed the DELETE.
    # The same exact-ID plus collection proof below resolves that ambiguity.
    :
  fi
  wait_for_release_absence "$DRAFT_RELEASE_ID" 'Unable to confirm removal of incomplete owned draft'
  ensure_exact_tag false
}

verify_published_release() {
  local release_id="$1"
  require_immutable_published_release
  ensure_exact_tag false
  refresh_release_assets "$release_id"
  if ! release_assets_match; then
    die "Published release asset validation failed for ${TAG_NAME}"
  fi
  ensure_release_is_not_latest
}

publish_verified_draft() {
  local mutation_status
  # Uploads can take long enough for repository settings or draft ownership to
  # change. Repeat every mutable trust check at the final publication boundary.
  require_immutable_releases
  resolve_release_author
  query_release_ids
  if [ -z "$RELEASE_IDS" ]; then
    die "Draft release disappeared before publication: ${TAG_NAME}"
  fi
  if [ -z "$DRAFT_RELEASE_ID" ] || [ "$RELEASE_IDS" != "$DRAFT_RELEASE_ID" ]; then
    die "Draft release identity changed before publication: ${TAG_NAME}"
  fi
  refresh_release_metadata "$DRAFT_RELEASE_ID"
  validate_release_identity "$DRAFT_RELEASE_ID"
  if [ "$METADATA_DRAFT" = false ]; then
    if ! wait_for_release_state "$DRAFT_RELEASE_ID" false true; then
      if [ "$METADATA_ID" = "$DRAFT_RELEASE_ID" ]; then
        require_immutable_published_release
      fi
      die "Published release was not visible after publication: ${TAG_NAME}"
    fi
    verify_published_release "$DRAFT_RELEASE_ID"
    return 0
  fi
  require_mutable_draft
  ensure_exact_tag false
  refresh_release_assets "$DRAFT_RELEASE_ID"
  if ! release_assets_match; then
    die "Draft release asset validation failed for ${TAG_NAME}"
  fi
  local published_release_id
  if published_release_id="$(gh_command api --method PATCH "repos/${REPOSITORY}/releases/${DRAFT_RELEASE_ID}" -f "tag_name=${TAG_NAME}" -f "target_commitish=${COMMIT_SHA}" -f "name=${RELEASE_TITLE}" -f "body=${RELEASE_BODY}" -F draft=false -F prerelease=false -f make_latest=false --jq 'if ((.id | type) == "number" and .id > 0) then (.id | tostring) else "invalid" end')"; then
    if [ "$published_release_id" != "$DRAFT_RELEASE_ID" ]; then
      die "Published release identity changed for ${TAG_NAME}"
    fi
  else
    mutation_status=$?
    preserve_mutation_interruption_status "$mutation_status"
  fi
  # A failed response is ambiguous: the PATCH may already be committed. Resolve
  # the known ID to the immutable state before deciding publication failed.
  if ! wait_for_release_state "$DRAFT_RELEASE_ID" false true; then
    if [ "$METADATA_ID" = "$DRAFT_RELEASE_ID" ]; then
      require_immutable_published_release
    fi
    die "Published release was not visible after publication: ${TAG_NAME}"
  fi
  verify_published_release "$DRAFT_RELEASE_ID"
}

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
TAG_NAME="java-cef-${COMMIT_SHA}"
RELEASE_TITLE="JCEF distributions ${COMMIT_SHA}"
RELEASE_BODY="Automated JCEF distributions for commit ${COMMIT_SHA};${RELEASE_MARKER}"

script_source="${BASH_SOURCE[0]}"
if [[ "$script_source" != */* ]]; then
  script_source="$(resolve_executable "$script_source" || true)"
fi
if [ -z "$script_source" ] || [ ! -f "$script_source" ] || [ -L "$script_source" ]; then
  die 'Unable to resolve the regular publisher script source'
fi
script_parent="${script_source%/*}"
SCRIPT_DIRECTORY="$(cd -- "$script_parent" && pwd -P)" || die 'Unable to resolve the publisher directory'
VERIFIER_PATH="${SCRIPT_DIRECTORY}/verify_distribution_archive.py"
if [ ! -f "$VERIFIER_PATH" ] || [ -L "$VERIFIER_PATH" ] || [ ! -r "$VERIFIER_PATH" ]; then
  die "Required sibling distribution verifier is unavailable: ${VERIFIER_PATH}"
fi

shopt -s dotglob nullglob
ARTIFACT_ENTRIES=("${ARTIFACT_DIRECTORY}"/*)
if [ "${#ARTIFACT_ENTRIES[@]}" -ne 12 ]; then
  die 'Artifact directory must contain exactly the 12 canonical archive and' \
    "checksum files; found ${#ARTIFACT_ENTRIES[@]}"
fi

prepare_sanitized_environment
if HASH_PATH="$(resolve_executable sha256sum)"; then
  HASH_COMMAND='sha256sum'
elif HASH_PATH="$(resolve_executable shasum)"; then
  HASH_COMMAND='shasum'
else
  die 'sha256sum or shasum is required to validate distribution archives'
fi
CMP_PATH="$(resolve_executable cmp || true)"
WC_PATH="$(resolve_executable wc || true)"
TR_PATH="$(resolve_executable tr || true)"
PYTHON_PATH="$(resolve_executable python3 || true)"
SLEEP_PATH="$(resolve_executable sleep || true)"
if [ -z "$CMP_PATH" ] || [ -z "$WC_PATH" ] || [ -z "$TR_PATH" ] || [ -z "$PYTHON_PATH" ] || [ -z "$SLEEP_PATH" ]; then
  die 'cmp, sleep, wc, tr and Python 3.9+ are required to validate distribution artifacts'
fi
if ! "$SYSTEM_ENV_PATH" "${SANITIZED_ENV_ARGS[@]}" "$PYTHON_PATH" -I -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 9) else 1)'; then
  die 'Python 3.9 or newer is required to validate distribution artifacts'
fi

for target in "${TARGETS[@]}"; do
  archive_name="${target}.tar.gz"
  archive_path="${ARTIFACT_DIRECTORY}/${archive_name}"
  checksum_name="${archive_name}.sha256"
  checksum_path="${ARTIFACT_DIRECTORY}/${checksum_name}"
  if [ ! -f "$archive_path" ] || [ -L "$archive_path" ]; then
    die "Missing canonical regular archive: ${archive_name}"
  fi
  if [ ! -f "$checksum_path" ] || [ -L "$checksum_path" ]; then
    die "Missing canonical regular checksum: ${checksum_name}"
  fi
  if ! archive_digest="$(hash_file "$archive_path")"; then
    die "Unable to calculate a valid SHA-256 for ${archive_name}"
  fi
  # Bash variables cannot preserve NUL bytes, so compare the complete file to
  # generated LF and CRLF byte streams instead of parsing checksum text.
  if ! "$CMP_PATH" -s "$checksum_path" <(printf '%s  %s\n' "$archive_digest" "$archive_name") && ! "$CMP_PATH" -s "$checksum_path" <(printf '%s  %s\r\n' "$archive_digest" "$archive_name"); then
    die "Checksum must byte-match the canonical LF or CRLF form: ${checksum_name}"
  fi
  if ! archive_size="$(file_size "$archive_path")" || ! checksum_size="$(file_size "$checksum_path")" || ! checksum_digest="$(hash_file "$checksum_path")"; then
    die "Unable to calculate asset metadata for ${target}"
  fi
  if ! "$SYSTEM_ENV_PATH" "${SANITIZED_ENV_ARGS[@]}" "$PYTHON_PATH" -I "$VERIFIER_PATH" --target "$target" --archive "$archive_path" --java-cef-commit "$COMMIT_SHA"; then
    die "Distribution archive byte verification failed: ${archive_name}"
  fi
  append_asset "$archive_name" "$archive_size" "$archive_digest"
  append_asset "$checksum_name" "$checksum_size" "$checksum_digest"
  ARCHIVE_PATHS+=("$archive_path")
  CHECKSUM_PATHS+=("$checksum_path")
done

GH_PATH="$(resolve_executable gh || true)"
if [ -z "$GH_PATH" ]; then
  die 'gh is required for distribution publication'
fi
if [ -n "$ENV_TOKEN_SOURCE" ]; then
  if [ -z "$ENV_TOKEN_CONTENT" ] || [[ ! "$ENV_TOKEN_CONTENT" =~ [^[:space:]] ]]; then
    die "${ENV_TOKEN_SOURCE} must contain a non-whitespace token when set"
  fi
fi

require_immutable_releases
resolve_release_author
resolve_initial_release_state
if [ -n "$RELEASE_IDS" ]; then
  validate_release_identity "$RELEASE_IDS"
  if [ "$METADATA_DRAFT" = false ]; then
    require_immutable_published_release
    ensure_exact_tag false
    refresh_release_assets "$RELEASE_IDS"
    if ! release_assets_match; then
      die "Published release does not exactly match local assets: ${TAG_NAME}"
    fi
    ensure_release_is_not_latest
    echo "GitHub Release ${TAG_NAME} is already published and matches exactly"
    exit 0
  fi
  require_mutable_draft
  DRAFT_RELEASE_ID="$RELEASE_IDS"

  # Validate ownership and the canonical asset-name subset before mutating
  # even the tag. Only this script's exact bot-authored draft is recoverable.
  refresh_release_assets "$DRAFT_RELEASE_ID"
  if ! release_assets_are_canonical_subset; then
    die "Draft release contains an unexpected asset; refusing recovery: ${TAG_NAME}"
  fi
  ensure_exact_tag true
  if release_assets_match; then
    publish_verified_draft
    echo "Published recovered GitHub Release ${TAG_NAME}"
    exit 0
  fi
  delete_incomplete_owned_draft
else
  ensure_exact_tag true
fi

create_empty_draft

# Checksums are uploaded only after the complete archive upload succeeds. Each
# raw-byte request targets the validated draft ID, so tag replacement cannot
# redirect an upload. The release remains a recoverable draft until all assets
# verify and the final publication boundary succeeds.
upload_release_assets

publish_verified_draft
echo "Published all six JCEF distributions in GitHub Release ${TAG_NAME}"
