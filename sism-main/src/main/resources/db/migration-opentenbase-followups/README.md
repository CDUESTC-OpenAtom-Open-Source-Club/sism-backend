# OpenTenBase Follow-up Migrations

该目录保存 OpenTenBase 专用链路在 `V1` 之后继续执行的迁移。

当前做法：

- 先从共享 `db/migration/` 复制 `V2+`
- 再按 OpenTenBase 实际执行报错逐条裁剪或改写
- PostgreSQL 主链路继续只使用原目录，不受本目录影响

执行顺序：

1. `db/migration-opentenbase/` 里的专用 `V1`
2. `db/migration-opentenbase-followups/` 里的 `V2+`
