> **[已归档 2026-10-26]** 本文档为早期审批联调汇总，**已被 4 份 `PLAN_*-前端测试清单.md` 取代**（步骤名已按 2026-10-26 五节点链更新）。其中的账号命名与流程步骤可能过时。

# SISM 审批流程测试文档

## 概述

本文档提供 SISM 系统 4 个审批流程的完整测试指南，包括用户账号映射、测试场景和预期结果。

**测试环境**: http://localhost:8080
**默认密码**: 所有测试账号密码均为 `admin123`

**说明**:
- 本文档以 [`audit_step_def-data.sql`](../database/seeds/audit_step_def-data.sql) 的 14 个 canonical 步骤为准。
- 部分旧文档里的 `jwc_*`、`jsjxy_*`、`zlb_leader` 等旧账号命名已不再适用于当前 clean seed。
- 当前可直接复用的关键全局账号是 `admin`（战略发展部分管校领导）、`zlb_admin`（战略发展部填报人）、`zlb_final1` / `zlb_final2`（`ROLE_STRATEGY_DEPT_HEAD`，可处理战略发展部负责人/终审节点）。

---

## 流程 1: PLAN_DISPATCH_STRATEGY - Plan下发审批（战略发展部）

### 流程说明
战略发展部发起的 Plan 下发审批流程，需要经过战略发展部负责人审批和分管校领导审批。

### 审批步骤
1. **提交步骤** (SUBMIT) - 提交人自动完成
2. **战略发展部负责人审批** (APPROVAL) - 战略发展部负责人角色审批
3. **分管校领导审批** (APPROVAL) - 分管校领导角色审批

### 测试账号

| 角色 | 用户名 | 姓名 | 组织 | 说明 |
|------|--------|------|------|------|
| 发起人 | `zlb_admin` | 战略管理员 | 战略发展部 | 提交 Plan 下发申请 |
| 第二步审批人 | `zlb_final1` / `zlb_final2` | 战略发展部终审人1 / 战略发展部终审人2 | 战略发展部 | 当前 clean seed 中持有 `ROLE_STRATEGY_DEPT_HEAD`，可处理“战略发展部负责人审批”节点 |
| 第三步审批人 | `admin` | 系统管理员 | 战略发展部 | 分管校领导角色 |

### 测试场景

#### 场景 1.1: 正常审批通过
1. 使用 `zlb_admin` 登录
2. 创建 Plan 并提交下发申请，给教务处发送测试
3. 系统自动完成提交步骤，创建战略发展部负责人审批步骤
4. 切换为 `zlb_final1` 或 `zlb_final2` 处理第二步审批
5. 在"我的待办"中找到该审批任务，点击"通过"
6. 系统创建分管校领导审批步骤
7. 使用 `admin` 登录并通过审批
8. 验证 Plan 状态变为"已下发"

**预期结果**:
- 提交后自动创建 2 个步骤实例（提交步骤已完成 + 战略发展部负责人步骤待审批）
- 第二步通过后自动创建第 3 个步骤实例（分管校领导待审批）
- 分管校领导通过后，workflow 状态变为 `APPROVED`
- Plan 状态同步更新

#### 场景 1.2: 分管校领导驳回
1. 使用 `zlb_admin` 登录提交 Plan 下发
2. 使用 `zlb_final1` 或 `zlb_final2` 通过第二步审批
3. 使用 `admin` 登录
4. 在"我的待办"中找到该审批任务并点击"驳回"
5. 验证第二步恢复为 `PENDING`

**预期结果**:
- 分管校领导驳回后，workflow 状态仍为 `IN_REVIEW`
- 战略发展部负责人步骤恢复为 `PENDING` 状态
- `zlb_final1` / `zlb_final2` 可以重新审批

---

## 流程 2: PLAN_DISPATCH_FUNCDEPT - Plan下发审批（职能部门）

