#!/usr/bin/env bash

# CLASSIFICATION: local only
# 用途：本地开发一键启动（数据库检查 → 可选灌种子 → 后端 → 前端）
#
# 种子是**可选**的：
#   - 选择灌：本地库会有完整测试账号与计划容器，适合联调
#   - 选择不灌：Flyway 仍会自动建表，只是没有业务数据（需自行造数）
#
# 用法：
#   ./scripts/dev/up-local.sh              # 交互式（会询问是否灌种子）
#   ./scripts/dev/up-local.sh --no-seed    # 跳过种子
#   ./scripts/dev/up-local.sh --seed       # 强制灌种子
#   ./scripts/dev/up-local.sh --stop       # 停止本脚本启动的后端/前端

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
FRONTEND_DIR="$ROOT_DIR/frontend"
LOG_DIR="$ROOT_DIR/.dev-logs"
PID_FILE="$LOG_DIR/dev-processes.pid"

SEED_MODE="ask"   # ask | yes | no
ACTION="start"

for arg in "$@"; do
  case "$arg" in
    --seed)    SEED_MODE="yes" ;;
    --no-seed) SEED_MODE="no" ;;
    --stop)    ACTION="stop" ;;
    -h|--help)
      sed -n '3,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *)
      echo "Unknown option: $arg" >&2
      exit 2 ;;
  esac
done

log()  { printf '[dev-up] %s\n' "$1"; }
fail() { printf '[dev-up] ERROR: %s\n' "$1" >&2; exit 1; }

stop_processes() {
  if [[ -f "$PID_FILE" ]]; then
    while read -r pid; do
      [[ -z "$pid" ]] && continue
      if kill -0 "$pid" 2>/dev/null; then
        log "停止进程 $pid"
        kill "$pid" 2>/dev/null || true
      fi
    done < "$PID_FILE"
    rm -f "$PID_FILE"
    log "已停止"
  else
    log "没有记录到由本脚本启动的进程"
  fi
}

if [[ "$ACTION" == "stop" ]]; then
  stop_processes
  exit 0
fi

command -v java >/dev/null 2>&1 || fail "未找到 java，请配置 JAVA_HOME"
command -v node >/dev/null 2>&1 || fail "未找到 node，请配置 Node 22+"
[[ -d "$FRONTEND_DIR/node_modules" ]] || fail "frontend 依赖未安装，请先在 frontend 目录执行 npm install"

mkdir -p "$LOG_DIR"

check_database() {
  if [[ -f "$BACKEND_DIR/.env" ]]; then
    ( set -a; source "$BACKEND_DIR/.env"; set +a )
  fi
  local url="${DB_URL:-}"
  if [[ -z "$url" ]]; then
    log "未找到 backend/.env 中的 DB_URL，跳过数据库连通性检查"
    return 0
  fi
  local conn="${url#jdbc:postgresql://}"
  local hostport="${conn%%/*}"
  local host="${hostport%%:*}"
  local port="${hostport##*:}"
  [[ "$host" == "$port" ]] && port=5432
  if (echo >"/dev/tcp/$host/$port") 2>/dev/null; then
    log "数据库可达：$host:$port"
    return 0
  fi
  log "数据库不可达：$host:$port"
  return 1
}

if ! check_database; then
  log "请先启动本地 PostgreSQL（compose: cd backend/deploy/compose && docker compose up -d postgres）"
  exit 1
fi

run_seed() {
  log "灌入种子数据（清空业务表并重载 clean seeds）…"
  ( cd "$BACKEND_DIR" && bash database/scripts/reset-clean-seeds.sh )
  log "种子灌入完成"
}

case "$SEED_MODE" in
  yes)
    run_seed
    ;;
  no)
    log "按参数跳过种子灌入（Flyway 仍会自动建表，库中无业务数据）"
    ;;
  ask)
    if [[ ! -t 0 ]]; then
      log "非交互环境，默认跳过种子（如需灌种子请加 --seed）"
    else
      printf '[dev-up] 是否灌入种子数据？[y/N] ' >&2
      read -r answer
      case "${answer:-n}" in
        [yY]|[yY][eE][sS]) run_seed ;;
        *) log "跳过种子灌入" ;;
      esac
    fi
    ;;
esac

log "启动后端（Spring Boot，日志：$LOG_DIR/backend.log）"
(
  cd "$BACKEND_DIR"
  ./mvnw -q spring-boot:run
) > "$LOG_DIR/backend.log" 2>&1 &
BACKEND_PID=$!

log "启动前端（Vite dev，日志：$LOG_DIR/frontend.log）"
(
  cd "$FRONTEND_DIR"
  npm run dev
) > "$LOG_DIR/frontend.log" 2>&1 &
FRONTEND_PID=$!

{
  echo "$BACKEND_PID"
  echo "$FRONTEND_PID"
} > "$PID_FILE"

log "等待服务就绪（最多 120 秒）…"
for _ in $(seq 1 60); do
  backend_ok=0
  frontend_ok=0
  if curl -fsS http://127.0.0.1:8080/api/v1/actuator/health >/dev/null 2>&1; then backend_ok=1; fi
  if curl -fsS http://localhost:3500/ >/dev/null 2>&1; then frontend_ok=1; fi
  if [[ "$backend_ok" == "1" && "$frontend_ok" == "1" ]]; then
    log "后端与前端均已就绪"
    break
  fi
  sleep 2
done

cat <<EOF

────────────────────────────────────────────
  前端：      http://localhost:3500
  后端 API：  http://localhost:8080
  日志：      $LOG_DIR/backend.log
              $LOG_DIR/frontend.log
  停止：      ./scripts/dev/up-local.sh --stop
────────────────────────────────────────────
EOF
