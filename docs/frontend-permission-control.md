> **[时效标注 2026-10-26]** 本文描述的三层权限体系（路由守卫/权限码/组织角色）仍有效；
> 但 2026-10-26 起部分行为有变化：① 填报/编辑按钮仅 `ROLE_REPORTER` 可见；
> ② 二级学院只设「填报人 + 审核人」两级席位，不设「分管校领导」；
> ③ 详情看板不做权限限制。账号角色分布见 `docs/用户账号密码文档.md`。

# SISM 前端权限控制体系文档

> **更新时间**: 2026-05-10
> **适用范围**: strategic-task-management（Vue 3 + TypeScript + Pinia）

---

## 1. 权限体系总览

前端当前正式采用 **三层角色化控制** 体系：

```
┌─────────────────────────────────────────────────┐
│  第 1 层：路由守卫（Route Guard）                  │
│  控制页面级访问，未授权用户无法进入受保护路由          │
├─────────────────────────────────────────────────┤
│  第 2 层：导航控制（Navigation）                   │
│  不同角色看到不同的侧边栏/标签页                      │
├─────────────────────────────────────────────────┤
│  第 3 层：组件级权限（Component Permission）        │
│  按钮显隐、数据过滤、操作权限控制                     │
└─────────────────────────────────────────────────┘
```

**当前正式口径**：

- 核心业务以 `部门/组织身份 + 真实角色(role_id / role_code) + 业务规则` 为准
- 前端继续把后端组织类型映射为 3 个 UI 角色，用于路由、导航和展示层显隐
- `PermissionCode`、`/auth/permissions`、`sys_permission`、`sys_role_permission` 仍保留，但当前属于 **legacy 预留扩展能力**，不参与审批、提交、退回、数据归属等核心动作最终裁决

**角色体系**：后端真实角色 + 组织范围 → 前端映射为 3 个 UI 角色

| 后端 orgType | 前端 UserRole | 说明 |
|-------------|---------------|------|
| `admin` / `ADMIN` / `STRATEGY_DEPT` | `strategic_dept` | 战略发展部（最高权限） |
| `FUNCTIONAL_DEPT` / `functional` 等 | `functional_dept` | 职能部门 |
| `COLLEGE` / `SECONDARY_COLLEGE` 等 | `secondary_college` | 二级学院 |

---

## 2. 权限控制文件清单

### 2.1 核心角色与 legacy 权限定义

| 文件路径 | 职责 | 关键导出 |
|---------|------|---------|
| `src/5-shared/types/backend-aligned.ts` | 定义 `UserRole` 联合类型 | `type UserRole = 'strategic_dept' \| 'functional_dept' \| 'secondary_college'` |
| `src/5-shared/types/index.ts` | 保留 legacy `PermissionCode` 枚举 | 仅兼容旧接口与未来扩展 |
| `src/3-features/auth/lib/permissions.ts` | 保留 legacy 角色-权限映射表 | 非核心流程不再依赖 |
| `src/5-shared/lib/permissions/usePermission.ts` | 角色化业务助手（主要入口） | `canAccessOrg()`, `canEditPlan()`, `canSubmitPlan()` 等 |
| `src/5-shared/lib/permissions/adminConsoleAccess.ts` | 管理控制台访问控制 | `hasAdminConsoleAccess()` |

### 2.2 认证状态管理

| 文件路径 | 职责 |
|---------|------|
| `src/3-features/auth/model/store.ts` | Pinia auth store，管理 token/user/role；`permissions` 仅作兼容字段保留 |
| `src/5-shared/lib/utils/authHelpers/responseParser.ts` | 登录响应解析，后端 orgType → 前端 UserRole 映射 |
| `src/5-shared/lib/utils/tokenManager.ts` | JWT token 存储、刷新、过期检查 |

### 2.3 路由守卫