### 流程说明
职能部门发起的 Plan 下发审批流程，需要经过职能部门审批人审批和分管校领导审批。

### 审批步骤
1. **提交步骤** (SUBMIT) - 提交人自动完成
2. **职能部门审批人审批** (APPROVAL) - 职能部门审批角色审批
3. **分管校领导审批** (APPROVAL) - 分管校领导角色审批

### 测试账号（以教务处为例）

| 角色 | 用户名 | 姓名 | 组织 | 说明 |
|------|--------|------|------|------|
| 发起人 | `jiaowu_report` | 教务处填报员 | 教务处 | 提交 Plan 下发申请 |
| 第二步审批人 | `jiaowu_audit1` | 教务处审批人 | 教务处 | 职能部门审批角色 |
| 第三步审批人 | `jiaowu_leader` | 教务处领导 | 教务处 | 分管校领导审批 seat，按发起部门 org 解析 |

### 其他职能部门账号

**党委办公室**: `dangban_report`, `dangban_audit1`, `dangban_audit2`
**教务处**: `jiaowu_report`, `jiaowu_audit1`, `jiaowu_leader`
**科技处**: `keji_report`, `keji_audit1`, `keji_leader`
**财务部**: `caiwu_report`, `caiwu_audit1`, `caiwu_leader`
**后勤资产处**: `houqin_report`, `houqin_audit1`, `houqin_leader`

说明: 当前 clean seed 中，职能部门的 `_leader` / `_audit2` 已分配 `ROLE_VICE_PRESIDENT`，用于第三步“分管校领导审批”节点；第二步仍使用 `_audit1`。
后端当前按 workflow 的 `requester_org_id` 解析这个节点，所以职能部门流程第 3 步不是统一 `admin`，而是发起部门自己的 `_leader` / `_audit2`。

### 测试场景

#### 场景 2.1: 正常审批通过（两级审批）
1. 使用 `jiaowu_report` 登录
2. 创建 Plan 并提交下发申请
3. 系统自动完成提交步骤，创建部门审批步骤
4. 使用 `jiaowu_audit1` 登录
5. 在"我的待办"中找到该审批任务，点击"通过"
6. 系统动态创建分管校领导审批步骤
7. 使用 `jiaowu_leader` 登录
8. 在"我的待办"中找到该审批任务，点击"通过"
9. 验证 Plan 状态变为"已下发"

**预期结果**:
- 提交后创建 2 个步骤实例（提交已完成 + 部门审批待审批）
- 部门审批通过后，动态创建第 3 个步骤实例（分管校领导待审批）
- 分管校领导通过后，workflow 状态变为 `APPROVED`

#### 场景 2.2: 部门负责人驳回
1. 使用 `dangban_report` 登录提交 Plan 下发
2. 使用 `dangban_audit1` 登录
3. 在"我的待办"中点击"驳回"
4. 验证 workflow 状态变为 `REJECTED`

**预期结果**:
- 部门审批驳回后，workflow 状态变为 `REJECTED`
- 提交步骤恢复为 `PENDING` 状态

#### 场景 2.3: 分管校领导驳回
1. 使用 `caiwu_report` 登录提交 Plan 下发
2. 使用 `caiwu_audit1` 登录通过部门审批
3. 使用 `caiwu_leader` 登录
4. 在"我的待办"中点击"驳回"
5. 验证部门审批步骤恢复为 `PENDING`

**预期结果**:
- 分管校领导驳回后，workflow 状态仍为 `IN_REVIEW`
- 部门审批步骤恢复为 `PENDING` 状态
- `caiwu_audit1` 可以重新审批

---

## 流程 3: PLAN_APPROVAL_FUNCDEPT - Plan审批流程（职能部门）

### 流程说明
职能部门的 Plan 审批流程，需要经过职能部门审批人审批、分管校领导审批和战略发展部终审。

