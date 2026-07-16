#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
TARGET_DIR="/opt/sism-stack/production"
ENV_SOURCE=""
VERIFY_ONLY=false
DEPLOY_STARTED=false
ROLLBACK_ACTIVE=false
OLD_BACKEND_IMAGE=""
OLD_FRONTEND_IMAGE=""
COMPOSE_FILE="docker-compose.yml"

usage() {
  cat <<'EOF'
Usage: install-offline-release.sh [options]

Options:
  --target DIR       Stack directory (default: /opt/sism-stack/production)
  --env-file FILE    Install an external production .env file before deployment
  --verify-only      Verify manifest, checksums and image archives only
  -h, --help         Show this help
EOF
}

log() {
  printf '[offline-install] %s\n' "$1"
}

fail() {
  printf '[offline-install] ERROR: %s\n' "$1" >&2
  exit 1
}

root_run() {
  if [[ "${EUID}" -eq 0 ]]; then
    "$@"
  else
    sudo -n "$@"
  fi
}

manifest_value() {
  local key="$1"
  awk -F= -v key="${key}" 'index($0, key "=") == 1 { sub("^[^=]*=", ""); print; exit }' \
    "${BUNDLE_ROOT}/manifest.env"
}

upsert_env() {
  local key="$1"
  local value="$2"
  local env_file="${TARGET_DIR}/.env"
  local temp_file=""
  if root_run grep -q "^${key}=" "${env_file}"; then
    temp_file="$(mktemp "${TMPDIR:-/tmp}/sism-offline-env.XXXXXX")"
    # shellcheck disable=SC2016
    root_run awk -v key="${key}" -v value="${value}" '
      index($0, key "=") == 1 { print key "=" value; next }
      { print }
    ' "${env_file}" > "${temp_file}"
    root_run install -m 0600 "${temp_file}" "${env_file}"
    rm -f "${temp_file}"
  else
    printf '%s=%s\n' "${key}" "${value}" | root_run tee -a "${env_file}" >/dev/null
  fi
}

wait_for_service_health() {
  local service="$1"
  local attempts="$2"
  local container_id=""
  local state=""
  for _ in $(seq 1 "${attempts}"); do
    container_id="$(root_run docker compose -f "${COMPOSE_FILE}" ps -q "${service}" 2>/dev/null || true)"
    if [[ -n "${container_id}" ]]; then
      state="$(root_run docker inspect "${container_id}" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>/dev/null || true)"
      if [[ "${state}" == "healthy" || "${state}" == "running" ]]; then
        log "SERVICE_HEALTHY=${service}"
        return 0
      fi
    fi
    sleep 2
  done
  return 1
}

