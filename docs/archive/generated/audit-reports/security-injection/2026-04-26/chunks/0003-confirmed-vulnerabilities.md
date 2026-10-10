## 03 已确认漏洞与缺陷

### 1. 错误信息直接回显给客户端

**风险等级：高**

`GlobalExceptionHandler.java` 在多个异常处理分支中直接把 `e.getMessage()` 作为 API 错误消息返回，例如：

- 未授权、认证失败、鉴权失败
- 非法参数、非法状态
- 资源未找到、工作流异常
- 技术异常与通用异常

`IndicatorController.java` 也存在在捕获 `IllegalStateException` / `IllegalArgumentException` 后直接回传 `ex.getMessage()` 的情况。

#### 风险说明

- 会把内部对象 ID、状态机信息、流程节点信息、权限判定结果等细节暴露给前端。
- 攻击者可以利用这些细节做下一步枚举、绕过和定向构造请求。
- 即使不形成注入，也属于典型的**信息泄露漏洞**。

#### 受影响文件

- `sism-shared-kernel/.../GlobalExceptionHandler.java`
- `sism-strategy/.../IndicatorController.java`

### 2. 输入校验覆盖不一致，入口基线不统一

**风险等级：中高**

`AuthController.java` 的登录、注册入口当前是：

- `login(@RequestBody LoginRequest request)`
- `register(..., @RequestBody RegisterRequest request)`

但 `LoginRequest.java` 与 `AuthController.java` 内部 `RegisterRequest` 基本没有 Bean Validation 约束；这意味着用户名、密码、姓名等字段缺少统一的空值、长度和格式校验。

`IndicatorController.java` 中 `updateIndicator(@RequestBody UpdateIndicatorRequest request)`、`rejectIndicator(@RequestBody RejectRequest request)` 也没有形成统一的 `@Valid` 约束链；内部 DTO 中不少字符串、数值字段缺少长度和边界规则。

`RoleManagementController.java` 虽然在入口上使用了 `@Valid`，但 `CreateRoleRequest` 里关键字段用的是 `@lombok.NonNull`，这不是 Jakarta Bean Validation，不能替代统一的请求参数校验。

#### 风险说明

- 非法值、超长值、畸形值会更容易穿透控制器层。
- 将来一旦把这些字段用于拼接日志、表达式、导出、模糊搜索或外部系统调用，会成为新的注入面放大器。
- 这类问题往往不是单点爆炸，而是长期削弱全系统输入边界。

#### 受影响文件

- `sism-iam/.../AuthController.java`
- `sism-iam/.../LoginRequest.java`
- `sism-iam/.../RoleManagementController.java`
- `sism-strategy/.../IndicatorController.java`

### 3. 动态 SQL 标识符拼接属于潜在注入埋雷点

**风险等级：中**

`DatabaseDataChecker.java` 中存在 `"SELECT COUNT(*) FROM " + table` 这类动态拼接表名的实现。

#### 风险说明

- 当前看更像内部运维/校验工具，不像直接对外暴露接口。
- 但它已经建立了“运行时拼接 SQL 标识符”的模式，一旦未来把表名来源接到配置、请求或脚本输入，就可能演变成真正的 SQL 注入点。
- 这类代码很容易在后续维护中被复制到业务查询中，属于典型的**潜在漏洞源头**。

#### 受影响文件

- `sism-shared-kernel/.../DatabaseDataChecker.java`