### 审批步骤
1. **提交步骤** (SUBMIT) - 提交人自动完成
2. **职能部门审批人审批** (APPROVAL) - 职能部门审批角色审批
3. **分管校领导审批** (APPROVAL) - 分管校领导角色审批
4. **战略发展部终审人审批** (APPROVAL) - 战略发展部终审角色审批

### 测试账号（以教务处为例）

| 角色 | 用户名 | 姓名 | 组织 | 说明 |
|------|--------|------|------|------|
| 发起人 | `jiaowu_report` | 教务处填报员 | 教务处 | 提交 Plan 审批申请 |
| 第二步审批人 | `jiaowu_audit1` | 教务处审批人 | 教务处 | 职能部门审批角色 |
| 第三步审批人 | `jiaowu_leader` | 教务处领导 | 教务处 | 分管校领导审批 seat，按发起部门 org 解析 |
| 第四步审批人 | `zlb_final1` | 战略终审1 | 战略发展部 | 战略发展部终审角色 |

### 测试场景

#### 场景 3.1: 正常审批通过（三级审批）
1. 使用 `keji_report` 登录创建并提交 Plan
2. 系统自动完成提交步骤，创建第二步审批步骤
3. 使用 `keji_audit1` 登录，在"我的待办"中通过第二步审批
4. 系统动态创建分管校领导审批步骤
5. 使用 `keji_leader` 登录，在"我的待办"中通过第三步审批
6. 系统动态创建战略终审步骤
7. 使用 `zlb_final1` 登录，在"我的待办"中通过终审
8. 验证 Plan 状态变为"已审批"

**预期结果**:
- 提交后创建 2 个步骤实例（提交已完成 + 第二步审批待审批）
- 第二步审批通过后，动态创建第 3 个步骤实例（分管校领导待审批）
- 分管校领导通过后，动态创建第 4 个步骤实例（战略终审待审批）
- 战略终审通过后，workflow 状态变为 `APPROVED`

#### 场景 3.2: 第二步审批驳回
1. 使用 `xuegong_report` 登录提交 Plan
2. 使用 `xuegong_audit1` 登录驳回第二步审批
3. 验证 workflow 状态变为 `REJECTED`

**预期结果**:
- 第二步审批驳回后，workflow 状态变为 `REJECTED`
- 提交步骤恢复为 `PENDING` 状态

#### 场景 3.3: 分管校领导驳回
1. 使用 `houqin_report` 登录提交 Plan
2. 使用 `houqin_audit1` 登录通过第二步审批
3. 使用 `houqin_leader` 登录驳回第三步审批
4. 验证第二步恢复为 `PENDING`

**预期结果**:
- 分管校领导驳回后，workflow 状态仍为 `IN_REVIEW`
- 第二步审批步骤恢复为 `PENDING` 状态
- `houqin_audit1` 可以重新审批

#### 场景 3.4: 战略终审驳回
1. 使用 `jiaowu_report` 登录提交 Plan
2. 使用 `jiaowu_audit1` 登录通过第二步审批
3. 使用 `jiaowu_leader` 登录通过第三步审批
4. 使用 `zlb_final2` 登录驳回战略终审
5. 验证第三步审批步骤恢复为 `PENDING`

**预期结果**:
- 战略终审驳回后，workflow 状态仍为 `IN_REVIEW`
- 分管校领导审批步骤恢复为 `PENDING` 状态
- `jiaowu_leader` 可以重新审批

---

## 流程 4: PLAN_APPROVAL_COLLEGE - Plan审批流程（二级学院）

### 流程说明
二级学院的 Plan 审批流程，需要经过学院审批人审批、学院院长审批和职能部门终审。

### 审批步骤
1. **提交步骤** (SUBMIT) - 提交人自动完成
2. **二级学院审批人审批** (APPROVAL) - 二级学院审批角色审批
3. **学院院长审批人审批** (APPROVAL) - 学院院长角色审批
4. **职能部门终审人审批** (APPROVAL) - 对应上级职能部门审批角色审批