rollback() {
  local exit_code="$1"
  if [[ "${DEPLOY_STARTED}" == "true" && "${ROLLBACK_ACTIVE}" != "true" && \
        -n "${OLD_BACKEND_IMAGE}" && -n "${OLD_FRONTEND_IMAGE}" ]]; then
    ROLLBACK_ACTIVE=true
    log "Deployment failed; restoring previous image references"
    upsert_env BACKEND_IMAGE "${OLD_BACKEND_IMAGE}" || true
    upsert_env FRONTEND_IMAGE "${OLD_FRONTEND_IMAGE}" || true
    (
      cd "${TARGET_DIR}"
      root_run docker compose -f "${COMPOSE_FILE}" up -d --force-recreate
    ) || true
  fi
  exit "${exit_code}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)
      [[ $# -ge 2 ]] || fail "--target requires a value"
      TARGET_DIR="$2"
      shift 2
      ;;
    --env-file)
      [[ $# -ge 2 ]] || fail "--env-file requires a value"
      ENV_SOURCE="$2"
      shift 2
      ;;
    --verify-only)
      VERIFY_ONLY=true
      shift
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

[[ -f "${BUNDLE_ROOT}/manifest.env" ]] || fail "manifest.env is missing"
[[ -f "${BUNDLE_ROOT}/SHA256SUMS" ]] || fail "SHA256SUMS is missing"

BACKEND_SHA="$(manifest_value BACKEND_SHA)"
FRONTEND_SHA="$(manifest_value FRONTEND_SHA)"
BACKEND_IMAGE="$(manifest_value BACKEND_IMAGE)"
FRONTEND_IMAGE="$(manifest_value FRONTEND_IMAGE)"
[[ "${BACKEND_SHA}" =~ ^[0-9a-f]{40}$ ]] || fail "Invalid backend SHA"
[[ "${FRONTEND_SHA}" =~ ^[0-9a-f]{40}$ ]] || fail "Invalid frontend SHA"
[[ "${BACKEND_IMAGE}" == *":${BACKEND_SHA}" ]] || fail "Backend image does not match manifest SHA"
[[ "${FRONTEND_IMAGE}" == *":${FRONTEND_SHA}" ]] || fail "Frontend image does not match manifest SHA"

if command -v sha256sum >/dev/null 2>&1; then
  (cd "${BUNDLE_ROOT}" && sha256sum -c SHA256SUMS)
else
  command -v shasum >/dev/null 2>&1 || fail "sha256sum or shasum is required"
  (cd "${BUNDLE_ROOT}" && shasum -a 256 -c SHA256SUMS)
fi
gzip -t "${BUNDLE_ROOT}/images/backend-image.tar.gz"
gzip -t "${BUNDLE_ROOT}/images/frontend-image.tar.gz"
log "BUNDLE_VERIFIED backend=${BACKEND_SHA} frontend=${FRONTEND_SHA}"

if [[ "${VERIFY_ONLY}" == "true" ]]; then
  exit 0
fi

for command_name in awk curl docker grep gzip install mktemp sed sudo tar; do
  command -v "${command_name}" >/dev/null 2>&1 || fail "Missing required command: ${command_name}"
done
[[ "$(uname -s)" == "Linux" ]] || fail "Deployment target must be Linux"
[[ "$(uname -m)" == "x86_64" ]] || fail "Deployment target must be X64"
if [[ "${EUID}" -ne 0 ]]; then
  sudo -n true >/dev/null 2>&1 || fail "Passwordless sudo is required"
fi
root_run docker info >/dev/null
root_run docker compose version >/dev/null

root_run mkdir -p "${TARGET_DIR}/nginx"
if [[ -n "${ENV_SOURCE}" ]]; then
  [[ -f "${ENV_SOURCE}" ]] || fail "Environment file does not exist: ${ENV_SOURCE}"
  root_run install -m 0600 "${ENV_SOURCE}" "${TARGET_DIR}/.env"
fi
root_run test -f "${TARGET_DIR}/.env" || \
  fail "${TARGET_DIR}/.env is required; migrate it from the previous server or pass --env-file"

# shellcheck disable=SC2016
OLD_BACKEND_IMAGE="$(root_run awk -F= '/^BACKEND_IMAGE=/{print $2}' "${TARGET_DIR}/.env" | tail -n 1)"
# shellcheck disable=SC2016
OLD_FRONTEND_IMAGE="$(root_run awk -F= '/^FRONTEND_IMAGE=/{print $2}' "${TARGET_DIR}/.env" | tail -n 1)"
# shellcheck disable=SC2016
DB_MODE="$(root_run awk -F= '/^DB_MODE=/{print $2}' "${TARGET_DIR}/.env" | tail -n 1)"
DB_MODE="${DB_MODE:-internal}"
if [[ "${DB_MODE}" == "external" ]]; then
  COMPOSE_FILE="docker-compose.external-db.yml"
elif [[ "${DB_MODE}" != "internal" ]]; then
  fail "Unsupported DB_MODE=${DB_MODE}"
fi

trap 'rollback $?' ERR

log "Loading Docker images from offline archives"
gzip -dc "${BUNDLE_ROOT}/images/backend-image.tar.gz" | root_run docker load
gzip -dc "${BUNDLE_ROOT}/images/frontend-image.tar.gz" | root_run docker load
root_run docker image inspect "${BACKEND_IMAGE}" >/dev/null
root_run docker image inspect "${FRONTEND_IMAGE}" >/dev/null

root_run install -m 0644 "${BUNDLE_ROOT}/compose/docker-compose.yml" "${TARGET_DIR}/docker-compose.yml"
root_run install -m 0644 "${BUNDLE_ROOT}/compose/docker-compose.external-db.yml" "${TARGET_DIR}/docker-compose.external-db.yml"
root_run install -m 0644 "${BUNDLE_ROOT}/compose/nginx/frontend.conf" "${TARGET_DIR}/nginx/frontend.conf"
upsert_env BACKEND_IMAGE "${BACKEND_IMAGE}"
upsert_env FRONTEND_IMAGE "${FRONTEND_IMAGE}"

DEPLOY_STARTED=true
(
  cd "${TARGET_DIR}"
  root_run docker compose -f "${COMPOSE_FILE}" config --quiet
  root_run docker compose -f "${COMPOSE_FILE}" up -d --force-recreate
  if [[ "${DB_MODE}" == "internal" ]]; then
    wait_for_service_health postgres 90
  fi
  wait_for_service_health backend 180
  wait_for_service_health frontend 90
)

# shellcheck disable=SC2016
BACKEND_PORT="$(root_run awk -F= '/^BACKEND_PORT=/{print $2}' "${TARGET_DIR}/.env" | tail -n 1)"
# shellcheck disable=SC2016
FRONTEND_PORT="$(root_run awk -F= '/^FRONTEND_PORT=/{print $2}' "${TARGET_DIR}/.env" | tail -n 1)"
BACKEND_PORT="${BACKEND_PORT:-18080}"
FRONTEND_PORT="${FRONTEND_PORT:-18081}"
curl -fsS "http://127.0.0.1:${BACKEND_PORT}/api/v1/actuator/health" | grep -q '"status":"UP"'
curl -fsS "http://127.0.0.1:${FRONTEND_PORT}/" >/dev/null

root_run tee "${TARGET_DIR}/offline-deploy-status.env" >/dev/null <<EOF
DEPLOY_STATE=success
DEPLOYED_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)
BACKEND_SHA=${BACKEND_SHA}
FRONTEND_SHA=${FRONTEND_SHA}
BACKEND_IMAGE=${BACKEND_IMAGE}
FRONTEND_IMAGE=${FRONTEND_IMAGE}
EOF

trap - ERR
log "OFFLINE_DEPLOY_SUCCESS backend=${BACKEND_SHA} frontend=${FRONTEND_SHA}"
