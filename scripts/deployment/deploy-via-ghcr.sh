#!/usr/bin/env bash
# ==============================================================================
# SISM 生产部署（GHCR 直拉路径）——本机带宽受限时的主力路径
#
# 背景（2026-11-15 实测）：
#   本机 → GitHub Actions artifact API 下载镜像产物，速度仅 ~8 KB/s
#   （158MB 预计 5.5 小时），download-offline-release.sh 在此带宽下等同挂死。
#   服务器 → ghcr.io 直拉同一镜像 **21 秒**完成。
#   ⇒ 部署主路径改为「服务器直拉 GHCR」，本地只传必要的 compose/env 变更。
#
# 用法：
#   ./deploy-via-ghcr.sh --frontend-sha <40位SHA> [--backend-sha <40位SHA>] \
#                        [--remote root@175.24.139.148] [--stack-dir /opt/sism-stack/production]
#
# 行为：
#   1. 服务器 docker pull 两个镜像（带超时与重试）
#   2. 更新 .env 的 FRONTEND_IMAGE / BACKEND_IMAGE 行（**只改镜像行**）
#   3. docker compose up -d --no-deps <服务>（**不动 postgres**）
#   4. 健康检查 + 域名探活 + 登录冒烟
#   5. 失败即回滚 .env 镜像行并 up 回旧版本
#
# 纪律：执行前必须先立回滚点（见 deploy-rollback-checklist.md）！
# ==============================================================================
set -euo pipefail

BACKEND_REPO="cduestc-openatom-open-source-club/sism-backend"
FRONTEND_REPO="cduestc-openatom-open-source-club/strategic-task-management"

FRONTEND_SHA=""
BACKEND_SHA=""
REMOTE="root@175.24.139.148"
STACK_DIR="/opt/sism-stack/production"
PULL_TIMEOUT=600
HEALTH_WAIT=90

log()  { printf '[deploy-ghcr] %s\n' "$1"; }
fail() { printf '[deploy-ghcr] ERROR: %s\n' "$1" >&2; exit 1; }

usage() {
  cat <<'EOF'
Usage: deploy-via-ghcr.sh --frontend-sha SHA [--backend-sha SHA] [options]

Options:
  --frontend-sha SHA   前端镜像 tag（完整 40 位 commit SHA），必填
  --backend-sha SHA    后端镜像 tag；不传则保持服务器现有后端版本
  --remote USER@HOST   SSH 目标（默认 root@175.24.139.148）
  --stack-dir DIR      服务器 compose 栈目录（默认 /opt/sism-stack/production）
  --pull-timeout SEC   单个镜像拉取超时（默认 600）
  -h, --help           显示帮助
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --frontend-sha) FRONTEND_SHA="$2"; shift 2 ;;
    --backend-sha)  BACKEND_SHA="$2";  shift 2 ;;
    --remote)       REMOTE="$2";       shift 2 ;;
    --stack-dir)    STACK_DIR="$2";    shift 2 ;;
    --pull-timeout) PULL_TIMEOUT="$2"; shift 2 ;;
    -h|--help)      usage; exit 0 ;;
    *) fail "Unknown option: $1" ;;
  esac
done

[[ -n "$FRONTEND_SHA" ]] || { usage; fail "--frontend-sha is required"; }
[[ "$FRONTEND_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "frontend sha must be 40 hex chars"
if [[ -n "$BACKEND_SHA" ]]; then
  [[ "$BACKEND_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "backend sha must be 40 hex chars"
fi

log "目标：${REMOTE}:${STACK_DIR}"
log "前端镜像 tag = ${FRONTEND_SHA}"
log "后端镜像 tag = ${BACKEND_SHA:-（保持不变）}"

# --- 1. 服务器拉镜像（服务器在境内，ghcr 直拉很快；本机拉是 8KB/s 的坑） ---
pull_on_server() {
  local image="$1"
  log "服务器拉取 ${image}（超时 ${PULL_TIMEOUT}s）"
  ssh -o ConnectTimeout=15 "${REMOTE}" \
    "timeout ${PULL_TIMEOUT} docker pull '${image}'" \
    || fail "docker pull failed: ${image}"
}

pull_on_server "ghcr.io/${FRONTEND_REPO}:${FRONTEND_SHA}"
if [[ -n "$BACKEND_SHA" ]]; then
  pull_on_server "ghcr.io/${BACKEND_REPO}:${BACKEND_SHA}"
fi

# --- 2/3. 改 .env 镜像行 + compose up（不动 postgres） ---
log "更新 .env 镜像行并重建服务"
# heredoc 故意不加引号：本地变量（STACK_DIR/镜像 tag/HEALTH_WAIT）在本机展开，
# 转义的 \$(...) 与 \${...} 留到服务器端展开。
# shellcheck disable=SC2087
ssh -o ConnectTimeout=15 "${REMOTE}" bash -s <<REMOTE_SCRIPT
set -euo pipefail
cd "${STACK_DIR}"

OLD_FRONTEND="\$(grep '^FRONTEND_IMAGE=' .env | cut -d= -f2-)"
OLD_BACKEND="\$(grep '^BACKEND_IMAGE=' .env | cut -d= -f2-)"
echo "旧前端镜像: \$OLD_FRONTEND"
echo "旧后端镜像: \$OLD_BACKEND"

cp -a .env ".env.bak-\$(date +%Y%m%d-%H%M%S)"

sed -i 's|^FRONTEND_IMAGE=.*|FRONTEND_IMAGE=ghcr.io/${FRONTEND_REPO}:${FRONTEND_SHA}|' .env
if [[ -n "${BACKEND_SHA}" ]]; then
  sed -i 's|^BACKEND_IMAGE=.*|BACKEND_IMAGE=ghcr.io/${BACKEND_REPO}:${BACKEND_SHA}|' .env
fi

echo "--- 新镜像行 ---"
grep -E '^FRONTEND_IMAGE|^BACKEND_IMAGE' .env

docker compose up -d --no-deps frontend 2>&1 | tail -2
if [[ -n "${BACKEND_SHA}" ]]; then
  docker compose up -d --no-deps backend 2>&1 | tail -2
fi

# 等待 healthy
echo "--- 等待健康 ---"
for i in \$(seq 1 ${HEALTH_WAIT}); do
  st="\$(docker compose ps --format '{{.Name}} {{.Status}}' | grep -E 'frontend|backend' || true)"
  echo "[\$i] \$st"
  if ! echo "\$st" | grep -qE 'starting|unhealthy|created'; then
    if echo "\$st" | grep -q 'healthy'; then
      echo "HEALTHY_OK"
      break
    fi
  fi
  sleep 1
done
REMOTE_SCRIPT

# --- 4. 外部探活 + 登录冒烟 ---
log "探活：域名 + 登录"
curl -s -o /dev/null -w "  https=%{http_code}\n" -m 15 https://sism.blackevil.cn/ \
  || fail "domain check failed"
curl -s -m 15 -X POST https://sism.blackevil.cn/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"zlb_admin","password":"admin123"}' \
  -o /dev/null -w "  login=%{http_code}\n" || fail "login smoke failed"

log "完成。若任一步失败：用服务器上的 .env.bak-* 恢复镜像行后 docker compose up -d --no-deps <svc> 回滚。"