| 文件路径 | 职责 |
|---------|------|
| `src/1-app/providers/router.ts` | 路由定义 + `beforeEach` 全局守卫 |
| `src/1-app/providers/lib/routeAccess.ts` | 路由访问判定逻辑 `resolveProtectedRouteRedirect()` |

### 2.4 导航控制

| 文件路径 | 职责 |
|---------|------|
| `src/5-shared/lib/layout/useNavigation.ts` | 按角色返回不同的标签页配置 |
| `src/1-app/layouts/AppLayout.vue` | 主布局，控制菜单/标签页渲染 |
| `src/1-app/layouts/lib/useAppLayout.ts` | 布局级权限 composable |

### 2.5 组件级权限

| 文件路径 | 使用的权限机制 |
|---------|--------------|
| `src/3-features/dashboard/ui/DashboardView.vue` | `v-if="currentRole === 'strategic_dept'"` 等 |
| `src/3-features/dashboard/ui/DashboardFilters.vue` | `v-if="authStore.user?.role === 'strategic_dept'"` |
| `src/3-features/indicator/ui/IndicatorListView.vue` | `isStrategicDept` 控制按钮显隐 |
| `src/3-features/indicator/ui/IndicatorDistributeView.vue` | `isStrategicDept` / `isFunctionalDept` 控制操作 |
| `src/3-features/plan/ui/PlanListView.vue` | `authStore.user?.role === 'strategic_dept'` |
| `src/3-features/approval/ui/ApprovalProgressDrawer.vue` | 按角色/组织/当前处理人控制审批按钮 |
| `src/3-features/approval/ui/DistributionApprovalProgressDrawer.vue` | 按角色/组织/当前处理人控制审批按钮 |
| `src/3-features/task/ui/TaskApprovalDrawer.vue` | 按当前审批人控制是否可处理待办 |

### 2.6 错误页面

| 文件路径 | 职责 |
|---------|------|
| `src/5-shared/ui/error/ForbiddenView.vue` | 403 页面，权限不足时展示 |
| `src/5-shared/ui/error/NotFoundView.vue` | 404 页面 |

---

## 3. 权限机制详解

### 3.1 路由守卫

**文件**: `src/1-app/providers/router.ts` (L213-277)

路由通过 `meta.roles` 声明哪些角色可以访问：

```typescript
// 路由定义中的角色限制
{
  path: 'strategic-tasks',
  meta: { roles: ['strategic_dept'] }   // 仅战略发展部
},
{
  path: 'distribution',
  meta: { roles: ['functional_dept'] }  // 仅职能部门
}
```

`beforeEach` 守卫流程：

```
用户访问路由
  → ensureAuthRestored() 恢复认证状态
  → resolveProtectedRouteRedirect() 判断是否允许访问
    → requiresAuth 且未登录 → 重定向 /login
    → allowedRoles 且角色不匹配 → 重定向 /403 或 /dashboard
    → /admin/* 路由 → 检查 hasAdminConsoleAccess
    → strategic_dept 角色绕过所有角色检查
  → 放行
```

**特殊规则**: `strategic_dept` 在 `routeAccess.ts` L42-43 中直接返回 `null`（放行），即战略发展部可以访问所有路由。

### 3.2 导航控制

**文件**: `src/5-shared/lib/layout/useNavigation.ts` (L27-41)

三个角色看到不同的标签页：

| 角色 | 可见标签页 |
|------|-----------|
| `strategic_dept` | 数据看板、战略任务管理 |
| `functional_dept` | 数据看板、指标填报、指标下发与审批 |
| `secondary_college` | 数据看板、指标填报 |

### 3.3 orgType → UserRole 映射

**文件**: `src/5-shared/lib/utils/authHelpers/responseParser.ts` (L96-128)

登录时将后端的 `orgType` 映射为前端角色：

