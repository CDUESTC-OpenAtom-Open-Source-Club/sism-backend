## 04 配置级风险、低风险项与未发现项

### 1. 配置级安全收口仍需加强

#### 默认数据库兜底凭据不应在正式环境生效

`application.yml` 中存在：

- `${DB_USERNAME:sism_user}`
- `${DB_PASSWORD:sism_pass}`

这类默认值在本地开发阶段可以接受，但在正式环境里应明确禁止回落到兜底凭据，否则会削弱最小权限与凭据治理要求。

#### Swagger / Actuator 放行策略需要按环境收口

`SecurityConfig.java` 当前对 `/swagger-ui/**`、`/v3/api-docs/**`、`/actuator/**` 等路径做了 `permitAll()`。

这不等于立即存在漏洞，但如果生产环境缺少额外网关限制、Profile 开关或 Actuator 暴露面收缩，就会形成额外的信息暴露风险。

### 2. 低风险但应持续约束的实现

#### 固定常量拼接 SQL

`NativeDashboardSummaryQueryRepository.java` 存在视图名、列名常量拼接 SQL 的方式。当前这些值来自固定常量，不是用户输入，因此**不判定为直接 SQL 注入漏洞**；但后续仍应坚持常量白名单，不要把此模式扩散到业务动态字段。

#### 反序列化风险目前偏低

`RedisConfig.java`、`CacheUtilsObjectMapperConfig.java` 未看到开启 Jackson 默认多态；`EventStoreDatabase.java` 的事件恢复也更接近按固定事件类型反序列化，而不是任意类型反序列化。

这意味着当前**未发现典型的危险反序列化链**，但前提是数据库与缓存内容本身可信、且后续不引入 `enableDefaultTyping` / `activateDefaultTyping` 之类配置。

### 3. 当前未发现直接证据的漏洞类别

基于本轮静态代码排查，当前未发现明确证据支持以下漏洞已形成可利用实现：

- NoSQL 注入
- 命令注入
- LDAP 注入
- XPath 注入
- XML 外部实体注入（XXE）
- 模板注入

需要说明的是：**“当前未发现”不等于“永久不存在”**，而是表示这轮代码审计没有在现有主运行时代码中找到明显入口。
