#!/usr/bin/env bash

# CLASSIFICATION: local only
# 用途：发布一键化——develop 合并到 main（触发 CI + 镜像构建），可选在线部署。
#
# 流程：
#   ① 校验（前端 lint/type/arch/build；后端 package）
#   ② develop 合并到 main 并推送（触发 CI + 镜像构建，不部署）
#   ③ 等待两仓构建 success
#   ④ 可选：触发在线部署（workflow_dispatch test-server）——需显式 --deploy
#
# 用法：
#   ./scripts/dev/release.sh              # 合并 main + 等构建（不部署）
#   ./scripts/dev/release.sh --deploy     # 构建成功后继续触发在线部署并监控
#   ./scripts/dev/release.sh --skip-verify  # 跳过本地校验（不推荐）

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FRONTEND_DIR="$ROOT_DIR/frontend"
BACKEND_DIR="$ROOT_DIR/backend"
ORG="${GITHUB_ORG:-CDUESTC-OpenAtom-Open-Source-Club}"

DEPLOY=false
SKIP_VERIFY=false
for arg in "$@"; do
  case "$arg" in
    --deploy)      DEPLOY=true ;;
    --skip-verify) SKIP_VERIFY=true ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

log()  { printf '[release] %s\n' "$1"; }
fail() { printf '[release] ERROR: %s\n' "$1" >&2; exit 1; }

# ── ① 本地校验 ──
if [[ "$SKIP_VERIFY" == false ]]; then
  log "前端校验（lint/type/arch/build）…"
  ( cd "$FRONTEND_DIR"
    npm run lint:check > /dev/null 2>&1 || fail "前端 lint 未通过"
    npm run type-check > /dev/null 2>&1 || fail "前端 type-check 未通过"
    npm run arch:check > /dev/null 2>&1 || fail "前端 arch 未通过"
    npm run build > /dev/null 2>&1 || fail "前端 build 未通过"
  )
  log "前端校验通过"

  log "后端校验（package）…"
  ( cd "$BACKEND_DIR"
    ./mvnw -o -ntp -DskipTests package > /dev/null 2>&1 || fail "后端 package 未通过"
  )
  log "后端校验通过"
fi

# ── ② 推送 develop 与 main ──
export https_proxy="${https_proxy:-http://127.0.0.1:7897}" \
       http_proxy="${http_proxy:-http://127.0.0.1:7897}"

for repo_dir in "$FRONTEND_DIR" "$BACKEND_DIR"; do
  cd "$repo_dir"
  repo_name=$(basename "$repo_dir")
  # 工作树必须干净
  [[ -z "$(git status --porcelain)" ]] || fail "$repo_name 工作树有未提交改动"
  # 推 develop（若本地在 develop）
  if [[ "$(git branch --show-current)" == "develop" ]]; then
    git push origin develop 2>&1 | tail -1
  fi
done

# frontend: main ← develop
cd "$FRONTEND_DIR"
git checkout main --quiet
git merge --no-ff develop -m "chore: release——merge develop into main" 2>&1 | tail -1
git push origin main 2>&1 | tail -1
FE_SHA=$(git rev-parse --short origin/main)
log "frontend main → $FE_SHA"

# backend: main ← develop
cd "$BACKEND_DIR"
git checkout main --quiet
git merge --no-ff develop -m "chore: release——merge develop into main" 2>&1 | tail -1
git push origin main 2>&1 | tail -1
BE_SHA=$(git rev-parse --short origin/main)
log "backend main → $BE_SHA"

# ── ③ 等待两仓构建 success ──
log "等待 GitHub Actions 构建（最多 20 分钟）…"
export https_proxy="${https_proxy:-http://127.0.0.1:7897}" \
       http_proxy="${http_proxy:-http://127.0.0.1:7897}"

wait_build() {
  local repo="$1" sha="$2" label="$3"
  local run_id
  for _ in $(seq 1 10); do sleep 10
    run_id=$(gh run list -R "$ORG/$repo" --limit 5 --json databaseId,headSha,status,event \
      --jq "[.[] | select(.headSha==\"$sha\" and .event==\"push\")][0].databaseId" 2>/dev/null || true)
    [[ -n "$run_id" ]] && { echo "$run_id"; return 0; }
  done
  echo ""
}

FE_BUILD_RUN=$(wait_build "strategic-task-management" "$FE_SHA" "frontend")
BE_BUILD_RUN=$(wait_build "sism-backend" "$BE_SHA" "backend")
log "构建 run：frontend=$FE_BUILD_RUN backend=$BE_BUILD_RUN"

for entry in "strategic-task-management $FE_BUILD_RUN frontend" "sism-backend $BE_BUILD_RUN backend"; do
  set -- $entry
  repo="$1"; run_id="$2"; label="$3"
  [[ -z "$run_id" ]] && { log "$label: 无构建 run，跳过等待"; continue; }
  for _ in $(seq 1 40); do
    sleep 30
    st=$(gh run view "$run_id" -R "$ORG/$repo" --json status,conclusion --jq '"\(.status)/\(.conclusion // "-")"' 2>/dev/null || echo "?")
    [[ "$st" == completed/* ]] && break
  done
  concl=$(gh run view "$run_id" -R "$ORG/$repo" --json conclusion --jq .conclusion 2>/dev/null || echo '?')
  [[ "$concl" == "success" ]] || fail "$label 构建未成功（$concl）——镜像未产出，检查 Actions 日志"
  log "$label 构建成功"
done

log "两仓镜像构建完成，GHCR 已产出最新镜像（tag 含 SHA 与 main）"

# ── ④ 可选：在线部署 ──
if [[ "$DEPLOY" == true ]]; then
  log "触发在线部署（workflow_dispatch test-server full-stack）…"
  gh workflow run build-and-push-image.yml -R "$ORG/sism-backend" \
    --ref main \
    -f delivery_target=test-server \
    -f deploy_mode=full-stack \
    -f db_mode=internal \
    -f target_env=production
  log "部署已触发——到 Actions 页面监控 Deploy Stack 进度"
  log "（本脚本不等部署完成；部署失败时 install-offline-release.sh 会自动回滚）"
else
  log "未触发在线部署。GHCR 最新镜像可用于离线三步："
  log "  ./scripts/deployment/download-offline-release.sh → upload → install"
fi

log "发布流程完成 ✅"
