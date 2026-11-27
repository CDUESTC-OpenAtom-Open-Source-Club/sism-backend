#!/usr/bin/env bash
# ==============================================================================
# GitHub Actions artifact 断点续传下载（抗链路速率劣化）
#
# 背景（2026-11-15 实测）：本机 → api.github.com 的 artifact 下载速率会
# **间歇性劣化到 KB/s**（同机同 URL 连测：9.4KB/s → 6.4KB/s → 5.7MB/s 跳变）。
# 普通 curl 的 --retry 遇超时可能整体重来 → 表现为"卡在 0 字节 20 分钟无进展"。
# 正确解法 = **外层循环 + --continue-at - 续传 + 完整性校验收口**。
#
# 用法：
#   ./download-artifact-resume.sh --repo <owner/repo> --artifact <artifact_name> \
#                                 --output <zip 路径> [--max-rounds 40] [--chunk-timeout 25]
#
# 退出码：0 = 下载完成且 zip 完整性校验通过；非 0 = 失败
# ==============================================================================
set -euo pipefail

REPO=""
ARTIFACT_NAME=""
OUTPUT=""
MAX_ROUNDS=40
CHUNK_TIMEOUT=25

log()  { printf '[artifact-resume] %s\n' "$1"; }
fail() { printf '[artifact-resume] ERROR: %s\n' "$1" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)          REPO="$2"; shift 2 ;;
    --artifact)      ARTIFACT_NAME="$2"; shift 2 ;;
    --output)        OUTPUT="$2"; shift 2 ;;
    --max-rounds)    MAX_ROUNDS="$2"; shift 2 ;;
    --chunk-timeout) CHUNK_TIMEOUT="$2"; shift 2 ;;
    -h|--help)
      cat <<'EOF'
Usage: download-artifact-resume.sh --repo REPO --artifact NAME --output FILE
                                  [--max-rounds N] [--chunk-timeout SEC]
EOF
      exit 0 ;;
    *) fail "Unknown option: $1" ;;
  esac
done

[[ -n "$REPO" ]] || fail "--repo is required"
[[ -n "$ARTIFACT_NAME" ]] || fail "--artifact is required"
[[ -n "$OUTPUT" ]] || fail "--output is required"
command -v gh >/dev/null 2>&1 || fail "Missing required command: gh"
command -v curl >/dev/null 2>&1 || fail "Missing required command: curl"

TOKEN="$(gh auth token)" || fail "gh auth token failed (未登录?)"
AUTH_HEADER_FILE="$(mktemp)"
chmod 600 "${AUTH_HEADER_FILE}"
printf 'Authorization: Bearer %s\n' "${TOKEN}" > "${AUTH_HEADER_FILE}"
unset TOKEN

mkdir -p "$(dirname "${OUTPUT}")"

artifact_id="$(gh api "/repos/${REPO}/actions/artifacts" --paginate \
  --jq ".artifacts[] | select(.name == \"${ARTIFACT_NAME}\") | .id" | head -n 1)"
[[ -n "${artifact_id}" ]] || fail "Artifact not found: ${ARTIFACT_NAME} (repo=${REPO})"

url="https://api.github.com/repos/${REPO}/actions/artifacts/${artifact_id}/zip"
log "下载 ${ARTIFACT_NAME} (artifact_id=${artifact_id}) → ${OUTPUT}"

for round in $(seq 1 "${MAX_ROUNDS}"); do
  before=0
  [[ -f "${OUTPUT}" ]] && before="$(stat -f%z "${OUTPUT}" 2>/dev/null || stat -c%s "${OUTPUT}" 2>/dev/null || echo 0)"
  timeout "${CHUNK_TIMEOUT}" curl \
    --fail --location --silent --show-error \
    --connect-timeout 10 \
    --continue-at - \
    --header "Accept: application/vnd.github+json" \
    --header "@${AUTH_HEADER_FILE}" \
    --output "${OUTPUT}" \
    "${url}" || true
  after=0
  [[ -f "${OUTPUT}" ]] && after="$(stat -f%z "${OUTPUT}" 2>/dev/null || stat -c%s "${OUTPUT}" 2>/dev/null || echo 0)"
  log "轮次 ${round}/${MAX_ROUNDS}: ${before} → ${after} 字节"
  # 完整性校验：zip 能完整读取才算成功
  if unzip -tq "${OUTPUT}" >/dev/null 2>&1; then
    rm -f "${AUTH_HEADER_FILE}"
    log "下载完成并通过完整性校验（${after} 字节）"
    exit 0
  fi
  if [[ "${after}" -le 0 ]]; then
    log "本轮无进展（0 字节），重试中…"
  fi
  sleep 2
done

rm -f "${AUTH_HEADER_FILE}"
fail "超过最大轮次 ${MAX_ROUNDS} 仍未下载完整（当前 $(stat -f%z "${OUTPUT}" 2>/dev/null || echo 0) 字节）"
