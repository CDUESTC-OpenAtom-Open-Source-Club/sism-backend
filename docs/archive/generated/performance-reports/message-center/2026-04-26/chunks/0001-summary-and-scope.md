## 01 摘要与排查范围

本次只做**后端性能排查**，不改业务代码，优先选择了对用户体感最直接、同时又能放大上游压力的 `消息中心模块`。

### 本次覆盖的核心对象

- `MessageCenterController.java`
- `MessageCenterApplicationService.java`
- `MessageCenterModels.java`
- `WorkflowReadModelService.java`
- `UserNotificationRepository.java`
- `JpaUserNotificationRepository.java`
- `JpaWorkflowRepository.java`

### 为什么优先排查这个模块

- 它同时承接**摘要、列表、详情、已读**几类高频调用。
- 它聚合了**工作流待办**和**用户通知**两个上游源头，天然容易形成性能放大。
- 它既影响接口耗时，也影响前端页面初始化速度和消息刷新体验。

### 本次排查的结论摘要

当前消息中心最大的性能问题**不是单条 SQL 明显过慢**，而是整体实现偏向“先全量拉取，再在应用层聚合、过滤、排序、分页”。这种策略在数据量较小时可用，但用户消息量或审批量一旦上升，接口耗时、对象创建、内存占用和跨模块调用次数都会线性放大。
