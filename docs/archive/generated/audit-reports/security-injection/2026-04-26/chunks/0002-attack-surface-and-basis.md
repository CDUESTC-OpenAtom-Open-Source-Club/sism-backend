## 02 攻击面与判定依据

### 1. 数据访问主路径总体偏安全

本次排查到的主干数据库访问方式以 **JPA 命名参数**、`EntityManager.setParameter(...)`、`JdbcTemplate` `?` 占位符、`NamedParameterJdbcTemplate` 为主。

重点样本包括：

- `PlanWorkflowSnapshotQueryService.java`
- `JdbcPlanReportIndicatorRepository.java`
- `JdbcWorkflowApprovalMetadataQuery.java`
- `JdbcWorkflowAuditSyncGateway.java`
- `JpaWorkflowRepository.java`

这意味着：**主线 SQL/JPQL 查询没有出现大面积把用户原始输入直接拼进查询字符串的现象。**

### 2. 文件路径与导出链路已有较好约束

文件与导出相关代码里，`AttachmentApplicationService.java`、`AnalyticsFileStorageService.java`、`ExportService.java` 已做了以下控制：

- 路径 `normalize()` 归一化
- 基础目录约束
- `startsWith(root)` 逃逸检测
- 下载路径与持久化路径分离处理

因此当前**文件路径注入风险偏低**，这部分实现质量在本轮审计中相对较好。

### 3. 当前未发现明显使用的危险技术栈

在主运行时代码中，本次没有发现以下明确业务实现：

- `Runtime.exec(...)` / `ProcessBuilder` 一类命令执行链路
- 面向业务查询的 Mongo / Elasticsearch / Cassandra 等 NoSQL 查询入口
- `LdapTemplate` 或 LDAP 查询构造
- `XPathFactory` / `DocumentBuilderFactory` 等业务 XML 解析链路
- Thymeleaf / FreeMarker / Velocity 等服务端模板渲染入口

因此 **命令注入、NoSQL 注入、LDAP 注入、XPath 注入、XXE、模板注入** 在当前代码面上未见直接证据。

### 4. 不是所有“字符串拼接 SQL”都等价于高危 SQL 注入

本次也发现了部分字符串拼接 SQL 的实现，但要区分两类：

- **固定常量拼接**：例如 `NativeDashboardSummaryQueryRepository.java` 里基于固定视图名、固定列名常量拼接 SQL，这类实现可维护性一般，但当前**不是直接用户输入注入**。
- **动态标识符拼接**：例如 `DatabaseDataChecker.java` 里的表名拼接，这类实现即使现在多用于内部调用，也应视为**潜在安全缺陷**，必须改成白名单映射。

### 5. 统一入口校验仍是薄弱面

从控制器层看，校验风格并不统一：

- `OrganizationController.java` + `OrgRequest.java` / `RenameOrgRequest.java` 是相对规范的正例。
- `AuthController.java`、`IndicatorController.java`、`RoleManagementController.java` 的部分入口或 DTO 则存在明显缺口。

这意味着当前系统的安全性更多依赖“业务代码恰好做了处理”，而不是依赖稳定一致的输入校验基线。
