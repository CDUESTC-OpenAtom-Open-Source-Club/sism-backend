# SISM 数据库回滚标准操作流程（SOP）

适用对象：

- 生产环境数据库回滚
- 预发布环境数据库回滚
- 因部署失败、迁移异常、数据污染导致的受控恢复

适用脚本：

- [restore-database.sh](restore-database.sh)
- [backup-database.sh](backup-database.sh)

## 1. 启动回滚的前置条件

必须同时满足：

1. 已确认当前故障无法通过应用重新部署解决。
2. 已明确回滚目标备份文件。
3. 已通知业务方进入只读/停机窗口。
4. 已停止后端写入流量。
5. 已确认当前数据库会先做一次“回滚前备份”。

## 2. 回滚责任分工

- 发布负责人：决定是否执行回滚
- 数据库执行人：执行备份验证、恢复、结果校验
- 应用负责人：停止/恢复应用服务，执行 smoke
- 业务确认人：确认核心流程恢复

## 3. 回滚前检查

按顺序执行：

1. 确认目标备份文件存在。
2. 执行仅验证模式：

```bash
DB_PASSWORD=*** ./scripts/deployment/restore-database.sh /var/backups/sism/<backup>.sql.gz --verify
```

3. 检查当前数据库连接可用。
4. 停止后端写流量：

```bash
sudo systemctl stop sism-backend
```

5. 如前端仍在线，确保入口页已切到维护页或明确告知业务暂停操作。

## 4. 正式回滚步骤

执行命令：

```bash
DB_HOST=localhost \
DB_PORT=5432 \
DB_NAME=sism_prod \
DB_USER=<db_user> \
DB_PASSWORD=<db_password> \
./scripts/deployment/restore-database.sh /var/backups/sism/<backup>.sql.gz --force
```

脚本行为说明：

1. 校验备份文件存在。
2. 校验数据库连接。
3. 自动创建“恢复前备份”。
4. 断开目标数据库连接。
5. 删除并重建数据库。
6. 恢复 SQL 内容。
7. 校验关键表、表数量、外键完整性。

## 5. 回滚后恢复步骤

1. 重新启动后端服务：

```bash
sudo systemctl start sism-backend
```

2. 执行健康检查：

```bash
./scripts/deployment/health-check.sh
```

3. 执行后端 smoke：

- `GET /api/v1/auth/health`
- `GET /api/v1/actuator/health`
- 登录接口
- 1 条核心审批链查询接口

4. 执行前端 smoke：

- 登录页可访问
- 关键首页可加载
- 消息中心/指标页可打开

## 6. 回滚后必须确认的结果

必须确认以下结果后，才允许对业务宣布恢复：

1. 健康检查返回 `HTTP 200` 且 `status=UP`
2. 数据库关键表存在且记录数合理
3. 登录成功
4. 关键审批链可查询
5. 前端页面无 5xx/白屏
6. 业务确认核心功能恢复

## 7. 回滚失败时的处理

如果恢复失败：

1. 不要重复覆盖执行未知备份文件。
2. 保留控制台输出和日志。
3. 收集以下信息：
   - 备份文件名
   - 错误堆栈
   - `systemctl status sism-backend`
   - `journalctl -u sism-backend -n 200`
   - PostgreSQL 错误日志
4. 由发布负责人决定是否切换到上一个“回滚前备份”。

## 8. 禁止事项

1. 禁止在业务高峰期直接执行强制恢复。
2. 禁止在未验证备份完整性的情况下直接恢复。
3. 禁止绕过“回滚前备份”步骤。
4. 禁止恢复完成后不做 smoke 即恢复业务流量。
