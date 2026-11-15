#!/usr/bin/env bash
# 载入 .env 文件，但**不覆盖外部显式传入的环境变量**。
#
# 优先级约定（与后端 Spring 侧 EnvConfigPostProcessor / EnvConfig 保持一致）：
#   显式环境变量 > .env 文件
#
# 背景：backend/.env 里的 DB_URL 指向本地开发库（sism）。过去脚本统一用
#   `set -a; source .env; set +a`
# 载入，source 会**直接覆盖**外部已经导出的同名变量，set -a 又把它导出给子进程。
# 结果是「按环境变量指定库」无声失效——JVM 拿到的是 .env 里的本地库地址，
# 连错库也没有任何提示。本轮部署就差点因此连错库。
#
# 用法：source "<本文件路径>" "<.env 路径>"

SISM_LOAD_ENV_FILE="${1:-}"

if [[ -z "$SISM_LOAD_ENV_FILE" ]]; then
  echo "[load-env-file] 缺少 .env 文件路径参数" >&2
  # shellcheck disable=SC2317  # 本文件被 source 引入，return 才是正确的退出方式
  return 1 2>/dev/null || exit 1
fi

if [[ ! -f "$SISM_LOAD_ENV_FILE" ]]; then
  # .env 不存在不算错误：保持外部环境原样
  unset SISM_LOAD_ENV_FILE
  # shellcheck disable=SC2317  # 本文件被 source 引入，return 才是正确的退出方式
  return 0 2>/dev/null || exit 0
fi

# 1) 快照当前已导出的环境变量（source 之前的原值）
SISM_LOAD_ENV_SNAPSHOT="$(export -p)"

# 2) 载入 .env（set -a 让 .env 里的变量导出给子进程）
case "$-" in
  *a*) SISM_LOAD_ENV_KEEP_ALLEXPORT=1 ;;
  *) SISM_LOAD_ENV_KEEP_ALLEXPORT=0 ;;
esac
set -a
# shellcheck disable=SC1090
source "$SISM_LOAD_ENV_FILE"
if [[ "$SISM_LOAD_ENV_KEEP_ALLEXPORT" == "0" ]]; then
  set +a
fi

# 3) 恢复外部显式传入的变量：env 优先级高于 .env 文件
#    （export -p 的输出是可被 eval 还原的声明式快照，含特殊字符的取值也安全）
eval "$SISM_LOAD_ENV_SNAPSHOT"

unset SISM_LOAD_ENV_FILE SISM_LOAD_ENV_SNAPSHOT SISM_LOAD_ENV_KEEP_ALLEXPORT
