#!/bin/bash
# ============================================
# SISM 数据库备份脚本
# ============================================
# 用途: 自动备份 PostgreSQL 数据库
# 使用: ./backup-database.sh [full|schema|data]
# 定时任务: 0 2 * * * /opt/sism/scripts/backup-database.sh full
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
# 凭据经 PGPASSFILE 传递（libpq 官方机制），避免口令出现在命令行与环境变量
PGPASS_FILE="$(mktemp -t sism-pgpass-XXXXXX)"
printf '*:*:*:%s:%s\n' "$DB_USER" "$DB_PASSWORD" > "$PGPASS_FILE"
chmod 600 "$PGPASS_FILE"
export PGPASSFILE="$PGPASS_FILE"
trap 'rm -f "$PGPASS_FILE"' EXIT

BACKUP_DIR="${BACKUP_DIR:-/var/backups/sism}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
LOG_FILE="${LOG_FILE:-/var/log/sism/backup.log}"

# 备份类型
BACKUP_TYPE="${1:-full}"
DATE_STAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE=""

# 确保备份目录存在
ensure_backup_dir() {
    if [[ ! -d "$BACKUP_DIR" ]]; then
        mkdir -p "$BACKUP_DIR"
        log_info "创建备份目录: $BACKUP_DIR"
    fi
}

# 检查数据库连接
check_connection() {
    log_info "检查数据库连接..."
    
    if ! psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "SELECT 1" > /dev/null 2>&1; then
        log_error "无法连接到数据库"
        exit 1
    fi
    
    log_success "数据库连接正常"
}

# 全量备份
backup_full() {
    BACKUP_FILE="${BACKUP_DIR}/sism_full_${DATE_STAMP}.sql.gz"
    
    log_info "开始全量备份..."
    log_info "备份文件: $BACKUP_FILE"
    
    pg_dump \
        -h "$DB_HOST" \
        -p "$DB_PORT" \
        -U "$DB_USER" \
        -d "$DB_NAME" \
        --format=plain \
        --no-owner \
        --no-privileges \
        --verbose \
        2>> "$LOG_FILE" | gzip > "$BACKUP_FILE"
    
    if [[ $? -eq 0 ]]; then
        BACKUP_SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
        log_success "全量备份完成: $BACKUP_FILE ($BACKUP_SIZE)"
    else
        log_error "全量备份失败"
        rm -f "$BACKUP_FILE"
        exit 1
    fi
}

# 仅备份 Schema
backup_schema() {
    BACKUP_FILE="${BACKUP_DIR}/sism_schema_${DATE_STAMP}.sql.gz"
    
    log_info "开始 Schema 备份..."
    log_info "备份文件: $BACKUP_FILE"
    
    pg_dump \
        -h "$DB_HOST" \
        -p "$DB_PORT" \
        -U "$DB_USER" \
        -d "$DB_NAME" \
        --schema-only \
        --format=plain \
        --no-owner \
        --no-privileges \
        2>> "$LOG_FILE" | gzip > "$BACKUP_FILE"
    
    if [[ $? -eq 0 ]]; then
        BACKUP_SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
        log_success "Schema 备份完成: $BACKUP_FILE ($BACKUP_SIZE)"
    else
        log_error "Schema 备份失败"
        rm -f "$BACKUP_FILE"
        exit 1
    fi
}

# 仅备份数据
backup_data() {
    BACKUP_FILE="${BACKUP_DIR}/sism_data_${DATE_STAMP}.sql.gz"
    
    log_info "开始数据备份..."
    log_info "备份文件: $BACKUP_FILE"
    
    pg_dump \
        -h "$DB_HOST" \
        -p "$DB_PORT" \
        -U "$DB_USER" \
        -d "$DB_NAME" \
        --data-only \
        --format=plain \
        2>> "$LOG_FILE" | gzip > "$BACKUP_FILE"
    
    if [[ $? -eq 0 ]]; then
        BACKUP_SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
        log_success "数据备份完成: $BACKUP_FILE ($BACKUP_SIZE)"
    else
        log_error "数据备份失败"
        rm -f "$BACKUP_FILE"
        exit 1
    fi
}

# 清理旧备份
cleanup_old_backups() {
    log_info "清理 $BACKUP_RETENTION_DAYS 天前的备份文件..."
    
    DELETED_COUNT=$(find "$BACKUP_DIR" -name "sism_*.sql.gz" -mtime +$BACKUP_RETENTION_DAYS -delete -print | wc -l)
    
    if [[ $DELETED_COUNT -gt 0 ]]; then
        log_info "已删除 $DELETED_COUNT 个旧备份文件"
    else
        log_info "没有需要清理的旧备份文件"
    fi
}

