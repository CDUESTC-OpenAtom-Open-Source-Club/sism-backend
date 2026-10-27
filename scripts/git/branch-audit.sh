#!/usr/bin/env bash
# 分支审计与清理。默认只读（dry-run），加 --apply 才真正删除。
#
# 判定分三类：
#   A 已合入 main           → 直接删
#   B 补丁等价（squash 合入）→ 直接删
#   C 有独有提交            → 先打 archive/<repo>/<branch> 标签，再删
#
# 永久分支 main / develop 永不删除；PR 引用 pr/N 不在删除范围。
#
# 用法：
#   ./scripts/git/branch-audit.sh            # 只读，打印计划
#   ./scripts/git/branch-audit.sh --apply    # 执行清理
set -euo pipefail

APPLY=false
[[ "${1:-}" == "--apply" ]] && APPLY=true

cd "$(git rev-parse --show-toplevel)"

NAME_WITH_OWNER="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || true)"
[[ -n "${NAME_WITH_OWNER}" ]] || { echo "无法解析仓库名，请确认 gh 已登录且当前目录是仓库" >&2; exit 1; }
OWNER="${NAME_WITH_OWNER%%/*}"
SLUG="${NAME_WITH_OWNER##*/}"

echo "仓库: ${OWNER}/${SLUG}"
git fetch --all --prune --quiet
git fetch origin +refs/tags/*:refs/tags/* --quiet 2>/dev/null || true

DEL_MERGED=(); DEL_EQUIV=(); ARCHIVE=(); RELEASE=()

for b in $(git branch -r | grep -v -- '->' | sed 's|^[ *]*origin/||' \
           | grep -v -E '^(HEAD|main|develop|pr/[0-9]+)$' | sort); do
  git show-ref --verify --quiet "refs/remotes/origin/$b" || continue
  if [[ "$b" == release/v* ]]; then RELEASE+=("$b"); fi
  if git merge-base --is-ancestor "origin/$b" origin/main 2>/dev/null; then
    DEL_MERGED+=("$b"); continue
  fi
  UNIQUE="$(git cherry origin/main "origin/$b" 2>/dev/null | grep -c '^+' || true)"
  if [[ "${UNIQUE:-0}" -eq 0 ]]; then DEL_EQUIV+=("$b"); else ARCHIVE+=("$b"); fi
done

echo
echo "A. 已合入 main，直接删 (${#DEL_MERGED[@]})"
for b in "${DEL_MERGED[@]:-}"; do [[ -n "$b" ]] && echo "   $b"; done
echo "B. 补丁已等价存在于 main，直接删 (${#DEL_EQUIV[@]})"
for b in "${DEL_EQUIV[@]:-}"; do [[ -n "$b" ]] && echo "   $b"; done
echo "C. 有独有提交，归档后删 (${#ARCHIVE[@]})"
for b in "${ARCHIVE[@]:-}"; do [[ -n "$b" ]] && echo "   $b"; done
echo "D. release/* 分支将先固化为版本标签 (${#RELEASE[@]})"
for b in "${RELEASE[@]:-}"; do [[ -n "$b" ]] && echo "   $b"; done

if [[ "${APPLY}" != true ]]; then
  echo
  echo "（只读模式；确认无误后加 --apply 执行）"
  exit 0
fi

echo
echo "--- 执行 ---"
for b in "${RELEASE[@]:-}"; do
  [[ -z "$b" ]] && continue
  V="${b#release/}"
  if git rev-parse -q --verify "refs/tags/$V" >/dev/null; then
    echo "标签已存在 $V"
  else
    git tag -f "$V" "origin/$b" >/dev/null 2>&1 && git push -q origin "refs/tags/$V" 2>/dev/null \
      && echo "打标签 $V -> $b" || echo "打标签失败 $V"
  fi
done

for b in "${ARCHIVE[@]:-}"; do
  [[ -z "$b" ]] && continue
  T="archive/${SLUG}/$(echo "$b" | tr '/' '-')"
  if git tag -f "$T" "origin/$b" >/dev/null 2>&1 && git push -q origin "refs/tags/$T" 2>/dev/null; then
    echo "归档 $T"
  else
    echo "归档失败 $T —— 该分支跳过删除"
    continue
  fi
done

for b in "${DEL_MERGED[@]:-}" "${DEL_EQUIV[@]:-}" "${ARCHIVE[@]:-}"; do
  [[ -z "$b" ]] && continue
  # 归档失败的 C 类分支不删：确认标签存在
  if [[ " ${ARCHIVE[*]:-} " == *" $b "* ]]; then
    T="archive/${SLUG}/$(echo "$b" | tr '/' '-')"
    git rev-parse -q --verify "refs/tags/$T" >/dev/null || { echo "跳过 $b（归档标签缺失）"; continue; }
  fi
  SHA="$(git rev-parse "origin/$b")"
  if gh api -X DELETE "repos/${OWNER}/${SLUG}/git/refs/heads/${b}" >/dev/null 2>&1; then
    echo "已删除 $b ($SHA)"
  else
    echo "删除失败 $b"
  fi
done
