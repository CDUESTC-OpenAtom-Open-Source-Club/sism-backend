# SISM Backend 项目历史与目录治理手册

## Summary

本文件定义 `sism-backend` 的目录治理规则、历史归档标准、状态流转和验证门禁，确保后端仓库在持续演进后仍然可以区分：

- 当前有效规范
- 可再生成产物
- 历史留档材料

## 当前开发阶段（2026-10-26）

- **维护模式**：只做本地开发、小功能与小界面迭代，不做大规模开发
- **审批链口径**：二级学院上报链为五节点；学院侧只设「填报人 + 审核人」两级席位，
  不设「分管校领导」（详见 `docs/用户账号密码文档.md` 与 runbook）
- **部署口径**：离线手动部署（三道安全门），无自动部署——见
  `docs/architecture/main-branch-release-and-deploy-runbook.md`
- 2026-10-26 文档治理：归档 OpenTenBase 实验指南与早期审批联调汇总、
  删除已被脚本取代的离线手册、清理本机路径泄漏与断链、role 2 更名「审核人」

## 当前目录结构

后端仓库当前主结构：

- `sism-main`
- `sism-shared-kernel`
- `sism-iam`
- `sism-organization`
- `sism-strategy`
- `sism-task`
- `sism-workflow`
- `sism-execution`
- `sism-analytics`
- `sism-alert`
- `database`
- `docs`
- `scripts`
- `deploy`
- `docker`

## 文档治理模型

### current

定义：

- 直接约束当前开发、测试、部署、账号、迁移的文档

放置位置：

- `docs/`

### generated

定义：

- 可由流程、脚本、审计或工具反复生成的产物

放置位置：

- `docs/generated/`

### archive

定义：

- 一次性设计稿
- 阶段性报告
- 历史专项排查
- 已完成但仍需留档的材料

放置位置：

- `docs/archive/YYYY-MM-topic-slug/`

### delete

定义：

- 无引用
- 无持续维护价值
- 已被新文档替代
- 不具备历史追溯必要性

处理方式：

- 直接删除，不保留空壳

## 项目状态流转

后端治理工作统一使用以下状态：

- `draft`
  - 还在梳理，未开始实施
- `in_progress`
  - 正在进行目录整理、归档、索引修复
- `ready_for_verification`
  - 整理已完成，等待门禁验证
- `verified`
  - 本地验证通过，可对外汇报
- `ready_for_release`
  - 你确认后允许提交并推送

当前本轮状态建议记为：

- `ready_for_verification`

## 当前保留原则

保留文档必须满足以下至少一条：

1. 当前 README 或脚本直接引用。
2. 当前发布部署流程直接依赖。
3. 当前测试链路直接依赖。
4. 当前数据库或权限行为仍需作为执行口径。

## 当前验证门禁

本仓库目录治理完成后，至少执行：

- `./mvnw -pl sism-main -am package -DskipTests`

若本轮涉及 Java、资源目录或运行逻辑：

- `./mvnw -pl sism-main -am test`

此外必须人工检查：

- `docs/README.md`
- 根 `README.md`
- 被引用的文档路径

## 发布前要求

本仓库当前发布入口为（合并到 `main` 只构建镜像，**不会自动部署**）：

- `main`

推送前必须确认：

1. 文档入口无断链。
2. 改动集中在目录治理、归档、索引或必要引用修复。
3. 本地验证通过。
4. 已向你完成本地汇报。