```typescript
export function mapOrgTypeToRole(orgType: string): UserRole | null {
  const mapping: Record<string, UserRole> = {
    STRATEGY_DEPT: 'strategic_dept',
    FUNCTIONAL_DEPT: 'functional_dept',
    FUNCTION_DEPT: 'functional_dept',
    COLLEGE: 'secondary_college',
    SECONDARY_COLLEGE: 'secondary_college',
    DIVISION: 'secondary_college',
    // ...
  }
  return mapping[normalizedType] || null
}
```

### 3.4 usePermission composable

**文件**: `src/5-shared/lib/permissions/usePermission.ts`

这是组件中最常用的角色化业务判断入口，提供：

| 方法 | 说明 |
|------|------|
| `hasPermission(code)` | legacy 兼容壳，不作为核心业务最终判断依据 |
| `canAccessOrg(orgId)` | 检查用户能否访问指定组织数据 |
| `canOperate(orgId, permission)` | 组织范围判断；`permission` 参数仅兼容旧签名 |
| `canViewPlan(plan)` | 能否查看计划 |
| `canEditPlan(plan)` | 能否编辑计划（仅 draft 状态，strategic/本 org functional） |
| `canDeletePlan(plan)` | 能否删除计划（仅 strategic_dept + draft） |
| `canSubmitPlan(plan)` | 能否提交计划 |
| `canFillIndicator(indicator)` | 能否填报指标 |
| `canViewIndicator(indicator)` | 能否查看指标 |

**关键规则**：

- 战略发展部可跨组织查看与操作
- 职能部门、二级学院默认仅可操作本组织数据
- 审批、提交、退回这类核心动作不能仅看 `hasPermission()`，必须结合当前流程节点、当前处理人、业务状态一起判断

### 3.5 Auth Store 内置 legacy 权限接口

**文件**: `src/3-features/auth/model/store.ts` (L396-432)

store 仍保留 `hasPermission(resource, action)` 兼容接口，但运行时不再主动拉取 `/auth/permissions`，也不再把后端权限码作为核心业务判定来源。

### 3.6 管理控制台访问

**文件**: `src/5-shared/lib/permissions/adminConsoleAccess.ts`

```typescript
export function hasAdminConsoleAccess(user): boolean {
  return user?.orgType === 'admin'
}
```

仅 `orgType === 'admin'` 的用户可访问 `/admin/console`。

---

## 4. Legacy 权限码定义

**文件**: `src/5-shared/types/index.ts`

```typescript
export const PermissionCode = {
  PLAN_CREATE: 'plan:create',
  PLAN_VIEW: 'plan:view',
  PLAN_EDIT: 'plan:edit',
  PLAN_DELETE: 'plan:delete',
  PLAN_SUBMIT: 'plan:submit',
  INDICATOR_VIEW: 'indicator:view',
  INDICATOR_FILL: 'indicator:fill',
  INDICATOR_EDIT: 'indicator:edit',
  TASK_CREATE: 'task:create',
  TASK_EDIT: 'task:edit',
  TASK_DELETE: 'task:delete'
}
```

当前这些权限码仅作为历史兼容与未来扩展保留，不再驱动审批、提交、退回、数据归属等核心业务动作。

**legacy 权限码 → 角色映射** (`usePermission.ts` L52-64):

| 权限码 | 允许的角色 |
|--------|-----------|
| `plan:create` | strategic_dept, functional_dept |
| `plan:view` | 全部 |
| `plan:edit` | strategic_dept, functional_dept |
| `plan:delete` | strategic_dept |
| `plan:submit` | 全部 |
| `indicator:view` | 全部 |
| `indicator:fill` | 全部 |
| `indicator:edit` | strategic_dept, functional_dept |
| `task:create` | strategic_dept, functional_dept |
| `task:edit` | strategic_dept, functional_dept |
| `task:delete` | strategic_dept |

---

## 5. Demo 账号与权限的关系

