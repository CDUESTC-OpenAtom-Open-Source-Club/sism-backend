#!/bin/bash
# ============================================
# SISM 健康检查脚本
# ============================================
# 用途: 检查 SISM 系统各组件健康状态
# 使用: ./health-check.sh [--json] [--quiet]
# 定时任务: */5 * * * * /opt/sism/scripts/health-check.sh --quiet
# ============================================

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# 配置
BACKEND_URL="${BACKEND_URL:-http://localhost:8080}"
BACKEND_HEALTH_PATH="${BACKEND_HEALTH_PATH:-/api/v1/actuator/health}"
FRONTEND_URL="${FRONTEND_URL:-http://localhost}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-sism_prod}"
DB_USER="${DB_USER:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-}"
# 凭据经 PGPASSFILE 传递（libpq 官方机制），避免口令出现在命令行与环境变量
PGPASS_FILE="$(mktemp -t sism-pgpass-XXXXXX)"
printf '*:*:*:%s:%s\n' "$DB_USER" "$DB_PASSWORD" > "$PGPASS_FILE"
chmod 600 "$PGPASS_FILE"
export PGPASSFILE="$PGPASS_FILE"
trap 'rm -f "$PGPASS_FILE"' EXIT

# 选项
OUTPUT_JSON=false
QUIET_MODE=false
ALERT_ON_FAILURE=true

# 检查结果
OVERALL_STATUS="healthy"
CHECKS=()

# 解析参数
parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --json)
                OUTPUT_JSON=true
                shift
                ;;
            --quiet|-q)
                QUIET_MODE=true
                shift
                ;;
            --no-alert)
                ALERT_ON_FAILURE=false
                shift
                ;;
            --help|-h)
                show_help
                exit 0
                ;;
            *)
                shift
                ;;
        esac
    done
}

# 日志函数
log_info() {
    if [[ "$QUIET_MODE" == "false" ]]; then
        echo -e "${BLUE}[INFO]${NC} $1"
    fi
}

log_success() {
    if [[ "$QUIET_MODE" == "false" ]]; then
        echo -e "${GREEN}[OK]${NC} $1"
    fi
}

log_warn() {
    if [[ "$QUIET_MODE" == "false" ]]; then
        echo -e "${YELLOW}[WARN]${NC} $1"
    fi
}

log_error() {
    if [[ "$QUIET_MODE" == "false" ]]; then
        echo -e "${RED}[FAIL]${NC} $1"
    fi
}

# 添加检查结果
add_check() {
    local name=$1
    local status=$2
    local message=$3
    local response_time=$4
    
    CHECKS+=("{\"name\":\"$name\",\"status\":\"$status\",\"message\":\"$message\",\"response_time_ms\":$response_time}")
    
    if [[ "$status" != "healthy" ]]; then
        OVERALL_STATUS="unhealthy"
    fi
}