### 测试账号（以计算机学院为例）

| 角色 | 用户名 | 姓名 | 组织 | 说明 |
|------|--------|------|------|------|
| 发起人 | `jisuanji_report` | 计算机学院填报员 | 计算机学院 | 提交 Plan 审批申请 |
| 第二步审批人 | `jisuanji_audit1` | 计算机学院审批人 | 计算机学院 | 二级学院审批角色 |
| 第三步审批人 | `jisuanji_leader` | 计算机学院院长 | 计算机学院 | 学院院长角色 |
| 第四步审批人 | `jiaowu_audit1` | 教务处审批人 | 教务处 | 当前默认上级职能部门终审账号 |

### 其他学院账号

**马克思主义学院**: `makesi_report`, `makesi_audit1`, `makesi_audit2`
**工学院**: `gongxue_report`, `gongxue_audit1`, `gongxue_leader`
**商学院**: `shangxue_report`, `shangxue_audit1`, `shangxue_leader`
**文理学院**: `wenli_report`, `wenli_audit1`, `wenli_leader`
**艺术与科技学院**: `yishukeji_report`, `yishukeji_audit1`, `yishukeji_leader`

说明: 当前 clean seed 的学院流程最后一步不是战略发展部终审，而是对应上级职能部门的终审；默认测试可使用教务处 `jiaowu_audit1`。

### 测试场景

#### 场景 4.1: 正常审批通过（三级审批）
1. 使用 `jisuanji_report` 登录创建并提交 Plan
2. 系统自动完成提交步骤，创建第二步审批步骤
3. 使用 `jisuanji_audit1` 登录，在"我的待办"中通过第二步审批
4. 系统动态创建学院院长审批步骤
5. 使用 `jisuanji_leader` 登录，在"我的待办"中通过第三步审批
6. 系统动态创建职能部门终审步骤
7. 使用 `jiaowu_audit1` 登录，在"我的待办"中通过终审
8. 验证 Plan 状态变为"已审批"

**预期结果**:
- 提交后创建 2 个步骤实例（提交已完成 + 第二步审批待审批）
- 第二步审批通过后，动态创建第 3 个步骤实例（学院院长审批待审批）
- 第三步审批通过后，动态创建第 4 个步骤实例（职能部门终审待审批）
- 职能部门终审通过后，workflow 状态变为 `APPROVED`

#### 场景 4.2: 第二步审批驳回
1. 使用 `shangxue_report` 登录提交 Plan
2. 使用 `shangxue_audit1` 登录驳回第二步审批
3. 验证 workflow 状态变为 `REJECTED`

**预期结果**:
- 第二步审批驳回后，workflow 状态变为 `REJECTED`
- 提交步骤恢复为 `PENDING` 状态

#### 场景 4.3: 学院院长驳回
1. 使用 `wenli_report` 登录提交 Plan
2. 使用 `wenli_audit1` 登录通过第二步审批
3. 使用 `wenli_leader` 登录驳回第三步审批
4. 验证第二步审批步骤恢复为 `PENDING`

**预期结果**:
- 学院院长驳回后，workflow 状态仍为 `IN_REVIEW`
- 第二步审批步骤恢复为 `PENDING` 状态
- `wenli_audit1` 可以重新审批

#### 场景 4.4: 职能部门终审驳回
1. 使用 `yishukeji_report` 登录提交 Plan
2. 使用 `yishukeji_audit1` 登录通过第二步审批
3. 使用 `yishukeji_leader` 登录通过第三步审批
4. 使用 `jiaowu_audit1` 登录驳回第四步终审
5. 验证第三步审批步骤恢复为 `PENDING`

**预期结果**:
- 职能部门终审驳回后，workflow 状态仍为 `IN_REVIEW`
- 学院院长审批步骤恢复为 `PENDING` 状态
- `yishukeji_leader` 可以重新审批

---

## 通用测试要点

