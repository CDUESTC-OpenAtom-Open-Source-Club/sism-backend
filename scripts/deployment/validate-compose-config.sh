#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ENV_FILE="$(mktemp)"
trap 'rm -f "${ENV_FILE}"' EXIT

cat > "${ENV_FILE}" <<'ENVVARS'
COMPOSE_PROJECT_NAME=sism-portability-test
POSTGRES_DB=sism_test
POSTGRES_USER=sism_test
POSTGRES_PASSWORD=test-password
DB_URL=jdbc:postgresql://127.0.0.1:5432/sism_test
DB_USERNAME=sism_test
DB_PASSWORD=test-password
JWT_SECRET=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
ALLOWED_ORIGINS=https://example.test
SPRING_FLYWAY_ENABLED=true
BACKEND_IMAGE=example.invalid/sism-backend:test
FRONTEND_IMAGE=example.invalid/strategic-task-management:test
BACKEND_PORT=18080
FRONTEND_PORT=18081
ENVVARS

for compose_file in \
  "${REPO_ROOT}/deploy/compose/docker-compose.yml" \
  "${REPO_ROOT}/deploy/compose/docker-compose.external-db.yml"; do
  docker compose --env-file "${ENV_FILE}" -f "${compose_file}" config --quiet
done

grep -Fq "127.0.0.1:\${BACKEND_PORT}:8080" \
  "${REPO_ROOT}/deploy/compose/docker-compose.yml"
grep -Fq "127.0.0.1:\${FRONTEND_PORT}:80" \
  "${REPO_ROOT}/deploy/compose/docker-compose.yml"
grep -Fq "127.0.0.1:\${BACKEND_PORT}:8080" \
  "${REPO_ROOT}/deploy/compose/docker-compose.external-db.yml"
grep -Fq "127.0.0.1:\${FRONTEND_PORT}:80" \
  "${REPO_ROOT}/deploy/compose/docker-compose.external-db.yml"

printf '[compose-validation] COMPOSE_CONFIG_OK\n'
