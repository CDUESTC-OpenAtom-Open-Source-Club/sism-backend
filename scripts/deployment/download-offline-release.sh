#!/usr/bin/env bash
set -euo pipefail

BACKEND_REPO="${BACKEND_REPO:-CDUESTC-OpenAtom-Open-Source-Club/sism-backend}"
FRONTEND_REPO="${FRONTEND_REPO:-CDUESTC-OpenAtom-Open-Source-Club/strategic-task-management}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${OUTPUT_DIR:-dist/offline-releases}"
BACKEND_RUN_ID=""
FRONTEND_RUN_ID=""

usage() {
  cat <<'EOF'
Usage: download-offline-release.sh [options]

Options:
  --output DIR          Output directory (default: dist/offline-releases)
  --backend-run ID      Successful backend Actions run ID
  --frontend-run ID     Successful frontend Actions run ID
  -h, --help            Show this help

When run IDs are omitted, the latest successful push run on main is used.
EOF
}

log() {
  printf '[offline-download] %s\n' "$1"
}

fail() {
  printf '[offline-download] ERROR: %s\n' "$1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

download_artifact() {
  local repo="$1"
  local run_id="$2"
  local artifact_name="$3"
  local destination="$4"
  local artifact_json
  local artifact_id
  local artifact_size
  local artifact_expired
  local token
  local auth_header_file
  local zip_path

  artifact_json="$(gh api "/repos/${repo}/actions/runs/${run_id}/artifacts" --paginate \
    --jq ".artifacts[] | select(.name == \"${artifact_name}\")")"
  [[ -n "${artifact_json}" ]] || fail "Artifact ${artifact_name} was not found in run ${run_id}"
  artifact_id="$(jq -r '.id' <<<"${artifact_json}" | head -n 1)"
  artifact_size="$(jq -r '.size_in_bytes' <<<"${artifact_json}" | head -n 1)"
  artifact_expired="$(jq -r '.expired' <<<"${artifact_json}" | head -n 1)"
  [[ "${artifact_expired}" == "false" ]] || fail "Artifact ${artifact_name} has expired"
  [[ "${artifact_id}" =~ ^[0-9]+$ ]] || fail "Invalid artifact ID for ${artifact_name}"

  mkdir -p "${destination}"
  zip_path="${WORK_DIR}/${artifact_name}.zip"
  token="$(gh auth token)"
  auth_header_file="${WORK_DIR}/.${artifact_name}.github-auth-header"
  (umask 077 && printf 'Authorization: Bearer %s\n' "${token}" > "${auth_header_file}")
  unset token
  log "Downloading ${artifact_name} (${artifact_size} bytes)"
  curl \
    --fail \
    --location \
    --silent \
    --show-error \
    --retry 5 \
    --retry-delay 2 \
    --retry-all-errors \
    --connect-timeout 30 \
    --max-time 1800 \
    --header "Accept: application/vnd.github+json" \
    --header "@${auth_header_file}" \
    --output "${zip_path}" \
    "https://api.github.com/repos/${repo}/actions/artifacts/${artifact_id}/zip"
  rm -f "${auth_header_file}"
  unzip -q "${zip_path}" -d "${destination}"
  rm -f "${zip_path}"
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1"
  else
    shasum -a 256 "$1"
  fi
}

latest_successful_push_run() {
  local repo="$1"
  gh run list \
    --repo "${repo}" \
    --workflow build-and-push-image.yml \
    --branch main \
    --event push \
    --status success \
    --limit 20 \
    --json databaseId,headSha,createdAt \
    --jq 'sort_by(.createdAt) | reverse | .[0].databaseId'
}

run_sha() {
  local repo="$1"
  local run_id="$2"
  local metadata
  metadata="$(gh run view "${run_id}" --repo "${repo}" --json status,conclusion,headSha)"
  [[ "$(jq -r '.status' <<<"${metadata}")" == "completed" ]] || \
    fail "Run ${run_id} in ${repo} is not completed"
  [[ "$(jq -r '.conclusion' <<<"${metadata}")" == "success" ]] || \
    fail "Run ${run_id} in ${repo} did not succeed"
  jq -r '.headSha' <<<"${metadata}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output)
      [[ $# -ge 2 ]] || fail "--output requires a value"
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --backend-run)
      [[ $# -ge 2 ]] || fail "--backend-run requires a value"
      BACKEND_RUN_ID="$2"
      shift 2
      ;;
    --frontend-run)
      [[ $# -ge 2 ]] || fail "--frontend-run requires a value"
      FRONTEND_RUN_ID="$2"
      shift 2
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      fail "Unknown argument: $1"
      ;;
  esac
done

for command_name in curl gh gzip jq tar unzip; do
  require_command "${command_name}"
done
command -v sha256sum >/dev/null 2>&1 || require_command shasum
gh auth status >/dev/null 2>&1 || fail "GitHub CLI is not authenticated"

if [[ -z "${BACKEND_RUN_ID}" ]]; then
  BACKEND_RUN_ID="$(latest_successful_push_run "${BACKEND_REPO}")"
fi
if [[ -z "${FRONTEND_RUN_ID}" ]]; then
  FRONTEND_RUN_ID="$(latest_successful_push_run "${FRONTEND_REPO}")"
fi
[[ "${BACKEND_RUN_ID}" =~ ^[0-9]+$ ]] || fail "Unable to resolve backend run ID"
[[ "${FRONTEND_RUN_ID}" =~ ^[0-9]+$ ]] || fail "Unable to resolve frontend run ID"

BACKEND_SHA="$(run_sha "${BACKEND_REPO}" "${BACKEND_RUN_ID}")"
FRONTEND_SHA="$(run_sha "${FRONTEND_REPO}" "${FRONTEND_RUN_ID}")"
[[ "${BACKEND_SHA}" =~ ^[0-9a-f]{40}$ ]] || fail "Invalid backend SHA: ${BACKEND_SHA}"
[[ "${FRONTEND_SHA}" =~ ^[0-9a-f]{40}$ ]] || fail "Invalid frontend SHA: ${FRONTEND_SHA}"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

log "Downloading backend artifacts from run ${BACKEND_RUN_ID} (${BACKEND_SHA})"
download_artifact \
  "${BACKEND_REPO}" \
  "${BACKEND_RUN_ID}" \
  "backend-image-${BACKEND_SHA}" \
  "${WORK_DIR}/backend-image" &
BACKEND_IMAGE_DOWNLOAD_PID=$!
download_artifact \
  "${BACKEND_REPO}" \
  "${BACKEND_RUN_ID}" \
  "backend-stack-${BACKEND_SHA}" \
  "${WORK_DIR}/backend-stack" &
BACKEND_STACK_DOWNLOAD_PID=$!

log "Downloading frontend artifact from run ${FRONTEND_RUN_ID} (${FRONTEND_SHA})"
download_artifact \
  "${FRONTEND_REPO}" \
  "${FRONTEND_RUN_ID}" \
  "frontend-image-${FRONTEND_SHA}" \
  "${WORK_DIR}/frontend-image" &
FRONTEND_IMAGE_DOWNLOAD_PID=$!

DOWNLOAD_FAILED=false
for download_pid in \
  "${BACKEND_IMAGE_DOWNLOAD_PID}" \
  "${BACKEND_STACK_DOWNLOAD_PID}" \
  "${FRONTEND_IMAGE_DOWNLOAD_PID}"; do
  if ! wait "${download_pid}"; then
    DOWNLOAD_FAILED=true
  fi
done
[[ "${DOWNLOAD_FAILED}" == "false" ]] || fail "One or more artifact downloads failed"

gzip -t "${WORK_DIR}/backend-image/backend-image.tar.gz"
gzip -t "${WORK_DIR}/frontend-image/frontend-image.tar.gz"

mkdir -p "${OUTPUT_DIR}"
OUTPUT_DIR="$(cd "${OUTPUT_DIR}" && pwd)"
RELEASE_NAME="sism-offline-${BACKEND_SHA:0:12}-${FRONTEND_SHA:0:12}"
BUNDLE_DIR="${OUTPUT_DIR}/${RELEASE_NAME}"
ARCHIVE_PATH="${OUTPUT_DIR}/${RELEASE_NAME}.tar.gz"
rm -rf "${BUNDLE_DIR}" "${ARCHIVE_PATH}" "${ARCHIVE_PATH}.sha256"
mkdir -p \
  "${BUNDLE_DIR}/images" \
  "${BUNDLE_DIR}/compose/nginx" \
  "${BUNDLE_DIR}/scripts" \
  "${BUNDLE_DIR}/database"

cp "${WORK_DIR}/backend-image/backend-image.tar.gz" "${BUNDLE_DIR}/images/"
cp "${WORK_DIR}/frontend-image/frontend-image.tar.gz" "${BUNDLE_DIR}/images/"
cp "${WORK_DIR}/backend-stack/deploy/compose/docker-compose.yml" "${BUNDLE_DIR}/compose/"
cp "${WORK_DIR}/backend-stack/deploy/compose/docker-compose.external-db.yml" "${BUNDLE_DIR}/compose/"
cp "${WORK_DIR}/backend-stack/deploy/compose/nginx/frontend.conf" "${BUNDLE_DIR}/compose/nginx/"
if [[ -f "${WORK_DIR}/backend-stack/scripts/deployment/install-offline-release.sh" ]]; then
  cp "${WORK_DIR}/backend-stack/scripts/deployment/install-offline-release.sh" \
    "${BUNDLE_DIR}/scripts/"
else
  log "Backend artifact predates offline installer; using the local compatible installer"
  cp "${SCRIPT_DIR}/install-offline-release.sh" "${BUNDLE_DIR}/scripts/"
fi
cp "${SCRIPT_DIR}/validate-docker-host.sh" "${BUNDLE_DIR}/scripts/"
cp -R "${WORK_DIR}/backend-stack/database/." "${BUNDLE_DIR}/database/"
chmod +x "${BUNDLE_DIR}/scripts/"*.sh

cat > "${BUNDLE_DIR}/manifest.env" <<EOF
BACKEND_SHA=${BACKEND_SHA}
FRONTEND_SHA=${FRONTEND_SHA}
BACKEND_IMAGE=ghcr.io/cduestc-openatom-open-source-club/sism-backend:${BACKEND_SHA}
FRONTEND_IMAGE=ghcr.io/cduestc-openatom-open-source-club/strategic-task-management:${FRONTEND_SHA}
BACKEND_RUN_ID=${BACKEND_RUN_ID}
FRONTEND_RUN_ID=${FRONTEND_RUN_ID}
CREATED_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

cat > "${BUNDLE_DIR}/README.txt" <<'EOF'
SISM offline deployment bundle

1. Keep production secrets outside this bundle.
2. Ensure /opt/sism-stack/production/.env exists on the target server, or pass
   --env-file to install-offline-release.sh.
3. Run scripts/install-offline-release.sh on the target Ubuntu/Linux server.
EOF

CHECKSUMS_TMP="${WORK_DIR}/SHA256SUMS"
(
  cd "${BUNDLE_DIR}"
  find . -type f ! -name SHA256SUMS | LC_ALL=C sort | while IFS= read -r file; do
    sha256_file "${file}"
  done > "${CHECKSUMS_TMP}"
)
mv "${CHECKSUMS_TMP}" "${BUNDLE_DIR}/SHA256SUMS"

tar -C "${OUTPUT_DIR}" -czf "${ARCHIVE_PATH}" "${RELEASE_NAME}"
(
  cd "${OUTPUT_DIR}"
  sha256_file "${RELEASE_NAME}.tar.gz" > "${RELEASE_NAME}.tar.gz.sha256"
)

log "OFFLINE_RELEASE_READY=${ARCHIVE_PATH}"
log "CHECKSUM_FILE=${ARCHIVE_PATH}.sha256"
