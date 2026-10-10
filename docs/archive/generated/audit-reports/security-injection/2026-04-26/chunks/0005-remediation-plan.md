## 05 整改方案

### 1. 第一优先级：收敛错误信息输出

必须统一禁止在 API 响应里直接返回 `e.getMessage()` / `ex.getMessage()`。

建议改造原则：

- 对外只返回固定业务文案或通用错误文案
- 在响应里附带 `requestId`
- 详细异常仅记录日志，不进入客户端返回体
- 对认证失败、鉴权失败、资源不存在、非法状态分别定义稳定错误码

### 2. 第二优先级：建立统一输入校验基线

建议把控制器层改成统一规范：

- 控制器类统一加 `@Validated`
- 所有 `@RequestBody` DTO 默认配套 `@Valid`
- 所有关键字符串字段补 `@NotBlank`、`@Size`、必要时补 `@Pattern`
- 所有分页、排序、数值、状态字段补 `@Min`、`@Max`、枚举白名单或自定义校验
- 把 `@lombok.NonNull` 从“校验手段”退回为“代码语义提示”，真正校验仍使用 Jakarta Validation

优先改造对象：

- `LoginRequest.java`
- `AuthController.java` 内部 `RegisterRequest`
- `IndicatorController.java` 内部 `UpdateIndicatorRequest`、`RejectRequest`
- `RoleManagementController.java` 内部角色相关请求 DTO

### 3. 第三优先级：消除动态 SQL 埋雷点

对 `DatabaseDataChecker.java` 这类实现，应改成：

- 固定白名单表名映射
- 不允许外部传入任意标识符
- 工具类与业务查询分离，避免示范效应

如果确实需要动态对象名，应先做白名单映射，再拼接已经验证过的常量值，而不是直接透传字符串。

### 4. 第四优先级：做生产配置硬化

- 生产环境禁用 Swagger UI 和开放式 API Docs
- 明确限制 Actuator 暴露项，只保留必要健康检查
- 禁止生产环境使用数据库兜底凭据
- 核验 PostgreSQL 账号只具备必要的 `SELECT/INSERT/UPDATE/DELETE` 权限
- 审查导出、附件、审计表等敏感表的访问边界
