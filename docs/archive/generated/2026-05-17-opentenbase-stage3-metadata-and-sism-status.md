# OpenTenBase 单机集群元数据收尾与 SISM 接入状态

日期：2026-05-17

## 本轮已完成

- 远端 `47.108.249.115` 上的 OpenTenBase 单机集群已恢复到可稳定运行状态：
  - GTM `6666`
  - CN `30004`
  - DN `20008`
- 已确认 `pgxc_node` 元数据正常：
  - `cn001`
  - `dn001`
  - `gtm`
- 已完成默认组与分片组收尾：
  - `CREATE DEFAULT NODE GROUP default_group WITH (dn001);`
  - `CREATE SHARDING GROUP TO GROUP default_group;`
  - `CLEAN SHARDING;`
- 已确认 `pgxc_group` 已落地：
  - `default_group|1|16384`
- 本地 SISM 到 OpenTenBase 的 JDBC 接入链已打通：
  - `validate-opentenbase-connection.sh` 可成功连通
  - Spring Boot 已能进入 Flyway 真正执行迁移阶段

## 已确认的易错点

- `pgxc_ctl` 不能长时间挂着做反复控制，否则会干扰 GTM，导致 `invalid global timestamp`。
- `.env` 中 `DB_URL` 必须使用双引号，不能用单引号，否则 `dotenv-java` 解析后会让 JDBC URL 失效。
- `default group not defined` 不是 JDBC 问题，而是 OpenTenBase 集群元数据未完成收尾。
- Maven 的 Flyway CLI 仍有本机插件冲突，当前应以 Spring Boot 内嵌 Flyway 结果为准。

## 当前最新阻塞

SISM 不再卡在连接或默认组，而是卡在 `V1__baseline_current_schema.sql` 与 OpenTenBase 分布式约束规则不兼容。

### 已修掉的第一处兼容问题

- 原语句：
  - `SET default_table_access_method = heap;`
- 问题：
  - OpenTenBase 5.0 基于 PostgreSQL 10，不支持该参数。
- 处理：
  - 已在本地迁移脚本中去掉该语句。

### 当前新的真实阻塞

- 报错：
  - `ERROR: Unique index of distributed table must contain the distribution column.`
- 首个触发点：
  - `sism-main/src/main/resources/db/migration/V1__baseline_current_schema.sql:2370`
- 对应语句：
  - `ALTER TABLE ONLY public.audit_flow_def ADD CONSTRAINT audit_flow_def_flow_code_key UNIQUE (flow_code);`

## 含义

当前 OpenTenBase 已进入“业务迁移兼容”阶段，而不是“数据库启动失败”阶段。

也就是说：

- RPM 不足、源码修复、GTM 启动、默认组缺失，这几个底层问题已经跨过去了。
- 下一步要处理的是 SISM 基线库表在 OpenTenBase 单机分布式语义下的建表/唯一约束兼容。

## 建议的下一步最短路径

优先处理 `V1__baseline_current_schema.sql` 中这类“唯一约束未包含分布列”的表。

建议顺序：

1. 先梳理 `UNIQUE (...)` 和 `CREATE UNIQUE INDEX` 的全部位置。
2. 对配置型/字典型/关联型表优先评估改为复制表。
3. 对真正的大表再决定是：
   - 调整分布列
   - 调整唯一约束
   - 或拆分为更适合 OpenTenBase 的分布方式

目前已识别的高风险对象包括：

- `audit_flow_def`
- `sys_permission`
- `sys_role`
- `sys_user`
- `refresh_tokens`
- `idempotency_records`
- `sys_org`
- `warn_level`
- `attachment`
- `plan_report`
- `plan_report_indicator`
- `sys_role_permission`
- `sys_user_role`