### 1. 动态步骤生成验证
- 提交后只创建第一个审批步骤（提交步骤自动完成）
- 每次审批通过后，动态创建下一个步骤
- 数据库 `audit_step_instance` 表中步骤数量逐步增加

### 2. 驳回回退验证
- 驳回后，上一个已完成步骤恢复为 `PENDING` 状态
- 驳回后，当前步骤状态变为 `REJECTED`
- 如果驳回第一个审批步骤，workflow 状态变为 `REJECTED`

### 3. 字段完整性验证
检查 `audit_step_instance` 表中每个步骤实例的字段：
- `step_no`: 步骤序号（1, 2, 3...）
- `approver_id`: 审批人 ID（不为空）
- `approver_org_id`: 审批人组织 ID（不为空）
- `step_def_id`: 步骤定义 ID（不为空）
- `status`: 步骤状态（PENDING/APPROVED/REJECTED）

### 4. API 接口测试

#### 获取我的待办任务
```bash
curl -X GET "http://localhost:8080/api/v1/workflows/my-tasks" \
  -H "Authorization: Bearer <token>"
```

#### 审批通过
```bash
curl -X POST "http://localhost:8080/api/v1/workflows/{instanceId}/approve" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"comment": "同意"}'
```

#### 审批驳回
```bash
curl -X POST "http://localhost:8080/api/v1/workflows/{instanceId}/reject" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"comment": "不同意，需要修改"}'
```

### 5. 日志检查
查看后端日志确认步骤创建：
```bash
tail -f /tmp/sism-backend.log | grep "StepInstanceFactory\|ApproveWorkflowUseCase"
```

---

## 测试数据准备

### 登录获取 Token
```bash
curl -X POST "http://localhost:8080/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "zlb_admin",
    "password": "admin123"
  }'
```

### 创建测试 Plan
```bash
curl -X POST "http://localhost:8080/api/v1/plans" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "planName": "测试Plan-战略发展部",
    "cycleId": 1,
    "description": "测试审批流程"
  }'
```

---

## 常见问题

### Q1: 提交后没有创建审批步骤？
**检查**:
- 确认 `audit_flow_def` 表中流程定义存在
- 确认 `audit_step_def` 表中步骤定义存在
- 查看后端日志是否有异常

### Q2: 审批通过后没有创建下一个步骤？
**检查**:
- 确认当前步骤状态已变为 `APPROVED`
- 确认流程定义中还有下一个步骤
- 查看 `ApproveWorkflowUseCase.createNextStep()` 日志

### Q3: approver_org_id 为空？
**检查**:
- 确认 `ApproverResolver.resolveApproverOrgId()` 方法正常工作
- 确认审批人账号的 `org_id` 字段不为空

### Q4: 驳回后上一步骤没有恢复？
**检查**:
- 确认 `AuditInstance.reject()` 方法正确执行
- 确认上一个步骤状态从 `APPROVED` 变为 `PENDING`
- 查看数据库 `audit_step_instance` 表中步骤状态

---

## 测试报告模板

### 测试执行记录

| 流程 | 场景 | 测试人 | 测试时间 | 结果 | 备注 |
|------|------|--------|----------|------|------|
| PLAN_DISPATCH_STRATEGY | 场景 1.1 | | | ✓/✗ | |
| PLAN_DISPATCH_FUNCDEPT | 场景 2.1 | | | ✓/✗ | |
| PLAN_APPROVAL_FUNCDEPT | 场景 3.1 | | | ✓/✗ | |
| PLAN_APPROVAL_COLLEGE | 场景 4.1 | | | ✓/✗ | |

### 缺陷记录

| 缺陷ID | 流程 | 场景 | 描述 | 严重程度 | 状态 |
|--------|------|------|------|----------|------|
| | | | | | |

---

**文档版本**: 1.0
**最后更新**: 2026-03-22
**维护人**: SISM 开发团队