Demo 账号在后端拥有所有 4 个角色（ROLE_REPORTER, ROLE_APPROVER, ROLE_STRATEGY_DEPT_HEAD, ROLE_VICE_PRESIDENT），但前端的角色映射取决于 `orgType`：

| Demo 账号 | orgId | orgType | 前端映射角色 |
|-----------|-------|---------|-------------|
| `zlb_demo` | 35 | `admin` | `strategic_dept` |
| `jiaowu_demo` | 44 | `functional` | `functional_dept` |
| `jisuanji_demo` | 57 | `college` | `secondary_college` |

**说明**：前端只识别 3 种 UI 角色；核心审批动作是否可执行，最终由真实角色、组织范围、当前流程节点和当前处理人共同决定，不再以权限码是否存在作为准入条件。

---

## 6. 文件索引（按路径排序）

```
src/
├── 1-app/
│   ├── layouts/
│   │   ├── AppLayout.vue                          # 主布局，菜单/标签页权限控制
│   │   └── lib/useAppLayout.ts                    # 布局权限 composable
│   └── providers/
│       ├── router.ts                              # 路由定义 + beforeEach 守卫
│       └── lib/routeAccess.ts                     # 路由访问判定逻辑
│
├── 3-features/
│   ├── auth/
│   │   ├── lib/permissions.ts                     # 角色-权限映射表
│   │   ├── model/store.ts                         # Auth Store（token/user/role/permissions）
│   │   └── api/query.ts                          # 获取用户权限 API
│   ├── admin/
│   │   └── ui/AdminConsoleView.vue               # 管理控制台（需 orgType=admin）
│   ├── approval/
│   │   ├── ui/ApprovalProgressDrawer.vue          # 审批进度抽屉（hasPlanApprovalPermission）
│   │   ├── ui/DistributionApprovalProgressDrawer.vue  # 下发审批进度（hasPlanApprovalPermission）
│   │   └── model/useApprovalProgress*.ts          # 审批权限相关 model
│   ├── dashboard/
│   │   ├── ui/DashboardView.vue                   # 仪表盘（大量 v-if role 判断）
│   │   └── ui/DashboardFilters.vue                # 筛选器（角色控制部门选择器显隐）
│   ├── indicator/
│   │   ├── ui/IndicatorListView.vue               # 指标列表（isStrategicDept 控制操作）
│   │   ├── ui/IndicatorDistributeView.vue         # 指标下发（isStrategicDept/isFunctionalDept）
│   │   └── model/useIndicatorListView.ts          # 指标权限 model
│   ├── plan/
│   │   └── ui/PlanListView.vue                    # 计划列表（strategic_dept 编辑权限）
│   └── task/
│       ├── ui/TaskApprovalDrawer.vue              # 任务审批（missingPermissionCode）
│       └── ui/StrategicTaskView.vue               # 战略任务（角色控制 UI 选项）
│
├── 5-shared/
│   ├── types/
│   │   ├── index.ts                               # PermissionCode 枚举定义
│   │   ├── backend-aligned.ts                     # UserRole 联合类型
│   │   ├── entities.ts                            # UserRole 常量对象
│   │   └── schemas.ts                             # Zod schema 验证
│   ├── lib/
│   │   ├── permissions/
│   │   │   ├── usePermission.ts                   # ★ 主要权限 composable
│   │   │   ├── adminConsoleAccess.ts              # 管理控制台访问控制
│   │   │   └── check-permission.ts                # 通用权限检查函数
│   │   ├── layout/
│   │   │   └── useNavigation.ts                   # 角色标签页配置
│   │   └── utils/
│   │       ├── authHelpers/responseParser.ts      # 登录响应解析 + orgType→role 映射
│   │       ├── tokenManager.ts                    # JWT 管理
│   │       └── authSession.ts                     # 会话清理
│   └── ui/
│       ├── error/ForbiddenView.vue                # 403 页面
│       └── form/DashboardFilters.vue              # 共享筛选器（角色控制）
```
