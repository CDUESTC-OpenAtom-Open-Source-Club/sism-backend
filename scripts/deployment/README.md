# SISM Backend Deployment Scripts

本目录只保留**当前仍建议长期使用**的后端部署与运维脚本。

当前 Docker Compose / GitHub Actions 生产换机流程见：

- `docs/Ubuntu-24.04-生产换机部署手册.md`
- `bootstrap-docker-host.sh`
- `validate-docker-host.sh`
- `validate-compose-config.sh`
- `download-offline-release.sh`
- `upload-offline-release.sh`
- `install-offline-release.sh`

正常推送 `main` 时，GitHub Actions 默认只生成保留 7 天的镜像 artifact，
不会连接服务器。只有在手动运行工作流并显式选择
`delivery_target=test-server` 时，才允许使用测试服务器的自托管 Runner。

离线发布流程：

```bash
./scripts/deployment/download-offline-release.sh --output "$HOME/Downloads/sism-releases"
./scripts/deployment/upload-offline-release.sh \
  --archive "$HOME/Downloads/sism-releases/sism-offline-<版本>.tar.gz" \
  --remote root@目标服务器
```

目标服务器只需要 Docker、Compose、SSH 和已经迁移好的生产 `.env`，不需要
访问 GitHub 或 GHCR。完整说明见 `docs/部署运维/SISM-离线人工发布手册.md`。

## 当前保留

### `deploy.sh`
服务器上的通用部署脚本，用于：

- 更新部署 JAR 软链接
- 重启 `sism-backend` 服务
- 进行启动后的健康检查

### `setup-server.sh`
一次性服务器初始化脚本，用于：

- 配置部署账号的最小 sudo 权限
- 设置 `/opt/sism/backend` 目录权限
- 验证部署账号是否具备重启服务和写目录能力

### `health-check.sh`
后端部署后的健康检查脚本，用于快速确认：

- Spring Boot 服务可达
- Actuator 健康端点返回正常
- 数据库连接可用
- 磁盘空间未达到危险阈值

### 当前生产 Gate 要求

- CI 中 `mvn test` 必须阻塞失败
- 发布前必须执行 Flyway `validate` 和 `migrate`
- 发布成功判定必须基于健康端点 `HTTP 200` 且返回 `status=UP`

### `backup-database.sh`
PostgreSQL 备份脚本，适用于生产或受控测试环境的例行备份。

### `restore-database.sh`
PostgreSQL 恢复脚本，适用于从备份文件恢复数据库；内部校验已对齐当前 `sys_* / cycle / indicator / sys_task` 表结构。

配套文档：

- [DATABASE-ROLLBACK-SOP.md](/Users/blackevil/战略开发/sism-backend/scripts/deployment/DATABASE-ROLLBACK-SOP.md)
- [PRODUCTION-RELEASE-CHECKLIST.md](/Users/blackevil/战略开发/sism-backend/scripts/deployment/PRODUCTION-RELEASE-CHECKLIST.md)
- [PREPROD-READINESS.md](/Users/blackevil/战略开发/sism-backend/scripts/deployment/PREPROD-READINESS.md)

## 不再保留的脚本

以下脚本已从本目录移除，因为它们已经不符合长期维护标准：

- `init-database.sh`：已被 Flyway 初始化流程与 `database/scripts/` 替代
- `quick-setup.sh`：包含环境绑定信息，不适合作为公共脚本长期保留

## 推荐用法

### 本地开发数据库

```bash
./mvnw flyway:migrate
./database/scripts/reset-clean-seeds.sh
```

### 服务器部署

```bash
# 一次性初始化部署账号
sudo ./scripts/deployment/setup-server.sh <deploy-user>

# 确保 /opt/sism/.env 已提供 DB_URL / DB_USERNAME / DB_PASSWORD

# 服务器上完成 JAR 替换后执行部署
./scripts/deployment/deploy.sh sism-backend-1.0.0.jar
```

### 部署后检查

```bash
./scripts/deployment/health-check.sh
```

### 回滚 SOP

发生数据库级回滚时，不要口头执行或临场决策，必须按以下文档操作：

```bash
cat ./scripts/deployment/DATABASE-ROLLBACK-SOP.md
```

### 正式发布检查清单

每次正式发布或回滚前，必须逐项勾选：

```bash
cat ./scripts/deployment/PRODUCTION-RELEASE-CHECKLIST.md
```

## 维护原则

1. 本目录只放可重复执行的部署/运维脚本。
2. 新脚本不得写死密码、固定业务 ID 或历史库表结构；目录、服务名等仓库级约定可保留为默认值，但需支持环境变量覆盖。
3. 任何一次性排障脚本都不应长期留在这里。
4. 如果部署流程变化，优先同步更新本目录 README 与 CI 工作流。
