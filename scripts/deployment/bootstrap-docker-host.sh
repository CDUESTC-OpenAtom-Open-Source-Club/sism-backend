#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root." >&2
  exit 1
fi

DEPLOY_USER="${1:-sism-runner}"
DEPLOY_HOME="/opt/sism-stack"
APP_ENV="${APP_ENV:-production}"
INSTALL_NGINX="${INSTALL_NGINX:-false}"

log() {
  printf '[bootstrap] %s\n' "$1"
}

ensure_user() {
  if id "${DEPLOY_USER}" >/dev/null 2>&1; then
    log "User ${DEPLOY_USER} already exists"
    return
  fi
  useradd -m -s /bin/bash "${DEPLOY_USER}"
  log "Created user ${DEPLOY_USER}"
}

install_docker() {
  if command -v docker >/dev/null 2>&1; then
    log "Docker already installed: $(docker --version)"
  else
    log "Installing Docker"
    curl -fsSL https://get.docker.com | sh
  fi

  if docker compose version >/dev/null 2>&1; then
    log "Docker Compose already available: $(docker compose version)"
  else
    log "Docker Compose plugin missing after install"
    exit 1
  fi
}

install_nginx() {
  if [[ "${INSTALL_NGINX}" != "true" ]]; then
    return
  fi

  if command -v apt-get >/dev/null 2>&1; then
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y nginx
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y nginx
  elif command -v yum >/dev/null 2>&1; then
    yum install -y nginx
  else
    log "Unsupported package manager for nginx install"
    exit 1
  fi

  systemctl enable --now nginx
  log "Nginx installed and enabled"
}

prepare_dirs() {
  mkdir -p "${DEPLOY_HOME}/${APP_ENV}/nginx"
  chown -R "${DEPLOY_USER}:${DEPLOY_USER}" "${DEPLOY_HOME}"
  chmod 755 "${DEPLOY_HOME}" "${DEPLOY_HOME}/${APP_ENV}" "${DEPLOY_HOME}/${APP_ENV}/nginx"
  log "Prepared ${DEPLOY_HOME}/${APP_ENV}"
}

configure_user() {
  usermod -aG docker "${DEPLOY_USER}"
  log "Added ${DEPLOY_USER} to docker group"
}

main() {
  ensure_user
  install_docker
  install_nginx
  prepare_dirs
  configure_user

  log "Bootstrap complete"
  log "Deploy user: ${DEPLOY_USER}"
  log "Stack dir: ${DEPLOY_HOME}/${APP_ENV}"
  log "Next step: configure GitHub secrets SERVER_HOST / SERVER_USER / SERVER_SSH_KEY"
}

main "$@"
