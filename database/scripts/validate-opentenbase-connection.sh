#!/usr/bin/env bash

# CLASSIFICATION: local or remote verification, read-only
# Validate that the current DB_* settings can reach an OpenTenBase/PostgreSQL-
# compatible endpoint and that Flyway can inspect the active migration chain.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  source "$ENV_FILE"
  set +a
fi

DB_JDBC_URL="${DB_URL:-}"
DB_USERNAME_VALUE="${DB_USERNAME:-}"
DB_PASSWORD_VALUE="${DB_PASSWORD:-}"
PSQL_BIN="${OTB_PSQL_BIN:-psql}"

if [[ -z "$DB_JDBC_URL" || -z "$DB_USERNAME_VALUE" || -z "$DB_PASSWORD_VALUE" ]]; then
  echo "Missing DB_URL / DB_USERNAME / DB_PASSWORD in $ENV_FILE or current environment." >&2
  exit 1
fi

if ! command -v "$PSQL_BIN" >/dev/null 2>&1; then
  echo "psql client not found. Set OTB_PSQL_BIN or install a compatible psql client." >&2
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

echo "==> Validating DB endpoint"
echo "JDBC URL: $DB_JDBC_URL"
echo "Host: $DB_HOST"
echo "Port: $DB_PORT"
echo "Database: $DB_NAME"
echo "Username: $DB_USERNAME_VALUE"

echo "==> Running connection probe"
"$PSQL_BIN" \
  --host "$DB_HOST" \
  --port "$DB_PORT" \
  --username "$DB_USERNAME_VALUE" \
  --dbname "$DB_NAME" \
  --set ON_ERROR_STOP=1 \
  --command "select version(); select current_database(), current_user;" \
  --command "select now() as server_time;" \
  --command "select count(*) as flyway_history_tables from information_schema.tables where table_schema = 'public' and table_name = 'flyway_schema_history';"

echo "==> Running Flyway info"
(
  cd "$ROOT_DIR"
  DB_URL="$DB_JDBC_URL" \
  DB_USERNAME="$DB_USERNAME_VALUE" \
  DB_PASSWORD="$DB_PASSWORD_VALUE" \
  mvn -q -pl sism-main org.flywaydb:flyway-maven-plugin:9.22.3:info
)

echo "==> Running Flyway validate"
(
  cd "$ROOT_DIR"
  DB_URL="$DB_JDBC_URL" \
  DB_USERNAME="$DB_USERNAME_VALUE" \
  DB_PASSWORD="$DB_PASSWORD_VALUE" \
  mvn -q -pl sism-main org.flywaydb:flyway-maven-plugin:9.22.3:validate
)

echo "OpenTenBase/PostgreSQL-compatible endpoint validation completed."
