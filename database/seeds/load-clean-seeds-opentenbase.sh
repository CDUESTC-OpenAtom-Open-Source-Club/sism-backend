#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/otb-seeds.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

CONN_URI="${1:-postgresql://opentenbase@127.0.0.1:33004/postgres?sslmode=disable}"
PSQL_BIN="${PSQL_BIN:-/opt/homebrew/opt/libpq/bin/psql}"

cp "$ROOT_DIR"/*.sql "$TMP_DIR"/

# OpenTenBase clean-load path truncates the target tables first, so ON CONFLICT
# clauses are unnecessary and some variants are not parser-compatible.
while IFS= read -r -d '' file; do
  perl -0pi -e 's/\nON CONFLICT[\s\S]*?;\n/;\n/g' "$file"
done < <(find "$TMP_DIR" -maxdepth 1 -type f -name '*.sql' -print0)

# progress_report-data.sql is an empty-row placeholder seed. The shared
# PostgreSQL variant still references a drifted column set, so skip it entirely
# in the OpenTenBase temp copy to avoid touching the shared seed file.
: > "$TMP_DIR/progress_report-data.sql"

# The shared reset script finishes with a broad setval() resync block. That is
# useful for PostgreSQL, but on OpenTenBase some replication-table sequence
# writes are rejected. Keep the shared script intact and remove only the temp
# copy's final DO block.
perl -0pi -e 's/\nDO \$\$[\s\S]*?END\n\$\$;\s*\z/\n/sg' \
  "$TMP_DIR/reset-and-load-clean-seeds-opentenbase.sql"

cd "$TMP_DIR"
"$PSQL_BIN" "$CONN_URI" -v ON_ERROR_STOP=1 -f reset-and-load-clean-seeds-opentenbase.sql
