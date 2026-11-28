# SISM 正式发布 / 回滚检查清单

使用方式：

- 发布前由发布负责人逐项确认
- 发布后由应用负责人补齐结果
- 回滚时切换到“回滚检查”部分执行

## 一、发布前检查

### 代码与分支

- [ ] 当前发布分支已冻结
- [ ] 发布提交 SHA 已记录
- [ ] 前后端基线分支一致并可追溯

### 构建与测试

- [ ] 前端 `npm test` 通过
- [ ] 前端 `npm run build` 通过
- [ ] 后端 CI 已通过且测试为阻塞项
- [ ] 后端关键回归/烟雾测试已通过

### 配置与安全

- [ ] `/opt/sism/.env` 中 `DB_URL/DB_USERNAME/DB_PASSWORD/JWT_SECRET` 已确认
- [ ] `SWAGGER_ENABLED=false`
- [ ] `PUBLIC_DOCS_ENABLED=false`
- [ ] `PUBLIC_ACTUATOR_ENABLED=false`
- [ ] nginx 已限制 `/api/v1/actuator/` 访问
- [ ] nginx 已禁止 Swagger / OpenAPI 对公网暴露

### 连接池容量（2026-11-27 看板 500 事故后新增）

事故现象：生产看板顶部弹红色「服务器内部错误」；后端日志
`SQLTransientConnectionException: Connection is not available, request timed out after 10000ms
(total=20, active=20, idle=0, waiting=24)`。

- [ ] `DB_HIKARI_MAX_POOL_SIZE` 已确认 ≥ 32（compose 兜底值已提到 32；
      生产若显式设过旧值 20/24，必须改掉，否则仍会覆盖新默认值）
- [ ] 变更后容器实际生效值已核对：
      `docker compose exec backend printenv DB_HIKARI_MAX_POOL_SIZE`
- [ ] 数据库侧 `max_connections` ≥ 连接池上限 + 10（PostgreSQL 默认 100，无需调整）
- [ ] 未在连接池打满期间改小该值（看板父指标归口水合已被前端限流到 6 并发，
      32 已是留足余量后的值，回收余量需同步确认前端限流仍然生效）

### 数据库

- [ ] 已确认当前备份可用
- [ ] `flyway:validate` 可通过
- [ ] 迁移版本号无重复
- [ ] 回滚目标备份文件已记录

### 发布窗口

- [ ] 已通知业务方发布时间窗口
- [ ] 已指定发布负责人
- [ ] 已指定数据库执行人
- [ ] 已指定故障升级联系人

## 二、发布执行检查

- [ ] 已完成构建产物生成
- [ ] 已完成 Flyway `validate`
- [ ] 已完成 Flyway `migrate`
- [ ] 已完成 JAR 替换
- [ ] 已完成服务重启
- [ ] 已执行健康检查，结果为 `HTTP 200` 且 `status=UP`

## 三、发布后 smoke 检查

### 后端

- [ ] `GET /api/v1/auth/health` 正常
- [ ] `GET /api/v1/actuator/health` 正常
- [ ] 登录接口正常
- [ ] 核心查询接口正常

### 前端

- [ ] 登录页可访问
- [ ] 核心页面可打开
- [ ] 静态资源无 404
- [ ] 控制台无明显 5xx 错误

### 业务链路

- [ ] 战略发展部主链 smoke 正常
- [ ] 至少 1 条职能部门链路正常
- [ ] 至少 1 条学院链路正常

## 四、回滚触发条件

满足任一条件可进入回滚决策：

- [ ] 健康检查无法恢复为 `status=UP`
- [ ] Flyway 迁移后出现不可接受错误
- [ ] 登录或主链功能不可用
- [ ] 业务确认关键功能受损

## 五、回滚执行检查

- [ ] 已停止后端写流量
- [ ] 已验证目标备份文件
- [ ] 已执行恢复前备份
- [ ] 已执行数据库恢复脚本
- [ ] 已重启后端服务
- [ ] 已执行恢复后 health check
- [ ] 已执行恢复后 smoke
- [ ] 已通知业务恢复情况
