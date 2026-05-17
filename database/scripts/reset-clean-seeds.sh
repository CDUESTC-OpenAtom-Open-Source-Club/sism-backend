#!/usr/bin/env bash

# CLASSIFICATION: local only, destructive
# RISK: resets business tables and reloads clean seeds
#
# WARNING:
# This script is destructive. It resets business tables and reloads clean seeds.
# Use it only for local or explicitly approved test databases.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SEED_DIR="$ROOT_DIR/database/seeds"
SCRIPT_DIR="$ROOT_DIR/database/scripts"
ENV_FILE="$ROOT_DIR/.env"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  source "$ENV_FILE"
  set +a
fi

DB_JDBC_URL="${DB_URL:-}"
DB_USERNAME_VALUE="${DB_USERNAME:-}"
DB_PASSWORD_VALUE="${DB_PASSWORD:-}"

if [[ -z "$DB_JDBC_URL" || -z "$DB_USERNAME_VALUE" || -z "$DB_PASSWORD_VALUE" ]]; then
  echo "Missing DB_URL / DB_USERNAME / DB_PASSWORD in $ENV_FILE or current environment." >&2
  exit 1
fi

if ! command -v psql >/dev/null 2>&1; then
  echo "psql is required but was not found in PATH." >&2
  exit 1
fi

JDBC_PREFIX="jdbc:postgresql://"
if [[ "$DB_JDBC_URL" != ${JDBC_PREFIX}* ]]; then
  echo "Only PostgreSQL-wire JDBC URLs are supported. Current DB_URL: $DB_JDBC_URL" >&2
  exit 1
fi

DB_CONN="${DB_JDBC_URL#$JDBC_PREFIX}"
DB_HOSTPORT="${DB_CONN%%/*}"
DB_NAME_AND_QUERY="${DB_CONN#*/}"
DB_NAME="${DB_NAME_AND_QUERY%%\?*}"
DB_HOST="${DB_HOSTPORT%%:*}"
DB_PORT="${DB_HOSTPORT##*:}"

if [[ "$DB_HOST" == "$DB_PORT" ]]; then
  DB_PORT="5432"
fi

export PGPASSWORD="$DB_PASSWORD_VALUE"

echo "==> Bootstrapping local seed support columns"
psql \
  --host "$DB_HOST" \
  --port "$DB_PORT" \
  --username "$DB_USERNAME_VALUE" \
  --dbname "$DB_NAME" \
  --file "$ROOT_DIR/database/scripts/bootstrap-local-seed-support.sql"

echo "==> Resetting database and loading clean seeds"
(cd "$SEED_DIR" && psql \
  --host "$DB_HOST" \
  --port "$DB_PORT" \
  --username "$DB_USERNAME_VALUE" \
  --dbname "$DB_NAME" \
  --file "$SEED_DIR/reset-and-load-clean-seeds.sql")

echo "==> Validating clean seed baseline"
psql \
  --host "$DB_HOST" \
  --port "$DB_PORT" \
  --username "$DB_USERNAME_VALUE" \
  --dbname "$DB_NAME" \
  --file "$SCRIPT_DIR/validate-clean-seeds.sql"

echo "Clean seed reset completed."
