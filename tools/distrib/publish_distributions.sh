#!/bin/bash
# Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license
# that can be found in the LICENSE file.

set -euo pipefail
set +x

readonly REPOSITORY_OWNER='Keksuccino'
readonly REPOSITORY_NAME='jcef-mcef'
readonly REPOSITORY="${REPOSITORY_OWNER}/${REPOSITORY_NAME}"
readonly RELEASE_MARKER='managed-by=tools/distrib/publish_distributions.sh;schema=1'
readonly LATEST_RELEASE_QUERY="query(\$owner:String!,\$name:String!){repository(owner:\$owner,name:\$name){latestRelease{tagName}}}"
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
ENV_TOKEN_SOURCE=''
ENV_TOKEN_CONTENT=''
if [ "${GH_TOKEN+x}" = x ]; then
  ENV_TOKEN_SOURCE='GH_TOKEN'
  ENV_TOKEN_CONTENT="$GH_TOKEN"
elif [ "${GITHUB_TOKEN+x}" = x ]; then
  ENV_TOKEN_SOURCE='GITHUB_TOKEN'
  ENV_TOKEN_CONTENT="$GITHUB_TOKEN"
fi
unset GITHUB_TOKEN GH_TOKEN GH_HOST

GH_PATH=''
HASH_COMMAND=''
CMP_PATH=''
COMMIT_SHA=''
ARTIFACT_DIRECTORY=''
TAG_NAME=''
RELEASE_TITLE=''
RELEASE_BODY=''
RELEASE_IDS=''
TAG_REFS=''
RELEASE_ASSETS=''
METADATA_TAG=''
METADATA_TARGET=''
METADATA_DRAFT=''
METADATA_IMMUTABLE=''
METADATA_PRERELEASE=''
METADATA_TITLE=''
METADATA_BODY=''
METADATA_AUTHOR=''
RELEASE_AUTHOR=''

ASSET_NAMES=()
ASSET_SIZES=()
ASSET_DIGESTS=()
ARCHIVE_PATHS=()
CHECKSUM_PATHS=()

die() {
  echo "ERROR: $*" >&2
  exit 1
}

gh_command() {
  if [ -n "$ENV_TOKEN_SOURCE" ]; then
    GH_TOKEN="$ENV_TOKEN_CONTENT" GH_HOST=github.com GH_PROMPT_DISABLED=1 GH_NO_UPDATE_NOTIFIER=1 "$GH_PATH" "$@"
  else
    GH_HOST=github.com GH_PROMPT_DISABLED=1 GH_NO_UPDATE_NOTIFIER=1 "$GH_PATH" "$@"
  fi
}

hash_file() {
  local path="$1"
  local output
  local digest
  if [ "$HASH_COMMAND" = 'sha256sum' ]; then
    output="$(sha256sum -- "$path")" || return 1
  else
    output="$(shasum -a 256 "$path")" || return 1
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
  size="$(wc -c < "$path" | tr -d '[:space:]')" || return 1
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

query_release_ids() {
  # A successful, paginated list query is required to prove absence. A failed
  # tag lookup must never be mistaken for permission or network-safe absence.
  if ! RELEASE_IDS="$(gh_command api --paginate "repos/${REPOSITORY}/releases?per_page=100" --jq ".[] | select(.tag_name == \"${TAG_NAME}\") | .id")"; then
    die "Unable to inspect releases for ${TAG_NAME}"
  fi
  if [[ "$RELEASE_IDS" == *$'\n'* ]]; then
    die "Multiple releases unexpectedly use tag ${TAG_NAME}"
  fi
  if [ -n "$RELEASE_IDS" ]; then
    case "$RELEASE_IDS" in
      *[!0-9]*) die "Release query returned an invalid identifier for ${TAG_NAME}" ;;
    esac
  fi
}

refresh_release_metadata() {
  local metadata
  if ! metadata="$(gh_command release view "$TAG_NAME" --repo "$REPOSITORY" --json tagName,targetCommitish,isDraft,isImmutable,isPrerelease,name,body,author --jq '[.tagName, .targetCommitish, (.isDraft | tostring), (.isImmutable | tostring), (.isPrerelease | tostring), .name, .body, .author.login] | join("|")')"; then
    die "Unable to inspect release metadata for ${TAG_NAME}"
  fi
  IFS='|' read -r METADATA_TAG METADATA_TARGET METADATA_DRAFT METADATA_IMMUTABLE METADATA_PRERELEASE METADATA_TITLE METADATA_BODY METADATA_AUTHOR <<< "$metadata"
}

