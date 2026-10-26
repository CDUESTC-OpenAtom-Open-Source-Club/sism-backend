# Scripts 目录说明

`scripts/` 只保留**长期可复用、当前仍在维护**的后端辅助脚本。

## 当前保留的脚本分类

### 0. `dev/`
用于本地开发一键启动：

- `up-local.sh`：检查环境与数据库 → **询问是否灌种子（可选）** → 启动后端与前端 → 打印访问地址
  - `./scripts/dev/up-local.sh`（交互）/ `--seed` / `--no-seed` / `--stop`

### 1. `deployment/`
用于后端部署与运维：

- `deploy.sh`：服务器上的通用部署脚本
- `setup-server.sh`：部署账号与目录权限初始化
- `health-check.sh`：部署后健康检查
- `backup-database.sh`：PostgreSQL 备份
- `restore-database.sh`：PostgreSQL 恢复

### 2. `report-chunks/`
用于 `docs/generated/` 下分片报告的清单生成与正文重建：

- `build-manifest.js`
- `render-report.js`

### 3. `testing/`
只保留仍有长期价值的轻量校验脚本：

- `check-flyway-version-collisions.sh`：检查 Flyway 迁移版本号冲突

## 已清理的脚本类型

以下内容不再长期留在 `scripts/`：

- 一次性修复脚本
- 某次重构/排查专用的临时验证脚本
- 绑定历史账号、历史表结构、历史计划 ID 的脚本
- 含硬编码环境信息、不可复用的脚本
- 已被 Flyway、`database/scripts/` 或正式文档替代的脚本

## 维护约定

1. 新脚本进入本目录前，先判断它是否具备 **跨时间复用价值**。
2. 只服务于一次排查/一次迁移/一次报告生成的脚本，优先放在对应任务目录，完成后删除，不沉淀到公共脚本库。
3. 涉及数据库结构或种子数据初始化时，优先使用 Flyway 与 `database/scripts/`，不要再新增旧模型同步脚本。
4. 生成类文档统一优先写入 `docs/generated/`，对应工具只保留在 `report-chunks/`。
5. 本地初始化统一走种子数据与活跃迁移链，不再保留“从远程数据库同步到本地”的脚本。
