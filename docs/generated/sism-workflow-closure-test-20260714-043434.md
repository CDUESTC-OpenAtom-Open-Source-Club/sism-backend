# SISM 审批闭环与并发通知测试报告

- 生成时间: 2026-07-14 04:34:34
- 环境: `http://localhost:8080`
- 并发链数: 12
- 并发 worker: 6
- 结果: 3/4 通过，1 失败

## 检查项

| 结果 | 检查项 | 细节 |
|---|---|---|
| ✓ | 登录全部 9 个测试账号 | base=http://localhost:8080 |
| ✓ | 准备单闭环测试数据 | 4044 权重=100；404457 已补学院指标 |
| ✓ | 单闭环 数据地基权重校验 | [{"plan_id": 4044, "top_level": true, "owner_org_id": 35, "target_org_id": 44, "total_weight": "100.00", "cnt": 3}, {"plan_id": 404457, "top_level": false, "owner_org_id": 44, "target_org_id": 57, "total_weight": "100.00", "cnt": 2}] |
| ✗ | 测试执行异常 | RuntimeError('No pending task for admin, plan=4044, flow=PLAN_DISPATCH_STRATEGY. Latest=[]') |

## 说明

- Plan 业务状态枚举当前以 `DISTRIBUTED` 表示已下发/已审批后的终态；工作流终态通过 `workflowStatus=APPROVED` 和 audit/workflow 表验证。
- 本报告使用 API 推进审批，用 SQL 做本地测试数据准备和字段完整性校验。