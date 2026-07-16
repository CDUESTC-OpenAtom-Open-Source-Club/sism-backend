#!/usr/bin/env bash
set -euo pipefail

TARGET_DIR="${1:-/opt/sism-stack/production}"
MIN_AVAILABLE_MEMORY_MB="${MIN_AVAILABLE_MEMORY_MB:-768}"
MIN_FREE_DISK_GB="${MIN_FREE_DISK_GB:-5}"

fail() {
  printf '[preflight] ERROR: %s\n' "$1" >&2
  exit 1
}

log() {
  printf '[preflight] %s\n' "$1"
}

version_at_least() {
  local actual="$1"
  local required="$2"
  [[ "$(printf '%s\n%s\n' "${required}" "${actual}" | sort -V | head -n 1)" == "${required}" ]]
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

[[ "$(uname -s)" == "Linux" ]] || fail "Only Linux deployment hosts are supported"

ARCH="$(uname -m)"
case "${ARCH}" in
  x86_64 | amd64)
    ;;
  *)
    fail "Unsupported architecture ${ARCH}; GitHub release images are built for X64"
    ;;
esac

if [[ -r /etc/os-release ]]; then
  # shellcheck disable=SC1091
  source /etc/os-release
else
  fail "/etc/os-release is missing"
fi

case "${ID:-unknown}" in
  ubuntu)
    version_at_least "${VERSION_ID:-0}" "24.04" || fail "Ubuntu 24.04 or newer is required"
    ;;
  opencloudos | rocky | almalinux | rhel | centos | debian)
    log "Compatibility mode enabled for ${PRETTY_NAME:-${ID}}"
    ;;
  *)
    fail "Unsupported Linux distribution: ${PRETTY_NAME:-${ID:-unknown}}"
    ;;
esac

for command_name in \
  awk bash curl df flock grep gzip openssl pgrep sed setsid sort tar timeout; do
  require_command "${command_name}"
done
require_command sudo
require_command docker

sudo -n true >/dev/null 2>&1 || fail "The runner user must have passwordless sudo"
sudo docker info >/dev/null 2>&1 || fail "Docker daemon is unavailable to the runner"
sudo docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 plugin is unavailable"

COMPOSE_VERSION="$(sudo docker compose version --short 2>/dev/null | sed 's/^v//')"
COMPOSE_MAJOR="${COMPOSE_VERSION%%.*}"
[[ "${COMPOSE_MAJOR}" =~ ^[0-9]+$ ]] || fail "Unable to parse Docker Compose version: ${COMPOSE_VERSION}"
(( COMPOSE_MAJOR >= 2 )) || fail "Docker Compose v2 or newer is required"

MEM_AVAILABLE_KB="$(awk '/MemAvailable:/ { print $2 }' /proc/meminfo)"
MEM_AVAILABLE_MB="$((MEM_AVAILABLE_KB / 1024))"
(( MEM_AVAILABLE_MB >= MIN_AVAILABLE_MEMORY_MB )) || \
  fail "Only ${MEM_AVAILABLE_MB} MB memory is available; require ${MIN_AVAILABLE_MEMORY_MB} MB"

SWAP_TOTAL_KB="$(awk '/SwapTotal:/ { print $2 }' /proc/meminfo)"
SWAP_FREE_KB="$(awk '/SwapFree:/ { print $2 }' /proc/meminfo)"
if (( SWAP_TOTAL_KB > 0 )); then
  SWAP_USED_PERCENT="$(((SWAP_TOTAL_KB - SWAP_FREE_KB) * 100 / SWAP_TOTAL_KB))"
  if (( SWAP_USED_PERCENT >= 90 )); then
    log "WARNING: swap usage is ${SWAP_USED_PERCENT}%"
  fi
fi

DISK_PATH="${TARGET_DIR}"
while [[ ! -e "${DISK_PATH}" && "${DISK_PATH}" != "/" ]]; do
  DISK_PATH="$(dirname "${DISK_PATH}")"
done
FREE_DISK_KB="$(df -Pk "${DISK_PATH}" | awk 'NR == 2 { print $4 }')"
FREE_DISK_GB="$((FREE_DISK_KB / 1024 / 1024))"
(( FREE_DISK_GB >= MIN_FREE_DISK_GB )) || \
  fail "Only ${FREE_DISK_GB} GB disk is free at ${DISK_PATH}; require ${MIN_FREE_DISK_GB} GB"

log "OS=${PRETTY_NAME:-${ID}}"
log "ARCH=${ARCH}"
log "DOCKER=$(sudo docker version --format '{{.Server.Version}}')"
log "COMPOSE=${COMPOSE_VERSION}"
log "AVAILABLE_MEMORY_MB=${MEM_AVAILABLE_MB}"
log "FREE_DISK_GB=${FREE_DISK_GB}"
log "TARGET_DIR=${TARGET_DIR}"
log "PREFLIGHT_OK"
