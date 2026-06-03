# SISM Backend 文档中心

本目录只保留后端当前仍直接服务于开发、测试、发布、部署的执行文档，并将一次性方案、阶段报告、专项历史材料统一收口到 `archive/`。

## 当前状态

- 当前仓库文档治理状态：`ready_for_verification`
- 当前代码目录与文档结构已完成一轮规范化整理
- 本目录作为后端文档唯一入口，后续新增文档必须先判断分类

## 文档分类规则

### 1. 当前执行文档

满足以下任一条件的文档，保留在 `docs/` 根目录：

- 当前仍直接约束代码实现、数据库迁移、测试口径或账号口径
- 当前仍直接约束发布、部署、运维排查
- 当前 README、脚本、工作流会直接引用

当前保留文档：

- `architecture/main-branch-release-and-deploy-runbook.md`
  - 主线发布、自动部署、服务器验证、故障排查入口
- `deployment-image-arch-note.md`
  - 生产服务器镜像架构要求与 2026-05-27 Docker 替换复盘
- `flyway-migration-guide.md`
  - 当前 Flyway 迁移执行规范
- `workflow-test-guide.md`
  - 当前审批联调与 smoke 测试入口
- `PLAN_DISPATCH_STRATEGY-前端测试清单.md`
- `PLAN_DISPATCH_FUNCDEPT-前端测试清单.md`
- `PLAN_APPROVAL_FUNCDEPT-前端测试清单.md`
- `PLAN_APPROVAL_COLLEGE-前端测试清单.md`
  - 当前审批链逐条测试清单
- `用户账号密码文档.md`
  - 当前测试账号与联调账号口径
- `frontend-permission-control.md`
  - 当前前后端权限分流说明

### 2. 可再生成文档

可由工具、脚本、审计流程重复生成的文档，统一放在 `docs/generated/`：

- 审计报告
- 性能报告
- 报告索引
- 机器生成的说明文件

当前入口：

- `generated/README.md`
- `generated/CONVENTION.md`
- `generated/reports-index.json`

### 3. 历史归档文档

以下文档统一进入 `docs/archive/`：

- 阶段性验收报告
- 一次性设计方案
- 已完成专项排查与历史指南
- 不再直接约束当前实现，但仍有追溯价值的材料

归档目录统一采用：

- `docs/archive/YYYY-MM-topic-slug/`

当前归档目录：

- `archive/2026-05-legacy-guides-and-reports/`
  - `DOCKER_MEMORY_GUIDE.md`
  - `STRESS_TEST_PLAN.md`
  - `demo-approval-verification-report.md`
  - `oss-storage-implementation-plan.md`
  - `邮箱手机号存储技术设计方案.md`

## 项目历史与治理

完整的目录治理规则、状态流转、保留/归档/删除标准见：

- `PROJECT_HISTORY_AND_GOVERNANCE.md`

该文档是本仓库“规范项目历史”的主入口，而不是按时间堆积阶段文档。

## 删除标准

满足以下条件的旧文档应直接删除，而不是继续保留：

1. 已被新版本文档完整覆盖。
2. 不再被 README、脚本、工作流或运行手册引用。
3. 不再直接约束当前代码、测试、部署或账号口径。
4. 没有归档追溯价值，只是过期草稿或重复材料。

## 验证门禁

目录与文档整理完成后，后端侧至少通过以下验证：

- 文档入口检查：`docs/README.md` 与根 `README.md` 链接无断裂
- 构建门禁：`./mvnw -pl sism-main -am package -DskipTests`
- 若本轮涉及 Java 或资源目录行为，再补 `./mvnw -pl sism-main -am test`

## 维护规则

1. 新增文档前，必须先判断属于“当前执行文档 / 可再生成文档 / 历史归档文档”中的哪一类。
2. 不允许再把一次性报告直接堆在 `docs/` 根目录。
3. 被归档文档若仍被引用，必须先修正引用路径。
4. OpenAPI 权威产物仍以仓库根目录产物为准，不在 `docs/` 下复制维护。
