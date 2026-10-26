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

# 2026-10-26：优先使用 psql；本机未安装 psql 时回退到 JDBC 执行器
# （database/scripts/jdbc/Exec.java + 项目自带 postgresql 驱动），保证「无 psql 也能灌种子」。
PSQL_BIN="$(command -v psql || true)"

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

# run_sql <sql-file>：按可用执行器跑单个 SQL 文件
run_sql() {
  local sql_file="$1"
  if [[ -n "$PSQL_BIN" ]]; then
    "$PSQL_BIN" \
      --host "$DB_HOST" \
      --port "$DB_PORT" \
      --username "$DB_USERNAME_VALUE" \
      --dbname "$DB_NAME" \
      --file "$sql_file"
    return
  fi
  run_sql_via_jdbc "$sql_file"
}

# run_sql_via_jdbc <sql-file>：无 psql 时用 JDBC 执行器。
# 注意：JDBC 执行器不支持 psql 元命令（\i / \set），因此先把 reset 脚本里的
# \i 引用就地展开为单文件再执行（等价于 psql 的 include 语义）。
run_sql_via_jdbc() {
  local sql_file="$1"
  local java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  if [[ -z "$java_bin" || ! -x "$java_bin" ]]; then
    java_bin="$(command -v java || true)"
  fi
  if [[ -z "$java_bin" ]]; then
    echo "Neither psql nor java is available; cannot execute $sql_file" >&2
    exit 1
  fi

  local driver_jar
  driver_jar="$(find "${HOME}/.m2/repository/org/postgresql/postgresql" -name 'postgresql-*.jar' 2>/dev/null \
    | grep -v sources | sort | tail -1 || true)"
  if [[ -z "$driver_jar" ]]; then
    echo "PostgreSQL JDBC driver not found under ~/.m2/repository/org/postgresql/postgresql" >&2
    exit 1
  fi

  local jdbc_url="${DB_JDBC_URL}"
  local prepared="$sql_file"
  if grep -q '^\\i ' "$sql_file"; then
    # 展开 include：把 \i <相对路径> 替换为对应文件内容（相对 seed 目录解析）
    prepared="$(mktemp -t sism-seed-XXXXXX.sql)"
    python3 - "$sql_file" "$prepared" <<'PYEOF_INNER'
import os, re, sys
src_path, out_path = sys.argv[1], sys.argv[2]
base = os.path.dirname(os.path.abspath(src_path))
out = []
with open(src_path, encoding='utf-8') as fh:
    for line in fh:
        m = re.match(r'^\\i\s+(.+?)\s*$', line)
        if m:
            rel = m.group(1).strip().strip('"')
            target = rel if os.path.isabs(rel) else os.path.join(base, rel)
            with open(target, encoding='utf-8') as tf:
                included = tf.read()
            # 保证被包含文件以换行结尾，避免与后续内容黏成一行
            out.append(included)
            if not included.endswith('\n'):
                out.append('\n')
        else:
            out.append(line)
with open(out_path, 'w', encoding='utf-8') as fh:
    fh.write(''.join(out))
PYEOF_INNER
  fi

  "$java_bin" -cp "$driver_jar:$SCRIPT_DIR/jdbc" Exec "$prepared" "$jdbc_url" "$DB_USERNAME_VALUE" "$DB_PASSWORD_VALUE"
  if [[ "$prepared" != "$sql_file" ]]; then
    rm -f "$prepared"
  fi
}

echo "==> Bootstrapping local seed support columns"
run_sql "$ROOT_DIR/database/scripts/bootstrap-local-seed-support.sql"

echo "==> Resetting database and loading clean seeds"
run_sql "$SEED_DIR/reset-and-load-clean-seeds.sql"

echo "==> Validating clean seed baseline"
run_sql "$SCRIPT_DIR/validate-clean-seeds.sql"

echo "Clean seed reset completed."
