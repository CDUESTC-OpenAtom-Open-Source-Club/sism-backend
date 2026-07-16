#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root." >&2
  exit 1
fi

DEPLOY_USER="${1:-sism-runner}"
DEPLOY_HOME="${DEPLOY_HOME:-/opt/sism-stack}"
APP_ENV="${APP_ENV:-production}"
INSTALL_NGINX="${INSTALL_NGINX:-false}"

log() {
  printf '[bootstrap] %s\n' "$1"
}

install_runtime_packages() {
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y \
      ca-certificates coreutils curl gzip openssl procps sudo tar util-linux
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y \
      ca-certificates coreutils curl gzip openssl procps-ng sudo tar util-linux
  elif command -v yum >/dev/null 2>&1; then
    yum install -y \
      ca-certificates coreutils curl gzip openssl procps-ng sudo tar util-linux
  else
    log "Unsupported package manager"
    exit 1
  fi
}

ensure_user() {
  if id "${DEPLOY_USER}" >/dev/null 2>&1; then
    log "User ${DEPLOY_USER} already exists"
  else
    useradd -m -s /bin/bash "${DEPLOY_USER}"
    log "Created user ${DEPLOY_USER}"
  fi
}

install_docker() {
  if command -v docker >/dev/null 2>&1; then
    log "Docker already installed: $(docker --version)"
  else
    log "Installing Docker Engine"
    curl -fsSL https://get.docker.com | sh
  fi

  systemctl enable --now docker
  docker info >/dev/null
  docker compose version >/dev/null
  log "Docker Compose available: $(docker compose version)"
}

install_nginx() {
  if [[ "${INSTALL_NGINX}" != "true" ]]; then
    return
  fi

  if command -v apt-get >/dev/null 2>&1; then
    DEBIAN_FRONTEND=noninteractive apt-get install -y nginx
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y nginx
  else
    yum install -y nginx
  fi

  systemctl enable --now nginx
  log "Nginx installed and enabled"
}

prepare_dirs() {
  install -d -m 0755 "${DEPLOY_HOME}" "${DEPLOY_HOME}/${APP_ENV}" \
    "${DEPLOY_HOME}/${APP_ENV}/nginx"
  chown -R "${DEPLOY_USER}:${DEPLOY_USER}" "${DEPLOY_HOME}"
  log "Prepared ${DEPLOY_HOME}/${APP_ENV}"
}

configure_runner_user() {
  usermod -aG docker "${DEPLOY_USER}"

  SUDOERS_FILE="/etc/sudoers.d/sism-runner"
  printf '%s ALL=(ALL) NOPASSWD: ALL\n' "${DEPLOY_USER}" > "${SUDOERS_FILE}"
  chmod 0440 "${SUDOERS_FILE}"
  visudo -c -f "${SUDOERS_FILE}" >/dev/null

  log "Configured Docker access and passwordless sudo for trusted Actions runner user"
}

validate_host() {
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  sudo -u "${DEPLOY_USER}" \
    MIN_AVAILABLE_MEMORY_MB=768 \
    MIN_FREE_DISK_GB=5 \
    "${SCRIPT_DIR}/validate-docker-host.sh" "${DEPLOY_HOME}/${APP_ENV}"
}

main() {
  install_runtime_packages
  ensure_user
  install_docker
  install_nginx
  prepare_dirs
  configure_runner_user
  validate_host

  log "Bootstrap complete"
  log "Deploy user: ${DEPLOY_USER}"
  log "Stack dir: ${DEPLOY_HOME}/${APP_ENV}"
  log "Register two repository runners on this host:"
  log "  backend labels: self-hosted,Linux,X64,backend,production"
  log "  frontend labels: self-hosted,Linux,X64,frontend,production"
  log "After registration, install and start both runner services"
}

main "$@"
