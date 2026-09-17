#!/usr/bin/env bash
set -euo pipefail

DB_HOST="${DB_HOST:-postgres}"
DB_PORT="${DB_PORT:-5432}"
DB_URL="${DB_URL:?DB_URL is required}"
DB_USERNAME="${DB_USERNAME:?DB_USERNAME is required}"
DB_PASSWORD="${DB_PASSWORD:?DB_PASSWORD is required}"
JWT_SECRET="${JWT_SECRET:?JWT_SECRET is required}"
ALLOWED_ORIGINS="${ALLOWED_ORIGINS:?ALLOWED_ORIGINS is required}"

SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}"

# ---------------------------------------------------------------------------
# JVM memory & runtime tuning (see docs/DOCKER_MEMORY_GUIDE.md)
# Defaults target a ~2GB container; override via env in docker-compose / k8s.
# ---------------------------------------------------------------------------
JAVA_HEAP_SIZE="${JAVA_HEAP_SIZE:-1400m}"
JAVA_METASPACE_SIZE="${JAVA_METASPACE_SIZE:-256m}"
JAVA_STACK_SIZE="${JAVA_STACK_SIZE:-512k}"
JAVA_GC_LOG_DIR="${JAVA_GC_LOG_DIR:-/app/logs}"

mkdir -p "${JAVA_GC_LOG_DIR}"

JAVA_OPTS="${JAVA_OPTS:-}"
JAVA_OPTS="${JAVA_OPTS} -Xms${JAVA_HEAP_SIZE} -Xmx${JAVA_HEAP_SIZE}"
JAVA_OPTS="${JAVA_OPTS} -XX:MaxMetaspaceSize=${JAVA_METASPACE_SIZE}"
JAVA_OPTS="${JAVA_OPTS} -Xss${JAVA_STACK_SIZE}"
JAVA_OPTS="${JAVA_OPTS} -XX:+UseContainerSupport"
JAVA_OPTS="${JAVA_OPTS} -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=4m"
JAVA_OPTS="${JAVA_OPTS} -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${JAVA_GC_LOG_DIR}/heapdump.hprof"
JAVA_OPTS="${JAVA_OPTS} -Xlog:gc*:file=${JAVA_GC_LOG_DIR}/gc.log:time,uptime,level,tags:filecount=5,filesize=10m"
JAVA_OPTS="${JAVA_OPTS} -Djava.security.egd=file:/dev/./urandom"
# 显式锁定 JVM 时区为北京时间：jackson 的 spring.jackson.time-zone 只对 java.util.Date
# 生效，LocalDateTime（全仓主力类型）不受其影响，必须由 JVM 时区兜底。
JAVA_OPTS="${JAVA_OPTS} -Duser.timezone=${APP_TIMEZONE:-Asia/Shanghai}"

echo "Waiting for PostgreSQL at ${DB_HOST}:${DB_PORT}..."
until pg_isready -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USERNAME}" >/dev/null 2>&1; do
  sleep 2
done

echo "Starting JVM with options: ${JAVA_OPTS}"
exec java ${JAVA_OPTS} org.springframework.boot.loader.launch.JarLauncher --spring.profiles.active="${SPRING_PROFILES_ACTIVE}"
