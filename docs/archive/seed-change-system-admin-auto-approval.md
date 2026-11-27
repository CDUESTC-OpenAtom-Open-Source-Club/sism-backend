# 系统管理员自动审批种子变更记录

更新时间：2026-06-05

## 背景

导入业务表格时支持勾选“导入后自动发起并完成审批”。自动审批需要一个技术执行账号完成系统动作，但该账号不能同时充当人工业务审批席位，否则会和真实的分管校领导角色混淆。

## 账号与角色

- `admin`：系统管理员专用账号，角色为 `ROLE_SYSTEM_ADMIN`，用于系统维护和导入后的系统自动审批。
- `zlb_vp1`：战略发展部真实分管校领导账号，角色为 `ROLE_VICE_PRESIDENT`，用于人工业务审批。

默认密码仍为 `admin123`。

## 已更新的种子文件

- `database/seeds/sys_role-data.sql`
  - 新增 `ROLE_SYSTEM_ADMIN`，id 为 `5`。
- `database/seeds/sys_user-data.sql`
  - 保留 `admin` 为系统管理员账号。
  - 新增 `zlb_vp1` 作为战略发展部真实分管校领导账号。
- `database/seeds/sys_user_role-data.sql`
  - `admin` 绑定 `ROLE_SYSTEM_ADMIN`。
  - `zlb_vp1` 绑定 `ROLE_VICE_PRESIDENT`。
- `database/seeds/sys_role_permission-data.sql`
  - `ROLE_SYSTEM_ADMIN` 绑定当前全部权限。
- `docs/用户账号密码文档.md`
  - 记录 `admin` 与 `zlb_vp1` 的职责拆分。

## 生产/云端迁移

已有数据库环境应通过 Flyway 迁移同步：

- `sism-main/src/main/resources/db/migration/V85__split_system_admin_and_strategy_vp.sql`

全量重建或初始化环境应通过 clean seed 文件同步。

## 核验 SQL

```sql
select
  u.id,
  u.username,
  u.real_name,
  u.org_id,
  o.name as org_name,
  string_agg(r.role_code, ',' order by r.role_code) as roles
from sys_user u
left join sys_org o on o.id = u.org_id
left join sys_user_role ur on ur.user_id = u.id
left join sys_role r on r.id = ur.role_id
where u.username in ('admin', 'zlb_vp1')
group by u.id, u.username, u.real_name, u.org_id, o.name
order by u.username;
```

期望结果：

- `admin` 只有 `ROLE_SYSTEM_ADMIN`。
- `zlb_vp1` 只有 `ROLE_VICE_PRESIDENT`。

