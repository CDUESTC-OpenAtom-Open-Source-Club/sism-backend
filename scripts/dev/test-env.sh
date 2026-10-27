#!/usr/bin/env bash

# CLASSIFICATION: local only
# 用途：多账号测试环境一键准备（数据库重置 → 后端 → 9 个测试浏览器登录）
#
# 1. （可选）重置数据库并灌 clean seeds（--reset 或交互询问）
# 2. 重启后端（加载最新 jar，避免旧进程 NoClassDefFoundError）
# 3. 关闭并重开 9 个测试 Chrome（独立 profile + CDP 调试端口）并自动登录
# 4. 输出窗口-账号对照表
#
# 前置：后端 jar 已构建（./mvnw -DskipTests package）；前端 dev server 已在 3500 跑
#       （可用 up-local.sh 起）；本机 Google Chrome。
#
# 用法：
#   ./scripts/dev/test-env.sh             # 交互式（询问是否重置数据库）
#   ./scripts/dev/test-env.sh --reset     # 强制重置数据库
#   ./scripts/dev/test-env.sh --keep-db   # 保留数据库，只重启后端+浏览器
#   ./scripts/dev/test-env.sh --browsers-only  # 只重开浏览器并登录（后端不动）
#   ./scripts/dev/test-env.sh --stop      # 关闭全部测试浏览器
#
# 测试窗口-账号对照：
#   9231 zlb_admin        战略发展部·填报人
#   9232 zlb_final1       战略发展部·负责人
#   9233 zlb_vp1          战略发展部·校领导
#   9234 jiaowu_report    教务处·填报人
#   9235 jiaowu_audit1    教务处·审核人
#   9236 jiaowu_leader    教务处·领导
#   9237 hangkong_report  航空学院·填报人
#   9238 hangkong_audit1  航空学院·审核人
#   9239 hangkong_leader  航空学院·领导

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
ROOT_DIR="$(cd "$BACKEND_DIR/.." && pwd)"
FRONTEND_DIR="$ROOT_DIR/frontend"
PROFILE_ROOT="${TEST_PROFILE_ROOT:-$HOME/chrome-test-profiles}"
PASSWORD="${TEST_PASSWORD:-admin123}"

SEED_MODE="ask"     # ask | yes | no
BROWSERS_ONLY=false
ACTION="start"

for arg in "$@"; do
  case "$arg" in
    --reset)        SEED_MODE="yes" ;;
    --keep-db)      SEED_MODE="no" ;;
    --browsers-only) BROWSERS_ONLY=true ;;
    --stop)         ACTION="stop" ;;
    -h|--help)
      sed -n '3,32p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

log()  { printf '[test-env] %s\n' "$1"; }
fail() { printf '[test-env] ERROR: %s\n' "$1" >&2; exit 1; }

# ── 窗口定义：CDP端口|profile名|账号|角色标签|落地页|窗口x|窗口y ──
WINDOWS=(
  "9231|zlb_admin|zlb_admin|战略发展部·填报人|/strategic-tasks|0|25"
  "9232|zlb_final1|zlb_final1|战略发展部·负责人|/strategic-tasks|280|45"
  "9233|zlb_vp1|zlb_vp1|战略发展部·校领导|/strategic-tasks|560|65"
  "9234|jiaowu_report|jiaowu_report|教务处·填报人|/indicators|840|85"
  "9235|jiaowu_audit1|jiaowu_audit1|教务处·审核人|/dashboard|0|470"
  "9236|jiaowu_leader|jiaowu_leader|教务处·领导|/dashboard|280|490"
  "9237|hangkong_report|hangkong_report|航空学院·填报人|/indicators|560|510"
  "9238|hangkong_audit1|hangkong_audit1|航空学院·审核人|/dashboard|840|530"
  "9239|hangkong_leader|hangkong_leader|航空学院·领导|/dashboard|1120|550"
)

stop_browsers() {
  pkill -f "remote-debugging-port=92" 2>/dev/null || true
  sleep 3
  pkill -9 -f "remote-debugging-port=92" 2>/dev/null || true
  # macOS Chrome 单例关闭需要时间；不等够会导致后续 open 复用垂死实例而全部失败
  sleep 5
  log "测试浏览器已全部关闭"
}

if [[ "$ACTION" == "stop" ]]; then
  stop_browsers
  exit 0
fi

# ── 0. 前置检查 ──
[[ -d "$FRONTEND_DIR/node_modules" ]] || fail "frontend 依赖未安装（先 npm install）"
[[ -x "$(command -v node)" ]] || fail "未找到 node"
if [[ "$BROWSERS_ONLY" == false ]]; then
  [[ -f "$BACKEND_DIR/sism-main/target/sism-main-1.2.0.jar" ]] || fail "后端 jar 未构建（先 ./mvnw -DskipTests package）"
fi

# ── 1. 数据库（可选重置）──
if [[ "$BROWSERS_ONLY" == false ]]; then
  do_seed=false
  case "$SEED_MODE" in
    yes) do_seed=true ;;
    no)  log "保留现有数据库数据" ;;
    ask)
      if [[ -t 0 ]]; then
        printf '[test-env] 是否重置数据库并灌种子（清空业务表）？[y/N] ' >&2
        read -r answer
        [[ "${answer:-n}" =~ ^[yY] ]] && do_seed=true || log "跳过数据库重置"
      else
        log "非交互环境，默认跳过数据库重置（--reset 强制）"
      fi
      ;;
  esac
  if [[ "$do_seed" == true ]]; then
    log "重置数据库并灌种子…"
    ( cd "$BACKEND_DIR" && bash database/scripts/reset-clean-seeds.sh )
  fi
