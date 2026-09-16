#!/bin/bash
# ============================================
# SISM 数据库恢复脚本
# ============================================
# 用途: 从备份文件恢复 PostgreSQL 数据库
# 使用: ./restore-database.sh <backup_file>
# ============================================

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 日志函数
log_info() {
    echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] ${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] ${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] ${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] ${RED}[ERROR]${NC} $1"
}

# 配置变量
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-sism_prod}"
DB_USER="${DB_USER:-sism_user}"
DB_PASSWORD="${DB_PASSWORD:-}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/sism}"

BACKUP_FILE=""
FORCE_MODE=false
VERIFY_ONLY=false

# 显示帮助
show_help() {
    echo "SISM 数据库恢复脚本"
    echo
    echo "用法: $0 <backup_file> [选项]"
    echo "      $0 --list"
    echo "      $0 --latest [选项]"
    echo
    echo "参数:"
    echo "  backup_file  - 备份文件路径 (.sql.gz 或 .sql)"
    echo
    echo "选项:"
    echo "  --force      - 强制恢复，不提示确认"
    echo "  --verify     - 仅验证备份文件，不执行恢复"
    echo "  --list       - 列出可用的备份文件"
    echo "  --latest     - 使用最新的备份文件"
    echo "  --help       - 显示帮助"
    echo
    echo "环境变量:"
    echo "  DB_HOST      - 数据库主机 (默认: localhost)"
    echo "  DB_PORT      - 数据库端口 (默认: 5432)"
    echo "  DB_NAME      - 数据库名称 (默认: sism_prod)"
    echo "  DB_USER      - 数据库用户 (默认: sism_user)"
    echo "  DB_PASSWORD  - 数据库密码"
    echo "  BACKUP_DIR   - 备份目录 (默认: /var/backups/sism)"
    echo
    echo "示例:"
    echo "  $0 /var/backups/sism/sism_full_20260118_020000.sql.gz"
    echo "  $0 --latest --force"
    echo "  $0 backup.sql.gz --verify"
    echo "  DB_PASSWORD=xxx $0 backup.sql.gz --force"
}

# 解析参数
parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --help|-h)
                show_help
                exit 0
                ;;
            --force)
                FORCE_MODE=true
                shift
                ;;
            --verify)
                VERIFY_ONLY=true
                shift
                ;;
            --list)
                list_backups
                exit 0
                ;;
            --latest)
                BACKUP_FILE=$(find "$BACKUP_DIR" -name "sism_full_*.sql.gz" -type f 2>/dev/null | sort -r | head -1)
                if [[ -z "$BACKUP_FILE" ]]; then
                    log_error "未找到备份文件"
                    exit 1
                fi
                log_info "使用最新备份: $BACKUP_FILE"
                shift
                ;;
            -*)
                log_error "未知选项: $1"
                show_help
                exit 1
                ;;
            *)
                if [[ -z "$BACKUP_FILE" ]]; then
                    BACKUP_FILE="$1"
                fi
                shift
                ;;
        esac
    done
}

# 列出可用备份
list_backups() {
    echo
    echo "============================================"
    echo "可用备份文件"
    echo "============================================"
    echo "备份目录: $BACKUP_DIR"
    echo
    
    if [[ ! -d "$BACKUP_DIR" ]]; then
        log_warn "备份目录不存在: $BACKUP_DIR"
        return 0
    fi
    
    local count=$(find "$BACKUP_DIR" -name "sism_*.sql.gz" -type f 2>/dev/null | wc -l)
    
    if [[ $count -eq 0 ]]; then
        log_warn "未找到备份文件"
        return 0
    fi
    
    echo "备份文件列表 (按时间倒序):"
    echo "----------------------------------------"
    printf "%-50s %10s %s\n" "文件名" "大小" "修改时间"
    echo "----------------------------------------"
    
    find "$BACKUP_DIR" -name "sism_*.sql.gz" -type f -printf "%T@ %p\n" 2>/dev/null | \
        sort -rn | \
        while read -r timestamp filepath; do
            filename=$(basename "$filepath")
            size=$(du -h "$filepath" | cut -f1)
            mtime=$(date -d "@${timestamp%.*}" '+%Y-%m-%d %H:%M:%S')
            printf "%-50s %10s %s\n" "$filename" "$size" "$mtime"
        done
    
    echo "----------------------------------------"
    echo "总计: $count 个备份文件"
    echo "============================================"
}

# 检查参数
check_args() {
    if [[ -z "$BACKUP_FILE" ]]; then
        log_error "请指定备份文件"
        show_help
        exit 1
    fi
    
    if [[ ! -f "$BACKUP_FILE" ]]; then
        log_error "备份文件不存在: $BACKUP_FILE"
        exit 1
    fi
}