# 验证备份文件
verify_backup() {
    log_info "验证备份文件..."
    
    if [[ ! -f "$BACKUP_FILE" ]]; then
        log_error "备份文件不存在: $BACKUP_FILE"
        exit 1
    fi
    
    # 检查文件大小
    FILE_SIZE=$(stat -f%z "$BACKUP_FILE" 2>/dev/null || stat -c%s "$BACKUP_FILE")
    
    if [[ $FILE_SIZE -lt 1000 ]]; then
        log_warn "备份文件过小 ($FILE_SIZE bytes)，可能存在问题"
    fi
    
    # 尝试解压验证
    if gzip -t "$BACKUP_FILE" 2>/dev/null; then
        log_success "备份文件验证通过"
    else
        log_error "备份文件损坏"
        exit 1
    fi
}

# 显示备份统计
show_stats() {
    echo
    echo "============================================"
    echo "备份统计"
    echo "============================================"
    echo "备份目录: $BACKUP_DIR"
    echo "备份文件数量: $(find "$BACKUP_DIR" -name "sism_*.sql.gz" | wc -l)"
    echo "总占用空间: $(du -sh "$BACKUP_DIR" | cut -f1)"
    echo
    echo "最近 5 个备份:"
    ls -lht "$BACKUP_DIR"/sism_*.sql.gz 2>/dev/null | head -5
    echo "============================================"
}

# 发送告警通知 (可选)
send_notification() {
    local status=$1
    local message=$2
    
    # 如果配置了 webhook URL，发送通知
    if [[ -n "$WEBHOOK_URL" ]]; then
        curl -s -X POST "$WEBHOOK_URL" \
            -H "Content-Type: application/json" \
            -d "{\"status\": \"$status\", \"message\": \"$message\", \"timestamp\": \"$(date -Iseconds)\"}" \
            > /dev/null 2>&1
    fi
    
    # 如果配置了邮件，发送邮件通知
    if [[ -n "$NOTIFY_EMAIL" ]] && command -v mail &> /dev/null; then
        echo "$message" | mail -s "SISM 数据库备份 - $status" "$NOTIFY_EMAIL"
    fi
}

# 上传到远程存储 (可选)
upload_to_remote() {
    if [[ -z "$BACKUP_FILE" ]] || [[ ! -f "$BACKUP_FILE" ]]; then
        return 0
    fi
    
    local filename=$(basename "$BACKUP_FILE")
    
    # S3 上传
    if [[ -n "$S3_BUCKET" ]]; then
        log_info "上传备份到 S3: s3://$S3_BUCKET/$S3_PREFIX$filename"
        
        if aws s3 cp "$BACKUP_FILE" "s3://$S3_BUCKET/${S3_PREFIX}$filename" --quiet; then
            log_success "S3 上传成功"
        else
            log_error "S3 上传失败"
            send_notification "WARN" "备份文件 S3 上传失败: $filename"
        fi
    fi
    
    # SCP 上传到远程服务器
    if [[ -n "$REMOTE_HOST" ]] && [[ -n "$REMOTE_PATH" ]]; then
        log_info "上传备份到远程服务器: $REMOTE_HOST:$REMOTE_PATH"
        
        if scp -q "$BACKUP_FILE" "${REMOTE_USER:-root}@$REMOTE_HOST:$REMOTE_PATH/$filename"; then
            log_success "远程服务器上传成功"
        else
            log_error "远程服务器上传失败"
            send_notification "WARN" "备份文件远程上传失败: $filename"
        fi
    fi
    
    # rsync 同步到远程服务器
    if [[ -n "$RSYNC_DEST" ]]; then
        log_info "同步备份到远程: $RSYNC_DEST"
        
        if rsync -az "$BACKUP_FILE" "$RSYNC_DEST/"; then
            log_success "rsync 同步成功"
        else
            log_error "rsync 同步失败"
            send_notification "WARN" "备份文件 rsync 同步失败: $filename"
        fi
    fi
}

# 安装定时任务
install_cron() {
    log_info "安装每日备份定时任务..."
    
    SCRIPT_PATH=$(readlink -f "$0")
    CRON_ENTRY="0 2 * * * $SCRIPT_PATH full >> $LOG_FILE 2>&1"
    
    # 检查是否已存在
    if crontab -l 2>/dev/null | grep -q "$SCRIPT_PATH"; then
        log_warn "定时任务已存在"
        crontab -l | grep "$SCRIPT_PATH"
        return 0
    fi
    
    # 添加定时任务
    (crontab -l 2>/dev/null; echo "$CRON_ENTRY") | crontab -
    
    if [[ $? -eq 0 ]]; then
        log_success "定时任务安装成功"
        log_info "每日凌晨 2:00 执行全量备份"
        log_info "Cron 条目: $CRON_ENTRY"
    else
        log_error "定时任务安装失败"
        exit 1
    fi
}

# 卸载定时任务
uninstall_cron() {
    log_info "卸载备份定时任务..."
    
    SCRIPT_PATH=$(readlink -f "$0")
    
    if ! crontab -l 2>/dev/null | grep -q "$SCRIPT_PATH"; then
        log_warn "未找到相关定时任务"
        return 0
    fi
    
    crontab -l | grep -v "$SCRIPT_PATH" | crontab -
    
    log_success "定时任务已卸载"
}