fi

# ── 2. 后端重启（杀旧进程，加载最新 jar）──
if [[ "$BROWSERS_ONLY" == false ]]; then
  log "重启后端（加载最新 jar）…"
  kill $(lsof -ti :8080) 2>/dev/null || true
  sleep 2
  (
    cd "$BACKEND_DIR"
    if [[ -x "${JAVA_HOME:+$JAVA_HOME/bin/}java" ]]; then
      "${JAVA_HOME}/bin/java" -Xms512m -Xmx1024m -jar sism-main/target/sism-main-1.2.0.jar
    else
      java -Xms512m -Xmx1024m -jar sism-main/target/sism-main-1.2.0.jar
    fi
  ) > /tmp/sism-backend-test.log 2>&1 &
  for _ in $(seq 1 40); do
    sleep 3
    curl -fsS http://127.0.0.1:8080/api/v1/actuator/health >/dev/null 2>&1 && break
  done
  curl -fsS http://127.0.0.1:8080/api/v1/actuator/health >/dev/null 2>&1 \
    || fail "后端健康检查未通过（日志：/tmp/sism-backend-test.log）"
  log "后端就绪（8080）"
fi

# ── 3. 前端 dev server 检查 ──
if ! curl -fsS http://localhost:3500/ >/dev/null 2>&1; then
  fail "前端 dev server（3500）未运行——先执行 backend/scripts/dev/up-local.sh 或手动 npm run dev"
fi
log "前端 dev server 就绪（3500）"

# ── 4. 浏览器：关旧开新 ──
log "关闭旧测试浏览器…"
stop_browsers
sleep 1
mkdir -p "$PROFILE_ROOT"

login_window() {
  local port="$1" profile="$2" user="$3" label="$4" land="$5" wx="$6" wy="$7"
  rm -f "$PROFILE_ROOT/$profile/SingletonLock" 2>/dev/null || true
  # CDP 端口就绪重试：open 在 Chrome 单例协调时可能静默失败，最多重开 3 次
  local ok=false
  for attempt in 1 2 3; do
    open -na "Google Chrome" --args \
      --remote-debugging-port="$port" \
      --user-data-dir="$PROFILE_ROOT/$profile" \
      --window-size=1100,850 --window-position="$wx,$wy" \
      "http://localhost:3500/login"
    for _ in $(seq 1 20); do
      sleep 1
      if nc -z 127.0.0.1 "$port" 2>/dev/null; then ok=true; break; fi
    done
    [[ "$ok" == true ]] && break
    log "  $label: 第 $attempt 次 CDP 未就绪，重试…"
  done
  [[ "$ok" == true ]] || { log "  $label: CDP 启动失败（$port）"; return 1; }
}

login_node() {
  # 用 Playwright CDP 在指定窗口完成登录并跳落地页
  node --input-type=module -e "
import { createRequire } from 'node:module'
const require2 = createRequire('$FRONTEND_DIR/node_modules/playwright/index.js')
const { chromium } = require2('playwright-core')
const APP = 'http://localhost:3500'
const [port, user, land] = process.argv.slice(2)
let browser = null
for (let i = 1; i <= 20 && !browser; i++) {
  try { browser = await chromium.connectOverCDP('http://127.0.0.1:' + port) }
  catch { await new Promise(res => setTimeout(res, 3000)) }
}
if (!browser) { console.log('  CDP 失败: ' + port); process.exit(0) }
const ctx = browser.contexts()[0]
const page = ctx.pages()[0] || await ctx.newPage()
await page.evaluate(() => localStorage.clear()).catch(() => {})
await page.goto(APP + '/login', { waitUntil: 'domcontentloaded' })
await page.waitForSelector('input[placeholder*=\"用户名\"]', { timeout: 30000 })
await page.evaluate((u) => {
  const setV = (el, v) => {
    const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
    s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true }))
  }
  setV(document.querySelector('input[placeholder*=\"用户名\"]'), u)
  setV(document.querySelector('input[type=\"password\"]'), '$PASSWORD')
}, user)
await page.evaluate(() => {
  [...document.querySelectorAll('button')].find(x => x.textContent.replace(/\s/g, '').includes('登录'))?.click()
})
await page.waitForTimeout(7000)
await page.goto(APP + land, { waitUntil: 'domcontentloaded' }).catch(() => {})
await page.waitForTimeout(3500)
const logged = await page.evaluate(() => {
  try { return JSON.parse(localStorage.getItem('currentUser') || '{}').username || '' } catch { return '' }
})
console.log((logged === user ? '  ✓ ' : '  ✗ ') + user + ' → ' + land)
process.exit(0)
" "$port" "$user" "$land"
}

log "打开并登录 9 个测试窗口…"
for row in "${WINDOWS[@]}"; do
  IFS='|' read -r port profile user label land wx wy <<< "$row"
  login_window "$port" "$profile" "$user" "$label" "$land" "$wx" "$wy" || true
  login_node "$port" "$user" "$land" || true
done

cat <<EOF

────────────────────────────────────────────
  测试环境就绪！窗口-账号对照：
EOF
for row in "${WINDOWS[@]}"; do
  IFS='|' read -r port profile user label land wx wy <<< "$row"
  echo "  $port  $user  $label"
done
cat <<EOF
  密码统一：$PASSWORD
  关闭全部测试浏览器：./scripts/dev/test-env.sh --stop
────────────────────────────────────────────
EOF