# 检查数据库连接
check_connection() {
    log_info "检查数据库连接..."
    
    if ! PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "postgres" -c "SELECT 1" > /dev/null 2>&1; then
        log_error "无法连接到数据库服务器"
        exit 1
    fi
    
    log_success "数据库连接正常"
}

# 确认恢复操作
confirm_restore() {
    if [[ "$FORCE_MODE" == "true" ]]; then
        return 0
    fi
    
    echo
    log_warn "警告: 此操作将删除现有数据库 $DB_NAME 中的所有数据!"
    echo
    echo "备份文件: $BACKUP_FILE"
    echo "目标数据库: $DB_NAME"
    echo "数据库主机: $DB_HOST:$DB_PORT"
    echo
    echo -n "确定要继续吗? 输入 'yes' 确认: "
    read -r response
    
    if [[ "$response" != "yes" ]]; then
        log_info "操作已取消"
        exit 0
    fi
}

# 创建恢复前备份
create_pre_restore_backup() {
    log_info "创建恢复前备份..."
    
    PRE_RESTORE_BACKUP="/tmp/sism_pre_restore_$(date +%Y%m%d_%H%M%S).sql.gz"
    
    PGPASSWORD="$DB_PASSWORD" pg_dump \
        -h "$DB_HOST" \
        -p "$DB_PORT" \
        -U "$DB_USER" \
        -d "$DB_NAME" \
        --format=plain \
        2>/dev/null | gzip > "$PRE_RESTORE_BACKUP" || true
    
    if [[ -f "$PRE_RESTORE_BACKUP" ]] && [[ $(stat -f%z "$PRE_RESTORE_BACKUP" 2>/dev/null || stat -c%s "$PRE_RESTORE_BACKUP") -gt 100 ]]; then
        log_success "恢复前备份已创建: $PRE_RESTORE_BACKUP"
    else
        log_warn "无法创建恢复前备份 (数据库可能为空)"
        rm -f "$PRE_RESTORE_BACKUP"
    fi
}

# 重建数据库
recreate_database() {
    log_info "重建数据库 $DB_NAME..."
    
    # 断开所有连接
    sudo -u postgres psql -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$DB_NAME' AND pid <> pg_backend_pid();" 2>/dev/null || true
    
    # 删除并重建数据库
    sudo -u postgres psql -c "DROP DATABASE IF EXISTS $DB_NAME;"
    sudo -u postgres psql -c "CREATE DATABASE $DB_NAME OWNER $DB_USER ENCODING 'UTF8' LC_COLLATE 'en_US.UTF-8' LC_CTYPE 'en_US.UTF-8' TEMPLATE template0;"
    sudo -u postgres psql -c "GRANT ALL PRIVILEGES ON DATABASE $DB_NAME TO $DB_USER;"
    
    log_success "数据库已重建"
}

# 恢复数据
restore_data() {
    log_info "开始恢复数据..."
    log_info "备份文件: $BACKUP_FILE"
    
    # 判断文件类型
    if [[ "$BACKUP_FILE" == *.gz ]]; then
        log_info "解压并恢复 gzip 压缩的备份..."
        gunzip -c "$BACKUP_FILE" | PGPASSWORD="$DB_PASSWORD" psql \
            -h "$DB_HOST" \
            -p "$DB_PORT" \
            -U "$DB_USER" \
            -d "$DB_NAME" \
            --single-transaction \
            --set ON_ERROR_STOP=on \
            2>&1
    else
        log_info "恢复 SQL 备份..."
        PGPASSWORD="$DB_PASSWORD" psql \
            -h "$DB_HOST" \
            -p "$DB_PORT" \
            -U "$DB_USER" \
            -d "$DB_NAME" \
            --single-transaction \
            --set ON_ERROR_STOP=on \
            -f "$BACKUP_FILE" \
            2>&1
    fi
    
    if [[ $? -eq 0 ]]; then
        log_success "数据恢复完成"
    else
        log_error "数据恢复失败"
        exit 1
    fi
}

