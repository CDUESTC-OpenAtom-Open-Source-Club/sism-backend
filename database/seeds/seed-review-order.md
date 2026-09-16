# Clean Seed Review Order

按单表审核/执行时，建议按下面顺序：

1. `sys_org-data.sql`
2. `sys_user-data.sql`
3. `sys_role-data.sql`
4. `sys_permission-data.sql`
5. `audit_flow_def-data.sql`
6. `sys_role_permission-data.sql`
7. `sys_user_role-data.sql`
8. `audit_step_def-data.sql`
9. `cycle-data.sql`
10. `plan-data.sql`
11. `sys_task-data.sql`
12. `indicator-data.sql`
14. `warn_level-data.sql`
15. `alert_window-data.sql`
16. `alert_rule-data.sql`
17. `plan_report-data.sql`
18. `plan_report_indicator-data.sql`
19. `attachment-data.sql`
20. `plan_report_indicator_attachment-data.sql`
21. `alert_event-data.sql`
22. `audit_instance-data.sql`
23. `audit_step_instance-data.sql`
24. `workflow_task-data.sql`
25. `workflow_task_history-data.sql`
26. `progress_report-data.sql`
27. `audit_log-clean.sql`
28. `password_reset_tokens-clean.sql`
29. `progress_report-clean.sql`
30. `refresh_tokens-clean.sql`

说明：

- 以上文件是按当前审批/RBAC/填报/预警主链路整理出来的“干净种子”。
- `sys_role-and-role_permission-review.sql` 与 `sys_user_role-review.sql` 保留为历史 review 草案，当前单表审核优先看 `*-data.sql`。
- 本轮故意没有把数据库里的脏值、历史冗余关系、无关账号全量搬进来，只保留支撑当前流程闭环所需的最小集合。
- `audit_log` 仍然属于历史审计痕迹，默认不纳入主链路种子；如需重置历史痕迹，可执行 `audit_log-clean.sql`。
- `progress_report` 在当前代码中实际对应 analytics 模块的分析报告实体，和 `plan_report` 不是同一个业务概念；如需重置分析报告输出数据，可先执行 `progress_report-clean.sql` 再执行 `progress_report-data.sql`。
- `refresh_tokens` 属于登录态运行时表，不建议生成固定种子；如需清空认证会话残留，可执行 `refresh_tokens-clean.sql`。
- `password_reset_tokens` 属于运行时密码找回验证码表，不建议生成固定种子；如需清空找回验证码残留，可执行 `password_reset_tokens-clean.sql`。