# 列出所有备份
list_backups() {
    echo
    echo "============================================"
    echo "备份文件列表"
    echo "============================================"
    echo "备份目录: $BACKUP_DIR"
    echo
    
    if [[ ! -d "$BACKUP_DIR" ]] || [[ -z "$(ls -A "$BACKUP_DIR" 2>/dev/null)" ]]; then
        log_warn "备份目录为空或不存在"
        return 0
    fi
    
    echo "文件列表 (按时间倒序):"
    echo "----------------------------------------"
    ls -lht "$BACKUP_DIR"/sism_*.sql.gz 2>/dev/null | awk '{print $9, $5, $6, $7, $8}'
    echo "----------------------------------------"
    echo
    echo "总文件数: $(find "$BACKUP_DIR" -name "sism_*.sql.gz" | wc -l)"
    echo "总占用空间: $(du -sh "$BACKUP_DIR" 2>/dev/null | cut -f1)"
    echo "============================================"
}

# 显示帮助
show_help() {
    echo "SISM 数据库备份脚本"
    echo
    echo "用法: $0 [选项]"
    echo
    echo "备份选项:"
    echo "  full    - 全量备份 (默认)"
    echo "  schema  - 仅备份 Schema"
    echo "  data    - 仅备份数据"
    echo
    echo "管理选项:"
    echo "  stats   - 显示备份统计"
    echo "  list    - 列出所有备份文件"
    echo "  cron    - 安装每日备份定时任务"
    echo "  uncron  - 卸载备份定时任务"
    echo "  help    - 显示帮助"
    echo
    echo "环境变量 - 数据库连接:"
    echo "  DB_HOST              - 数据库主机 (默认: localhost)"
    echo "  DB_PORT              - 数据库端口 (默认: 5432)"
    echo "  DB_NAME              - 数据库名称 (默认: sism_prod)"
    echo "  DB_USER              - 数据库用户 (默认: sism_user)"
    echo "  DB_PASSWORD          - 数据库密码"
    echo
    echo "环境变量 - 备份配置:"
    echo "  BACKUP_DIR           - 备份目录 (默认: /var/backups/sism)"
    echo "  BACKUP_RETENTION_DAYS - 备份保留天数 (默认: 30)"
    echo "  LOG_FILE             - 日志文件路径"
    echo
    echo "环境变量 - 远程存储 (可选):"
    echo "  S3_BUCKET            - AWS S3 存储桶名称"
    echo "  S3_PREFIX            - S3 对象前缀 (默认: 空)"
    echo "  REMOTE_HOST          - 远程服务器地址 (SCP)"
    echo "  REMOTE_USER          - 远程服务器用户 (默认: root)"
    echo "  REMOTE_PATH          - 远程服务器路径"
    echo "  RSYNC_DEST           - rsync 目标地址"
    echo
    echo "环境变量 - 告警通知 (可选):"
    echo "  WEBHOOK_URL          - Webhook 通知 URL"
    echo "  NOTIFY_EMAIL         - 邮件通知地址"
    echo
    echo "示例:"
    echo "  $0 full                          # 执行全量备份"
    echo "  $0 schema                        # 仅备份 Schema"
    echo "  $0 cron                          # 安装每日定时备份"
    echo "  DB_PASSWORD=xxx $0               # 指定密码执行备份"
    echo "  S3_BUCKET=my-backups $0 full     # 备份并上传到 S3"
}

# 主函数
main() {
    # 记录开始时间
    START_TIME=$(date +%s)
    
    case "$BACKUP_TYPE" in
        full)
            ensure_backup_dir
            check_connection
            backup_full
            verify_backup
            upload_to_remote
            cleanup_old_backups
            show_stats
            send_notification "SUCCESS" "全量备份完成: $BACKUP_FILE"
            ;;
        schema)
            ensure_backup_dir
            check_connection
            backup_schema
            verify_backup
            upload_to_remote
            send_notification "SUCCESS" "Schema 备份完成: $BACKUP_FILE"
            ;;
        data)
            ensure_backup_dir
            check_connection
            backup_data
            verify_backup
            upload_to_remote
            send_notification "SUCCESS" "数据备份完成: $BACKUP_FILE"
            ;;
        stats)
            show_stats
            ;;
        list)
            list_backups
            ;;
        cron)
            install_cron
            ;;
        uncron)
            uninstall_cron
            ;;
        help|--help|-h)
            show_help
            exit 0
            ;;
        *)
            log_error "未知的备份类型: $BACKUP_TYPE"
            show_help
            exit 1
            ;;
    esac
    
    # 计算耗时
    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))
    log_info "备份耗时: ${DURATION}秒"
}

# 错误处理
trap 'log_error "备份过程中发生错误"; send_notification "FAILED" "备份失败，请检查日志"; exit 1' ERR

# 运行主函数
main "$@"
