# 2026-05 后端目录治理整理报告

## 当前状态

- 状态：`verified`
- 范围：`sism-backend` 仓库内目录与文档治理

## 本轮保留

- `docs/architecture/main-branch-release-and-deploy-runbook.md`
- `docs/flyway-migration-guide.md`
- `docs/workflow-test-guide.md`
- 4 份 `PLAN_*` 审批测试清单
- `docs/用户账号密码文档.md`
- `docs/frontend-permission-control.md`

## 本轮归档

- `docs/archive/2026-05-legacy-guides-and-reports/DOCKER_MEMORY_GUIDE.md`
- `docs/archive/2026-05-legacy-guides-and-reports/STRESS_TEST_PLAN.md`
- `docs/archive/2026-05-legacy-guides-and-reports/demo-approval-verification-report.md`
- `docs/archive/2026-05-legacy-guides-and-reports/oss-storage-implementation-plan.md`
- `docs/archive/2026-05-legacy-guides-and-reports/邮箱手机号存储技术设计方案.md`

## 本轮新增治理入口

- `docs/README.md`
- `docs/PROJECT_HISTORY_AND_GOVERNANCE.md`

## 删除说明

- 本轮未直接删除后端仓库内文档文件。
- 扫描结果显示，适合退出主目录的旧材料均具备追溯价值，因此统一转入 archive。

## 验证结果

- 后端构建校验通过：
  - `./mvnw -pl sism-main -am package -DskipTests`

## 备注

- 根 `README.md` 已增加文档入口说明。
- 当前自动部署入口仍为 `main`，本轮未修改工作流触发条件。
