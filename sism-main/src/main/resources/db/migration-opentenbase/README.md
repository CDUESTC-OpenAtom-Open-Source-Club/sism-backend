# OpenTenBase Baseline

该目录只用于 OpenTenBase 专用 Flyway 基线。

规则：

- PostgreSQL 默认链路继续使用 `db/migration/`
- OpenTenBase 专用链路使用 `db/migration-opentenbase/`
- 不再把 OpenTenBase 兼容语义直接补进共享 `V1`
- 当 OpenTenBase 需要不同的表分布、约束、触发器或外键策略时，只在本目录维护

当前状态：

- 已从共享 `V1` 复制出独立的 `V1__baseline_current_schema.sql`
- 下一步按 OpenTenBase 分布式限制继续拆：
  - 复制表与分布表边界
  - trigger 支持边界
  - foreign key 支持边界
  - unique/index 约束边界
