#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

ENV_FILE=".env"
JAR_PATH="sism-main/target/sism-main-1.0.0.jar"
LOG_FILE="/tmp/sism-backend.log"
HEALTH_URL="http://localhost:8080/api/v1/auth/health"
MAX_RETRIES=30
# Default JVM sizing for a 4GB single-host deployment (Java + PostgreSQL + Redis).
# Override with APP_JAVA_OPTS or JAVA_OPTS when needed.
DEFAULT_APP_JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError"

cleanup_backend_processes() {
    local pids=""

    pids=$(ps aux | grep -E "sism-main-1.0.0.jar|sism-backend-1.0.0.jar|spring-boot:run" | grep -v grep | awk '{print $2}' || true)
    if [ -n "$pids" ]; then
        echo "⚠ 检测到旧的 SISM 后端进程，优先优雅停止..."
        echo "$pids" | xargs kill 2>/dev/null || true
        sleep 3
    fi

    pids=$(lsof -ti :8080 2>/dev/null || true)
    if [ -n "$pids" ]; then
        echo "⚠ 端口 8080 仍被占用，执行兜底停止..."
        echo "$pids" | xargs kill 2>/dev/null || true
        sleep 2
    fi

    pids=$(lsof -ti :8080 2>/dev/null || true)
    if [ -n "$pids" ]; then
        echo "⚠ 端口 8080 仍未释放，使用 kill -9 兜底..."
        echo "$pids" | xargs kill -9 2>/dev/null || true
        sleep 2
    fi
}

wait_for_health() {
    local retry_count=0

    echo "等待服务就绪（最多 $((MAX_RETRIES * 2)) 秒）..."
    while [ "$retry_count" -lt "$MAX_RETRIES" ]; do
        if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
            echo "✓ 服务健康检查通过"
            return 0
        fi
        retry_count=$((retry_count + 1))
        echo -n "."
        sleep 2
    done
    echo ""
    return 1
}

verify_key_endpoints() {
    local plans_status workflows_status

    plans_status=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/v1/plans || true)
    workflows_status=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/v1/workflows/my-tasks || true)

    if echo "$plans_status" | grep -qE "200|401|403"; then
        echo "✓ Plans 接口正常 ($plans_status)"
    else
        echo "✗ Plans 接口异常 ($plans_status)"
    fi

    if echo "$workflows_status" | grep -qE "200|401|403"; then
        echo "✓ Workflows 接口正常 ($workflows_status)"
    else
        echo "✗ Workflows 接口异常 ($workflows_status)"
    fi
}

echo "=== SISM 后端统一启动脚本 ==="
echo ""

if ! command -v java >/dev/null 2>&1; then
    echo "✗ 未找到 Java，请先安装并配置 Java 17"
    exit 1
fi

echo "✓ Java 环境: $(java -version 2>&1 | head -1)"
echo ""

if [ ! -f "$ENV_FILE" ]; then
    echo "✗ 未找到环境文件: $SCRIPT_DIR/$ENV_FILE"
    exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

echo "✓ 已加载环境文件: $SCRIPT_DIR/$ENV_FILE"
echo "✓ 当前数据库: ${DB_URL:-<missing DB_URL>}"
echo "✓ 当前 Profile: ${SPRING_PROFILES_ACTIVE:-default}"
echo ""

APP_JAVA_OPTS="${APP_JAVA_OPTS:-${JAVA_OPTS:-$DEFAULT_APP_JAVA_OPTS}}"
echo "✓ JVM 参数: $APP_JAVA_OPTS"
echo "✓ Hikari 连接池: max=${DB_HIKARI_MAX_POOL_SIZE:-8}, minIdle=${DB_HIKARI_MIN_IDLE:-2}"
echo ""

cleanup_backend_processes

echo "→ 重新构建后端模块并刷新本地 Maven 依赖..."
./mvnw -pl sism-main -am clean install -DskipTests -Dmaven.test.skip=true
echo "✓ 构建完成"
echo ""

if [ ! -f "$JAR_PATH" ]; then
    echo "✗ 未找到构建产物: $SCRIPT_DIR/$JAR_PATH"
    exit 1
fi

echo "→ 启动后端服务..."
nohup java $APP_JAVA_OPTS -jar "$JAR_PATH" >"$LOG_FILE" 2>&1 < /dev/null &
BACKEND_PID=$!
disown "$BACKEND_PID" 2>/dev/null || true

echo "✓ 后端服务已启动 (PID: $BACKEND_PID)"
echo "✓ 日志文件: $LOG_FILE"
echo ""

if ! wait_for_health; then
    echo ""
    echo "✗ 服务启动超时，请检查日志:"
    tail -30 "$LOG_FILE" || true
    exit 1
fi

echo ""
echo "→ 验证关键接口..."
verify_key_endpoints

echo ""
echo "服务地址: http://localhost:8080"
echo "健康检查: $HEALTH_URL"
echo "查看日志: tail -f $LOG_FILE"