# 验证备份文件完整性
verify_backup_file() {
    log_info "验证备份文件完整性..."
    
    # 检查文件存在
    if [[ ! -f "$BACKUP_FILE" ]]; then
        log_error "备份文件不存在: $BACKUP_FILE"
        return 1
    fi
    
    # 检查文件大小
    local file_size
    file_size=$(stat -f%z "$BACKUP_FILE" 2>/dev/null || stat -c%s "$BACKUP_FILE")
    
    if [[ $file_size -lt 1000 ]]; then
        log_warn "备份文件过小 ($file_size bytes)，可能存在问题"
    else
        log_info "备份文件大小: $(numfmt --to=iec $file_size 2>/dev/null || echo "$file_size bytes")"
    fi
    
    # 验证 gzip 完整性
    if [[ "$BACKUP_FILE" == *.gz ]]; then
        log_info "验证 gzip 压缩完整性..."
        if gzip -t "$BACKUP_FILE" 2>/dev/null; then
            log_success "gzip 压缩完整性验证通过"
        else
            log_error "备份文件损坏: gzip 校验失败"
            return 1
        fi
        
        # 检查 SQL 内容
        log_info "检查 SQL 内容..."
        local sql_check
        sql_check=$(gunzip -c "$BACKUP_FILE" 2>/dev/null | head -100)
        
        if echo "$sql_check" | grep -q "PostgreSQL database dump"; then
            log_success "备份文件格式正确 (PostgreSQL dump)"
        else
            log_warn "备份文件可能不是标准 PostgreSQL dump 格式"
        fi
        
        # 检查是否包含关键表
        log_info "检查关键表定义..."
        local tables_found=0
        local required_tables=("sys_org" "sys_user" "cycle" "indicator" "sys_task")
        
        for table in "${required_tables[@]}"; do
            if gunzip -c "$BACKUP_FILE" 2>/dev/null | grep -q "CREATE TABLE.*$table\|COPY.*$table"; then
                ((tables_found++))
            fi
        done
        
        log_info "找到 $tables_found/${#required_tables[@]} 个关键表"
        
        if [[ $tables_found -lt 3 ]]; then
            log_warn "备份文件可能不完整，缺少部分关键表"
        fi
    fi
    
    log_success "备份文件验证完成"
    return 0
}

# 验证恢复结果
verify_restore() {
    log_info "验证恢复结果..."
    
    local errors=0
    
    # 检查表数量
    TABLE_COUNT=$(PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -tAc "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';")
    
    log_info "表数量: $TABLE_COUNT"
    
    if [[ $TABLE_COUNT -lt 5 ]]; then
        log_error "表数量过少，恢复可能不完整"
        ((errors++))
    fi
    
    # 检查关键表记录数
    echo
    echo "关键表记录数:"
    echo "----------------------------------------"
    
    TABLES=("sys_org" "sys_user" "cycle" "sys_task" "indicator" "progress_report" "audit_log")
    
    for table in "${TABLES[@]}"; do
        COUNT=$(PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -tAc "SELECT COUNT(*) FROM $table;" 2>/dev/null || echo "N/A")
        printf "  %-20s: %s\n" "$table" "$COUNT"
        
        # 检查关键表是否有数据
        if [[ "$table" == "sys_org" ]] || [[ "$table" == "sys_user" ]]; then
            if [[ "$COUNT" == "0" ]] || [[ "$COUNT" == "N/A" ]]; then
                log_warn "关键表 $table 为空或不存在"
            fi
        fi
    done
    
    echo "----------------------------------------"
    
    # 检查外键完整性
    log_info "检查外键完整性..."
    
    local fk_errors
    fk_errors=$(PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -tAc "
        SELECT COUNT(*) FROM indicator i
        LEFT JOIN sys_org o ON i.owner_org_id = o.org_id
        WHERE i.owner_org_id IS NOT NULL AND o.org_id IS NULL;
    " 2>/dev/null || echo "0")
    
    if [[ "$fk_errors" != "0" ]] && [[ -n "$fk_errors" ]]; then
        log_warn "发现 $fk_errors 条外键引用错误"
        ((errors++))
    else
        log_success "外键完整性检查通过"
    fi
    
    # 检查序列值
    log_info "检查序列同步状态..."
    
    # 最终结果
    echo
    if [[ $errors -eq 0 ]]; then
        log_success "恢复验证通过"
        return 0
    else
        log_warn "恢复验证发现 $errors 个问题，请检查"
        return 1
    fi
}

# 显示恢复摘要
show_summary() {
    echo
    echo "============================================"
    echo "数据库恢复完成"
    echo "============================================"
    echo "备份文件: $BACKUP_FILE"
    echo "目标数据库: $DB_NAME"
    echo "恢复时间: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "============================================"
}

# 主函数
main() {
    log_info "SISM 数据库恢复脚本"
    echo
    
    parse_args "$@"
    check_args
    
    # 仅验证模式
    if [[ "$VERIFY_ONLY" == "true" ]]; then
        log_info "仅验证模式 - 不执行恢复"
        verify_backup_file
        exit $?
    fi
    
    check_connection
    confirm_restore
    create_pre_restore_backup
    recreate_database
    restore_data
    verify_restore
    show_summary
}

# 错误处理
trap 'log_error "恢复过程中发生错误"; exit 1' ERR

# 运行主函数
main "$@"
