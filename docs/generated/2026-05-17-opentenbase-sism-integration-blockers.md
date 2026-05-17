# OpenTenBase 接入阻塞记录

日期：2026-05-17

范围：记录 SISM 后端正式接入已启动 OpenTenBase 单机集群时的真实阻塞点，供后续开发与运维复用。

## 已验证成功的部分

### 数据库集群层

已验证 OpenTenBase 单机集群三角色可启动：

- `gtm` on `6666`
- `cn001` on `30004`
- `dn001` on `20008`

已验证节点信息可查询：

- `select node_name, node_type, node_host, node_port from pgxc_node order by node_name;`

### JDBC 与连接池层

已验证以下链路可用：

- 本地 SSH 隧道到远端 `30004`
- `pgjdbc` 直接连接 OpenTenBase 协调节点
- `Hikari + org.postgresql.Driver + jdbc:postgresql://127.0.0.1:33004/postgres...`

### SISM 应用层

已验证 Spring Boot 可以：

- 正确读取 `.env`
- 成功启动 Hikari
- 成功连接 OpenTenBase
- 成功进入 Flyway 执行阶段

## 已解决的问题

### 1. `.env` 中 DB_URL 被 shell 与 dotenv 解析不一致

问题：

- `DB_URL` 含 `&`
- shell `source` 与 `dotenv-java` 的解析方式不一致

现象：

- 预检脚本读不到完整 `DB_URL`
- Spring Boot 侧 URL 可能带错误引号或被截断

解决：

- 使用双引号形式保存 `DB_URL`

### 2. JDBC URL 被错误判定不被接受

问题：

- 实际不是 OpenTenBase URL 本身错误
- 而是 `.env` 被错误解析后传入了带引号或被截断的值

解决后验证：

- `org.postgresql.Driver.acceptsURL(...) == true`
- `Hikari` 最小探针可连通

## 当前真实阻塞点

### 阻塞 1：Flyway 首次建表时缺少默认节点组

现象：

- Spring Boot 成功连库后进入 Flyway
- 在创建 `flyway_schema_history` 时失败

报错：

- `ERROR: default group not defined`

含义：

- OpenTenBase 集群已可连接
- 但分布式元数据初始化未完成
- 还没有默认节点组与分片组，导致普通建表路径无法正常落地

### 阻塞 2：创建默认节点组时又被 prepared transactions 挡住

现象：

- 试图执行：
  - `CREATE DEFAULT NODE GROUP default_group WITH (dn001);`
  - `CREATE SHARDING GROUP TO GROUP default_group;`

报错：

- `ERROR: prepared transactions are disabled`

含义：

- 当前 CN/DN 运行参数中，`max_prepared_transactions` 仍不足以支撑该集群元数据初始化动作
- 这不是 SISM 业务 SQL 问题，而是 OpenTenBase 集群运行参数与集群元数据初始化顺序的问题

## 当前判断

现在的接入阶段不再被以下问题阻塞：

- 数据库无法启动
- JDBC 无法连通
- Hikari 无法初始化
- Spring Boot 无法访问数据库

当前唯一主阻塞已经收敛为：

- **先补齐 OpenTenBase 默认节点组 / 分片组**
- **并确保 `max_prepared_transactions` 在真实运行节点上生效**

只有这一步完成后，Flyway 才能真正开始建历史表和执行业务迁移。

## 下一步建议

1. 在当前已启动的 `GTM + CN + DN` 上，确认 CN 与 DN 运行时 `show max_prepared_transactions;`
   的真实值。
2. 若值仍为 `0`，则重新整理 CN/DN 配置并重启节点，确保非零值生效。
3. 成功创建：
   - `default_group`
   - `sharding group`
4. 然后重跑：
   - `./database/scripts/validate-opentenbase-connection.sh`
   - `mvn -pl sism-main spring-boot:run`