# 检查后端健康状态
check_backend_health() {
    log_info "检查后端服务健康状态..."
    
    local start_time=$(date +%s%3N)
    local response
    local http_code
    
    # 调用健康检查端点
    response=$(curl -s -w "\n%{http_code}" --connect-timeout 5 --max-time 10 "$BACKEND_URL$BACKEND_HEALTH_PATH" 2>/dev/null || echo -e "\n000")
    http_code=$(echo "$response" | tail -n1)
    local body=$(echo "$response" | sed '$d')
    
    local end_time=$(date +%s%3N)
    local response_time=$((end_time - start_time))
    
    if [[ "$http_code" == "200" ]]; then
        local status=$(echo "$body" | grep -o '"status"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | cut -d'"' -f4)
        
        if [[ "$status" == "UP" ]]; then
            log_success "后端服务健康 (响应时间: ${response_time}ms)"
            add_check "backend" "healthy" "Service is UP" "$response_time"
        else
            log_warn "后端服务状态异常: $status"
            add_check "backend" "degraded" "Service status: $status" "$response_time"
        fi
    else
        log_error "后端服务不可用 (HTTP $http_code)"
        add_check "backend" "unhealthy" "HTTP status: $http_code" "$response_time"
    fi
}

# 检查数据库连接
check_database() {
    log_info "检查数据库连接..."
    
    local start_time=$(date +%s%3N)
    
    if psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "SELECT 1" > /dev/null 2>&1; then
        local end_time=$(date +%s%3N)
        local response_time=$((end_time - start_time))
        
        log_success "数据库连接正常 (响应时间: ${response_time}ms)"
        add_check "database" "healthy" "Connection successful" "$response_time"
    else
        local end_time=$(date +%s%3N)
        local response_time=$((end_time - start_time))
        
        log_error "数据库连接失败"
        add_check "database" "unhealthy" "Connection failed" "$response_time"
    fi
}

# 检查 Nginx 状态
check_nginx() {
    log_info "检查 Nginx 服务..."
    
    local start_time=$(date +%s%3N)
    
    if systemctl is-active --quiet nginx; then
        local end_time=$(date +%s%3N)
        local response_time=$((end_time - start_time))
        
        log_success "Nginx 服务运行中"
        add_check "nginx" "healthy" "Service is running" "$response_time"
    else
        local end_time=$(date +%s%3N)
        local response_time=$((end_time - start_time))
        
        log_error "Nginx 服务未运行"
        add_check "nginx" "unhealthy" "Service is not running" "$response_time"
    fi
}

# 检查前端可访问性
check_frontend() {
    log_info "检查前端可访问性..."
    
    local start_time=$(date +%s%3N)
    local http_code
    
    http_code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 --max-time 10 "$FRONTEND_URL" 2>/dev/null || echo "000")
    
    local end_time=$(date +%s%3N)
    local response_time=$((end_time - start_time))
    
    if [[ "$http_code" == "200" ]] || [[ "$http_code" == "304" ]]; then
        log_success "前端可访问 (响应时间: ${response_time}ms)"
        add_check "frontend" "healthy" "HTTP $http_code" "$response_time"
    else
        log_error "前端不可访问 (HTTP $http_code)"
        add_check "frontend" "unhealthy" "HTTP $http_code" "$response_time"
    fi
}

# 检查磁盘空间
check_disk_space() {
    log_info "检查磁盘空间..."
    
    local start_time=$(date +%s%3N)
    local usage
    
    # 检查根分区使用率
    usage=$(df / | tail -1 | awk '{print $5}' | sed 's/%//')
    
    local end_time=$(date +%s%3N)
    local response_time=$((end_time - start_time))
    
    if [[ $usage -lt 80 ]]; then
        log_success "磁盘空间充足 (使用率: ${usage}%)"
        add_check "disk_space" "healthy" "Usage: ${usage}%" "$response_time"
    elif [[ $usage -lt 90 ]]; then
        log_warn "磁盘空间不足 (使用率: ${usage}%)"
        add_check "disk_space" "degraded" "Usage: ${usage}%" "$response_time"
    else
        log_error "磁盘空间严重不足 (使用率: ${usage}%)"
        add_check "disk_space" "unhealthy" "Usage: ${usage}%" "$response_time"
    fi
}

# 检查内存使用
check_memory() {
    log_info "检查内存使用..."
    
    local start_time=$(date +%s%3N)
    local usage
    
    # 获取内存使用率
    usage=$(free | grep Mem | awk '{printf "%.0f", $3/$2 * 100}')
    
    local end_time=$(date +%s%3N)
    local response_time=$((end_time - start_time))
    
    if [[ $usage -lt 80 ]]; then
        log_success "内存使用正常 (使用率: ${usage}%)"
        add_check "memory" "healthy" "Usage: ${usage}%" "$response_time"
    elif [[ $usage -lt 90 ]]; then
        log_warn "内存使用较高 (使用率: ${usage}%)"
        add_check "memory" "degraded" "Usage: ${usage}%" "$response_time"
    else
        log_error "内存使用过高 (使用率: ${usage}%)"
        add_check "memory" "unhealthy" "Usage: ${usage}%" "$response_time"
    fi
}

