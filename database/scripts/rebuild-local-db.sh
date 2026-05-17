#!/usr/bin/env bash

# CLASSIFICATION: local only, destructive
# Rebuild a PostgreSQL-wire-compatible database from the active Flyway migration
# chain, then reload the clean seed baseline.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"
SCRIPT_DIR="$ROOT_DIR/database/scripts"

if [[ "${1:-}" != "--yes" ]]; then
  echo "Usage: $0 --yes" >&2
  echo "This script is destructive. It drops and recreates the local database defined in $ENV_FILE." >&2
  exit 1
fi

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

if ! command -v psql >/dev/null 2>&1 || ! command -v dropdb >/dev/null 2>&1 || ! command -v createdb >/dev/null 2>&1 || ! command -v pg_dump >/dev/null 2>&1; then
  echo "psql, dropdb, createdb, and pg_dump are required." >&2
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

BACKUP_STAMP="$(date +%Y%m%d_%H%M%S)"
BACKUP_FILE="/tmp/${DB_NAME}_before_rebuild_${BACKUP_STAMP}.sql.gz"
FLYWAY_LOCATIONS="filesystem:sism-main/src/main/resources/db/migration,filesystem:sism-analytics/src/main/resources/db/migration"

echo "==> Backing up current database to $BACKUP_FILE"
pg_dump \
  --host "$DB_HOST" \
  --port "$DB_PORT" \
  --username "$DB_USERNAME_VALUE" \
  "$DB_NAME" | gzip > "$BACKUP_FILE"

echo "==> Dropping database $DB_NAME"
psql \
  --host "$DB_HOST" \
  --port "$DB_PORT" \
  --username "$DB_USERNAME_VALUE" \
  --dbname postgres \
  --command "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$DB_NAME' AND pid <> pg_backend_pid();" >/dev/null

dropdb \
  --host "$DB_HOST" \
  --port "$DB_PORT" \
  --username "$DB_USERNAME_VALUE" \
  --if-exists \
  "$DB_NAME"

echo "==> Creating database $DB_NAME"
createdb \
  --host "$DB_HOST" \
  --port "$DB_PORT" \
  --username "$DB_USERNAME_VALUE" \
  "$DB_NAME"

echo "==> Applying active Flyway migrations"
(
  cd "$ROOT_DIR"
  DB_URL="$DB_JDBC_URL" \
  DB_USERNAME="$DB_USERNAME_VALUE" \
  DB_PASSWORD="$DB_PASSWORD_VALUE" \
  mvn -q org.flywaydb:flyway-maven-plugin:9.22.3:migrate \
    -Dflyway.locations="$FLYWAY_LOCATIONS"
)

echo "==> Reloading clean seeds"
"$SCRIPT_DIR/reset-clean-seeds.sh"

echo "==> Verifying seed/schema alignment"
(
  cd "$ROOT_DIR"
  node ./database/scripts/check-seed-schema-drift.js
)

echo "Local database rebuild completed."
echo "Backup saved at: $BACKUP_FILE"