validate_release_identity() {
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
  local resolved_sha
  query_tag_refs
  if [ -z "$TAG_REFS" ]; then
    if [ "$allow_create" != true ]; then
      die "Required tag does not exist: ${TAG_NAME}"
    fi
    # Create a lightweight tag explicitly so gh can never infer the default
    # branch tip. Existing refs are only validated and are never retargeted.
    if ! gh_command api --method POST "repos/${REPOSITORY}/git/refs" -f "ref=refs/tags/${TAG_NAME}" -f "sha=${COMMIT_SHA}" >/dev/null; then
      die "Unable to create exact tag ${TAG_NAME}"
    fi
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
  if ! RELEASE_ASSETS="$(gh_command release view "$TAG_NAME" --repo "$REPOSITORY" --json assets --jq '.assets[] | [.name, (.size | tostring), .state, (.digest // "")] | join("|")')"; then
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

create_empty_draft() {
  if ! gh_command release create "$TAG_NAME" --repo "$REPOSITORY" --draft --verify-tag --target "$COMMIT_SHA" --title "$RELEASE_TITLE" --notes "$RELEASE_BODY" --latest=false; then
    die "Unable to create draft release ${TAG_NAME}"
  fi
  query_release_ids
  if [ -z "$RELEASE_IDS" ]; then
    die "Draft release was not visible after creation: ${TAG_NAME}"
  fi
  refresh_release_metadata
  validate_release_identity
  require_mutable_draft
  ensure_exact_tag false
  refresh_release_assets
  if [ -n "$RELEASE_ASSETS" ]; then
    die "New draft release unexpectedly contains assets: ${TAG_NAME}"
  fi
}

publish_verified_draft() {
  ensure_exact_tag false
  refresh_release_assets
  if ! release_assets_match; then
    die "Draft release asset validation failed for ${TAG_NAME}"
  fi
  if ! gh_command release edit "$TAG_NAME" --repo "$REPOSITORY" --draft=false --verify-tag --target "$COMMIT_SHA" --latest=false; then
    die "Unable to publish verified draft release ${TAG_NAME}"
  fi
  query_release_ids
  if [ -z "$RELEASE_IDS" ]; then
    die "Published release was not visible after publication: ${TAG_NAME}"
  fi
  refresh_release_metadata
  validate_release_identity
  require_immutable_published_release
  ensure_exact_tag false
  refresh_release_assets
  if ! release_assets_match; then
    die "Published release asset validation failed for ${TAG_NAME}"
  fi
  ensure_release_is_not_latest
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
CMP_PATH="$(command -v cmp || true)"
if [ -z "$CMP_PATH" ]; then
  die 'cmp is required to validate distribution checksums byte-for-byte'
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
  append_asset "$archive_name" "$archive_size" "$archive_digest"
  append_asset "$checksum_name" "$checksum_size" "$checksum_digest"
  ARCHIVE_PATHS+=("$archive_path")
  CHECKSUM_PATHS+=("$checksum_path")
done

GH_PATH="$(command -v gh || true)"
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
query_release_ids
if [ -n "$RELEASE_IDS" ]; then
  refresh_release_metadata
  validate_release_identity
  if [ "$METADATA_DRAFT" = false ]; then
    require_immutable_published_release
    ensure_exact_tag false
    refresh_release_assets
    if ! release_assets_match; then
      die "Published release does not exactly match local assets: ${TAG_NAME}"
    fi
    ensure_release_is_not_latest
    echo "GitHub Release ${TAG_NAME} is already published and matches exactly"
    exit 0
  fi
  require_mutable_draft

  # Validate ownership and the canonical asset-name subset before mutating
  # even the tag. Only this script's exact bot-authored draft is recoverable.
  refresh_release_assets
  if ! release_assets_are_canonical_subset; then
    die "Draft release contains an unexpected asset; refusing recovery: ${TAG_NAME}"
  fi
  ensure_exact_tag true
  if release_assets_match; then
    publish_verified_draft
    echo "Published recovered GitHub Release ${TAG_NAME}"
    exit 0
  fi
  if ! gh_command release delete "$TAG_NAME" --repo "$REPOSITORY" --yes; then
    die "Unable to remove incomplete owned draft ${TAG_NAME}"
  fi
  query_release_ids
  if [ -n "$RELEASE_IDS" ]; then
    die "Incomplete draft still exists after deletion: ${TAG_NAME}"
  fi
  ensure_exact_tag false
else
  ensure_exact_tag true
fi

create_empty_draft

# Checksums are uploaded only after the complete archive upload succeeds. The
# release remains an invisible, recoverable draft until all 12 assets verify.
if ! gh_command release upload "$TAG_NAME" "${ARCHIVE_PATHS[@]}" --repo "$REPOSITORY"; then
  die "Archive upload failed for draft ${TAG_NAME}"
fi
if ! gh_command release upload "$TAG_NAME" "${CHECKSUM_PATHS[@]}" --repo "$REPOSITORY"; then
  die "Checksum upload failed for draft ${TAG_NAME}"
fi

publish_verified_draft
echo "Published all six JCEF distributions in GitHub Release ${TAG_NAME}"