# 检查 API 响应
check_api_response() {
    log_info "检查 API 响应..."
    
    local start_time=$(date +%s%3N)
    local http_code
    
    # 测试当前公开的健康端点响应时间
    http_code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 --max-time 10 "$BACKEND_URL$BACKEND_HEALTH_PATH" 2>/dev/null || echo "000")
    
    local end_time=$(date +%s%3N)
    local response_time=$((end_time - start_time))
    
    if [[ "$http_code" == "200" ]]; then
        if [[ $response_time -lt 500 ]]; then
            log_success "API 响应正常 (响应时间: ${response_time}ms)"
            add_check "api_response" "healthy" "Response time: ${response_time}ms" "$response_time"
        else
            log_warn "API 响应较慢 (响应时间: ${response_time}ms)"
            add_check "api_response" "degraded" "Response time: ${response_time}ms" "$response_time"
        fi
    else
        log_error "API 响应异常 (HTTP $http_code)"
        add_check "api_response" "unhealthy" "HTTP $http_code" "$response_time"
    fi
}

# 输出 JSON 格式结果
output_json() {
    local checks_json=$(IFS=,; echo "${CHECKS[*]}")
    
    echo "{"
    echo "  \"status\": \"$OVERALL_STATUS\","
    echo "  \"timestamp\": \"$(date -Iseconds)\","
    echo "  \"checks\": [$checks_json]"
    echo "}"
}

# 输出文本格式结果
output_text() {
    echo
    echo -e "${CYAN}============================================${NC}"
    echo -e "${CYAN}SISM 健康检查报告${NC}"
    echo -e "${CYAN}============================================${NC}"
    echo "检查时间: $(date '+%Y-%m-%d %H:%M:%S')"
    echo
    
    if [[ "$OVERALL_STATUS" == "healthy" ]]; then
        echo -e "总体状态: ${GREEN}健康${NC}"
    else
        echo -e "总体状态: ${RED}异常${NC}"
    fi
    
    echo -e "${CYAN}============================================${NC}"
}

# 发送告警
send_alert() {
    if [[ "$ALERT_ON_FAILURE" == "true" ]] && [[ "$OVERALL_STATUS" != "healthy" ]]; then
        # 如果配置了 webhook URL，发送告警
        if [[ -n "$ALERT_WEBHOOK_URL" ]]; then
            local payload=$(output_json)
            curl -s -X POST "$ALERT_WEBHOOK_URL" \
                -H "Content-Type: application/json" \
                -d "$payload" \
                > /dev/null 2>&1 || true
        fi
        
        # 记录到系统日志
        logger -t "sism-health" "Health check failed: $OVERALL_STATUS"
    fi
}

# 显示帮助
show_help() {
    echo "SISM 健康检查脚本"
    echo
    echo "用法: $0 [选项]"
    echo
    echo "选项:"
    echo "  --json      - 输出 JSON 格式结果"
    echo "  --quiet, -q - 静默模式，仅输出最终结果"
    echo "  --no-alert  - 不发送告警通知"
    echo "  --help, -h  - 显示帮助"
    echo
    echo "环境变量:"
    echo "  BACKEND_URL       - 后端服务 URL (默认: http://localhost:8080)"
    echo "  FRONTEND_URL      - 前端 URL (默认: http://localhost)"
    echo "  DB_HOST           - 数据库主机 (默认: localhost)"
    echo "  DB_PORT           - 数据库端口 (默认: 5432)"
    echo "  DB_NAME           - 数据库名称 (默认: sism_prod)"
    echo "  DB_USER           - 数据库用户 (默认: sism_user)"
    echo "  DB_PASSWORD       - 数据库密码"
    echo "  ALERT_WEBHOOK_URL - 告警 Webhook URL"
    echo
    echo "退出码:"
    echo "  0 - 所有检查通过"
    echo "  1 - 存在失败的检查"
}

# 主函数
main() {
    parse_args "$@"
    
    if [[ "$QUIET_MODE" == "false" ]] && [[ "$OUTPUT_JSON" == "false" ]]; then
        echo -e "${CYAN}============================================${NC}"
        echo -e "${CYAN}SISM 健康检查${NC}"
        echo -e "${CYAN}============================================${NC}"
        echo
    fi
    
    # 执行各项检查
    check_backend_health
    check_database
    check_nginx
    check_frontend
    check_disk_space
    check_memory
    check_api_response
    
    # 输出结果
    if [[ "$OUTPUT_JSON" == "true" ]]; then
        output_json
    else
        output_text
    fi
    
    # 发送告警
    send_alert
    
    # 返回状态码
    if [[ "$OVERALL_STATUS" == "healthy" ]]; then
        exit 0
    else
        exit 1
    fi
}

# 运行主函数
main "$@"
