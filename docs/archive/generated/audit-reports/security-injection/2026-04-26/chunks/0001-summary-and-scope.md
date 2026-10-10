## 01 摘要与排查范围

本次针对 `sism-backend` 做的是**面向注入风险与输入处理机制的静态安全审计**，目标是把已确认的安全缺陷、潜在风险和当前未发现项沉淀为后端漏洞文档。

### 本次覆盖的核心对象

- 认证与用户入口：`AuthController.java`、`RoleManagementController.java`、`LoginRequest.java`
- 组织与指标入口：`OrganizationController.java`、`OrgRequest.java`、`RenameOrgRequest.java`、`IndicatorController.java`
- 数据访问实现：`PlanWorkflowSnapshotQueryService.java`、`JdbcPlanReportIndicatorRepository.java`、`JdbcWorkflowApprovalMetadataQuery.java`、`JdbcWorkflowAuditSyncGateway.java`、`JpaWorkflowRepository.java`
- 共享安全基础设施：`GlobalExceptionHandler.java`、`SecurityConfig.java`、`RedisConfig.java`、`CacheUtilsObjectMapperConfig.java`
- 文件与导出链路：`AttachmentApplicationService.java`、`AttachmentController.java`、`AnalyticsFileStorageService.java`、`ExportService.java`

### 本次重点判断的风险面

- SQL 注入 / ORM 注入 / 动态查询构造
- NoSQL 注入 / 命令注入 / LDAP 注入 / XPath 注入
- 文件路径注入 / HTTP 头注入 / XML 外部实体注入（XXE）
- 模板注入 / 反序列化漏洞 / 错误信息泄露
- 所有用户输入参数的校验、过滤与最小权限边界

### 本次结论摘要

当前代码库**未发现已成型、可直接利用的高危注入链**；数据库主路径多数使用参数化查询，文件路径解析也有较明确的归一化与根目录约束。

但作为后端错误/漏洞文档，需要明确记录以下已确认问题：

- **高风险缺陷**：异常与业务错误信息直接回显给客户端，容易泄露内部状态、对象标识和权限边界。
- **中高风险缺陷**：API 入口参数校验覆盖不一致，部分控制器和 DTO 缺少 `@Valid`、`@Validated` 与 Bean Validation 约束。
- **中风险缺陷**：存在动态 SQL 标识符拼接点，虽然当前更像内部工具调用，但属于容易被扩散误用的埋雷实现。
- **配置级风险**：默认数据库兜底凭据、开放式 Swagger / Actuator 放行策略需要结合生产环境进一步收口。
