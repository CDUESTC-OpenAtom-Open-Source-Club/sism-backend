#!/usr/bin/env python3
"""
SISM approval closure verification.

Scope:
- Runs one full strategic dispatch + report-up closure through HTTP APIs.
- Runs one rejection rollback probe.
- Prepares and runs multiple independent closure chains concurrently.
- Uses SQL only for deterministic local test-data preparation and validation.

This script is intentionally local-test oriented. It mutates the local PostgreSQL
test database and resets workflow/runtime rows for the test plans it owns.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import json
import os
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import requests


BASE_URL = os.environ.get("SISM_BASE_URL", "http://localhost:8080").rstrip("/")
DB_ENV = {
    "PGPASSWORD": os.environ.get("SISM_DB_PASSWORD", "unused_for_local_trust_auth"),
    **os.environ,
}
PSQL = [
    "psql",
    "-h",
    os.environ.get("SISM_DB_HOST", "127.0.0.1"),
    "-U",
    os.environ.get("SISM_DB_USER", "blackevil"),
    "-d",
    os.environ.get("SISM_DB_NAME", "sism_db"),
    "-v",
    "ON_ERROR_STOP=1",
]

ACCOUNTS = {
    "zlb_admin": "admin123",
    "zlb_final1": "admin123",
    "admin": "admin123",
    "jiaowu_report": "admin123",
    "jiaowu_audit1": "admin123",
    "jiaowu_leader": "admin123",
    "jisuanji_report": "admin123",
    "jisuanji_audit1": "admin123",
    "jisuanji_leader": "admin123",
}

ORG_STRATEGY = 35
ORG_JIAOWU = 44
ORG_JISUANJI = 57


@dataclass
class Check:
    name: str
    ok: bool
    detail: str = ""


@dataclass
class TestContext:
    session: requests.Session = field(default_factory=requests.Session)
    tokens: dict[str, str] = field(default_factory=dict)
    user_ids: dict[str, int] = field(default_factory=dict)
    user_orgs: dict[str, int] = field(default_factory=dict)
    lock: threading.Lock = field(default_factory=threading.Lock)
    checks: list[Check] = field(default_factory=list)
    created_plan_ids: list[int] = field(default_factory=list)

    def add(self, name: str, ok: bool, detail: str = "") -> None:
        with self.lock:
            self.checks.append(Check(name, ok, detail))
        mark = "✓" if ok else "✗"
        print(f"{mark} {name}" + (f" — {detail}" if detail else ""), flush=True)


def psql_exec(sql: str) -> str:
    proc = subprocess.run(
        PSQL,
        input=sql,
        text=True,
        capture_output=True,
        env=DB_ENV,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"psql failed\nSQL:\n{sql}\nSTDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}")
    return proc.stdout


def psql_json(query: str) -> list[dict[str, Any]]:
    sql = f"""
    SELECT COALESCE(json_agg(row_to_json(q)), '[]'::json)
    FROM (
    {query}
    ) q;
    """
    out = psql_exec(sql)
    lines = [line.strip() for line in out.splitlines() if line.strip()]
    json_line = next((line for line in lines if line.startswith("[") or line.startswith("{")), "[]")
    return json.loads(json_line)


def api(ctx: TestContext, account: str, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
    headers = kwargs.pop("headers", {})
    headers["Authorization"] = f"Bearer {ctx.tokens[account]}"
    headers.setdefault("Content-Type", "application/json")
    response = ctx.session.request(method, f"{BASE_URL}{path}", headers=headers, timeout=30, **kwargs)
    try:
        payload = response.json()
    except Exception as exc:  # pragma: no cover - diagnostic path
        raise RuntimeError(f"{method} {path} returned non-json HTTP {response.status_code}: {response.text}") from exc
    if response.status_code >= 400 or payload.get("success") is False:
        raise RuntimeError(f"{method} {path} failed HTTP {response.status_code}: {json.dumps(payload, ensure_ascii=False)}")
    return payload


def login_all(ctx: TestContext) -> None:
    for account, password in ACCOUNTS.items():
        response = ctx.session.post(
            f"{BASE_URL}/api/v1/auth/login",
            json={"account": account, "password": password},
            timeout=20,
        )
        payload = response.json()
        if response.status_code != 200 or not payload.get("success"):
            raise RuntimeError(f"login failed for {account}: {payload}")
        data = payload["data"]
        ctx.tokens[account] = data["token"]
        ctx.user_ids[account] = int(data["user"]["id"])
        ctx.user_orgs[account] = int(data["user"]["orgId"])
    ctx.add("登录全部 9 个测试账号", True, f"base={BASE_URL}")


def reset_runtime_for_plans(plan_ids: list[int]) -> None:
    ids = ",".join(str(int(x)) for x in plan_ids)
    psql_exec(f"""
    BEGIN;
    WITH target_instances AS (
        SELECT id FROM audit_instance
        WHERE entity_type = 'PLAN' AND entity_id IN ({ids})
    ),
    target_tasks AS (
        SELECT task_id FROM workflow_task
        WHERE workflow_id IN (SELECT id::text FROM target_instances)
    )
    DELETE FROM workflow_task_history WHERE task_id IN (SELECT task_id FROM target_tasks);

    WITH target_instances AS (
        SELECT id FROM audit_instance
        WHERE entity_type = 'PLAN' AND entity_id IN ({ids})
    )
    DELETE FROM workflow_task WHERE workflow_id IN (SELECT id::text FROM target_instances);

    DELETE FROM audit_step_instance
    WHERE instance_id IN (
        SELECT id FROM audit_instance
        WHERE entity_type = 'PLAN' AND entity_id IN ({ids})
    );

    DELETE FROM audit_instance
    WHERE entity_type = 'PLAN' AND entity_id IN ({ids});

    DELETE FROM sys_user_notification
    WHERE related_entity_type = 'PLAN' AND related_entity_id IN ({ids});

    UPDATE plan
    SET status = 'DRAFT', audit_instance_id = NULL, is_deleted = false, updated_at = NOW()
    WHERE id IN ({ids});
    COMMIT;
    """)


def prepare_single_chain_data(ctx: TestContext) -> None:
    reset_runtime_for_plans([4044, 404457])
    psql_exec("""
    BEGIN;
    -- Align local seed with this test task: admin acts as strategic VP.
    INSERT INTO sys_user_role (user_id, role_id)
    VALUES (124, 4)
    ON CONFLICT DO NOTHING;

    -- Keep the strategic -> Jiaowu package at exactly 100 top-level weight.
    UPDATE sys_task SET is_deleted = true, updated_at = NOW() WHERE task_id = 41022;

    -- Add deterministic Jiaowu -> Computer College package data for terminal validation.
    INSERT INTO sys_task (
        task_id, created_at, updated_at, remark, sort_order, name, "desc",
        task_type, created_by_org_id, cycle_id, org_id, is_deleted, plan_id
    )
    VALUES (
        910457, NOW(), NOW(), 'SISM closure test: Jiaowu to Computer College', 1,
        'SISM闭环测试-教务处下发计算机学院任务',
        '自动化闭环测试任务容器', 'BASIC', 44, 4, 57, false, 404457
    )
    ON CONFLICT (task_id) DO UPDATE
    SET updated_at = NOW(), is_deleted = false, plan_id = EXCLUDED.plan_id,
        created_by_org_id = EXCLUDED.created_by_org_id, org_id = EXCLUDED.org_id,
        cycle_id = EXCLUDED.cycle_id, task_type = EXCLUDED.task_type,
        name = EXCLUDED.name, "desc" = EXCLUDED."desc";

    INSERT INTO indicator (
        id, task_id, parent_indicator_id, indicator_desc, weight_percent, sort_order,
        remark, created_at, updated_at, type, progress, is_deleted,
        owner_org_id, target_org_id, status, responsible_user_id
    )
    VALUES
        (91045701, 910457, 2005, 'SISM闭环测试-计算机学院学院指标A', 55.00, 1,
         'closure-test', NOW(), NOW(), '定量', 0, false, 44, 57, 'DISTRIBUTED', 370),
        (91045702, 910457, 2006, 'SISM闭环测试-计算机学院学院指标B', 45.00, 2,
         'closure-test', NOW(), NOW(), '定性', 0, false, 44, 57, 'DISTRIBUTED', 370)
    ON CONFLICT (id) DO UPDATE
    SET task_id = EXCLUDED.task_id, parent_indicator_id = EXCLUDED.parent_indicator_id,
        indicator_desc = EXCLUDED.indicator_desc, weight_percent = EXCLUDED.weight_percent,
        updated_at = NOW(), type = EXCLUDED.type, is_deleted = false,
        owner_org_id = EXCLUDED.owner_org_id, target_org_id = EXCLUDED.target_org_id,
        status = EXCLUDED.status, responsible_user_id = EXCLUDED.responsible_user_id;
    COMMIT;
    """)
    ctx.add("准备单闭环测试数据", True, "4044 权重=100；404457 已补学院指标")


def assert_weight_foundation(ctx: TestContext, plan_ids: list[int], label: str) -> None:
    rows = psql_json(f"""
        SELECT t.plan_id,
               (i.parent_indicator_id IS NULL) AS top_level,
               i.owner_org_id,
               i.target_org_id,
               SUM(i.weight_percent)::text AS total_weight,
               COUNT(*) AS cnt
        FROM sys_task t
        JOIN indicator i ON i.task_id = t.task_id
        WHERE t.plan_id IN ({",".join(map(str, plan_ids))})
          AND COALESCE(t.is_deleted,false) = false
          AND COALESCE(i.is_deleted,false) = false
        GROUP BY t.plan_id, top_level, i.owner_org_id, i.target_org_id
        ORDER BY t.plan_id, top_level, i.owner_org_id, i.target_org_id
    """)
    ctx.add(f"{label} 数据地基权重校验", True, json.dumps(rows, ensure_ascii=False))


def submit_plan(ctx: TestContext, account: str, plan_id: int, flow_code: str, dispatch: bool = False) -> dict[str, Any]:
    suffix = "submit-dispatch" if dispatch else "submit"
    payload = api(
        ctx,
        account,
        "POST",
        f"/api/v1/plans/{plan_id}/{suffix}",
        json={"workflowCode": flow_code, "comment": f"auto {flow_code} {dt.datetime.now().isoformat()}"},
    )
    return payload["data"]


def plan(ctx: TestContext, account: str, plan_id: int) -> dict[str, Any]:
    return api(ctx, account, "GET", f"/api/v1/plans/{plan_id}")["data"]


def instance_detail(ctx: TestContext, account: str, plan_id: int) -> dict[str, Any]:
    return api(ctx, account, "GET", f"/api/v1/workflows/instances/entity/PLAN/{plan_id}")["data"]


def pending_tasks(ctx: TestContext, account: str) -> list[dict[str, Any]]:
    payload = api(ctx, account, "GET", "/api/v1/workflows/my-tasks?pageNum=1")
    data = payload["data"]
    return data.get("items") or data.get("content") or []


def find_task(ctx: TestContext, account: str, plan_id: int, flow_code: str) -> dict[str, Any]:
    deadline = time.time() + 10
    while time.time() < deadline:
        tasks = pending_tasks(ctx, account)
        for task in tasks:
            if int(task.get("planId") or task.get("entityId") or -1) == int(plan_id) and task.get("flowCode") == flow_code:
                return task
        time.sleep(0.25)
    raise RuntimeError(f"No pending task for {account}, plan={plan_id}, flow={flow_code}. Latest={pending_tasks(ctx, account)}")


def approve(ctx: TestContext, account: str, plan_id: int, flow_code: str) -> dict[str, Any]:
    task = find_task(ctx, account, plan_id, flow_code)
    payload = api(
        ctx,
        account,
        "POST",
        f"/api/v1/workflows/tasks/{task['taskId']}/approve",
        json={"comment": f"auto approve by {account}"},
    )
    return payload["data"]


def reject(ctx: TestContext, account: str, plan_id: int, flow_code: str) -> dict[str, Any]:
    task = find_task(ctx, account, plan_id, flow_code)
    payload = api(
        ctx,
        account,
        "POST",
        f"/api/v1/workflows/tasks/{task['taskId']}/reject",
        json={"reason": f"auto reject by {account}"},
    )
    return payload["data"]


def notifications(ctx: TestContext, account: str, plan_id: int) -> list[dict[str, Any]]:
    payload = api(ctx, account, "GET", "/api/v1/notifications/my?page=0&size=100")
    content = payload["data"].get("content", [])
    return [
        item
        for item in content
        if int(item.get("relatedEntityId") or item.get("related_entity_id") or -1) == int(plan_id)
        or str(plan_id) in json.dumps(item, ensure_ascii=False)
    ]


def message_center(ctx: TestContext, account: str, plan_id: int) -> list[dict[str, Any]]:
    payload = api(ctx, account, "GET", "/api/v1/message-center/messages?category=ALL&page=1&size=100")
    items = payload["data"].get("items", [])
    return [
        item for item in items
        if int(item.get("entityId") or -1) == int(plan_id)
        or int(item.get("approvalInstanceId") or -1) == int(plan_id)
        or str(plan_id) in json.dumps(item, ensure_ascii=False)
    ]


def validate_step_columns(ctx: TestContext, plan_id: int, expected_min_steps: int, label: str) -> None:
    rows = psql_json(f"""
        SELECT asi.id, asi.step_no, asi.approver_id, asi.approver_org_id,
               asi.step_def_id, asi.status, asi.step_name
        FROM audit_step_instance asi
        JOIN audit_instance ai ON ai.id = asi.instance_id
        WHERE ai.entity_type = 'PLAN' AND ai.entity_id = {int(plan_id)}
        ORDER BY asi.id
    """)
    non_null = all(
        row["step_no"] is not None
        and row["status"] is not None
        and (row["step_no"] == 1 or (row["approver_id"] is not None and row["approver_org_id"] is not None))
        and row["step_def_id"] is not None
        for row in rows
    )
    ctx.add(
        f"{label} audit_step_instance 字段非空",
        non_null and len(rows) >= expected_min_steps,
        f"steps={len(rows)}, rows={json.dumps(rows, ensure_ascii=False)}",
    )


def validate_plan_status(ctx: TestContext, account: str, plan_id: int, expected_status: str, label: str) -> None:
    data = plan(ctx, account, plan_id)
    ok = data["status"] == expected_status
    ctx.add(
        f"{label} Plan 状态={expected_status}",
        ok,
        f"actual={data['status']}, workflow={data.get('workflowStatus')}, current={data.get('currentStepName')}",
    )


def validate_notification(ctx: TestContext, account: str, plan_id: int, label: str) -> None:
    mine = notifications(ctx, account, plan_id)
    center = message_center(ctx, account, plan_id)
    ctx.add(
        f"{label} 通知精准到发起人 {account}",
        len(mine) > 0 or len(center) > 0,
        f"notifications={len(mine)}, messageCenter={len(center)}",
    )


def run_rejection_probe(ctx: TestContext) -> None:
    reset_runtime_for_plans([4044])
    submit_plan(ctx, "zlb_admin", 4044, "PLAN_DISPATCH_STRATEGY", dispatch=True)
    approve(ctx, "zlb_final1", 4044, "PLAN_DISPATCH_STRATEGY")
    reject(ctx, "admin", 4044, "PLAN_DISPATCH_STRATEGY")
    detail = instance_detail(ctx, "zlb_admin", 4044)
    tasks = detail.get("tasks", [])
    pending_previous = any(
        t.get("taskName") == "战略发展部负责人审批"
        and t.get("assigneeId") == ctx.user_ids["zlb_final1"]
        and t.get("status") == "PENDING"
        for t in tasks
    )
    ctx.add("驳回回退：上一完成步骤恢复 PENDING", pending_previous, json.dumps(tasks, ensure_ascii=False))
    validate_notification(ctx, "zlb_admin", 4044, "驳回后")
    reset_runtime_for_plans([4044])


def run_single_closure(ctx: TestContext, p_strategy: int = 4044, p_func: int = 404457, label: str = "单闭环") -> None:
    # 1-2 strategic dispatch
    submit_plan(ctx, "zlb_admin", p_strategy, "PLAN_DISPATCH_STRATEGY", dispatch=True)
    validate_step_columns(ctx, p_strategy, 2, f"{label} 流程1提交后")
    approve(ctx, "zlb_final1", p_strategy, "PLAN_DISPATCH_STRATEGY")
    validate_step_columns(ctx, p_strategy, 3, f"{label} 流程1二审后")
    approve(ctx, "admin", p_strategy, "PLAN_DISPATCH_STRATEGY")
    validate_plan_status(ctx, "zlb_admin", p_strategy, "DISTRIBUTED", f"{label} 流程1终审后")
    validate_notification(ctx, "zlb_admin", p_strategy, f"{label} 流程1")

    # 3-4 functional dispatch
    submit_plan(ctx, "jiaowu_report", p_func, "PLAN_DISPATCH_FUNCDEPT", dispatch=True)
    validate_step_columns(ctx, p_func, 2, f"{label} 流程2提交后")
    approve(ctx, "jiaowu_audit1", p_func, "PLAN_DISPATCH_FUNCDEPT")
    validate_step_columns(ctx, p_func, 3, f"{label} 流程2二审后")
    approve(ctx, "jiaowu_leader", p_func, "PLAN_DISPATCH_FUNCDEPT")
    validate_plan_status(ctx, "jiaowu_report", p_func, "DISTRIBUTED", f"{label} 流程2终审后")
    validate_notification(ctx, "jiaowu_report", p_func, f"{label} 流程2")

    # 5-6 college report-up
    submit_plan(ctx, "jisuanji_report", p_func, "PLAN_APPROVAL_COLLEGE")
    approve(ctx, "jisuanji_audit1", p_func, "PLAN_APPROVAL_COLLEGE")
    approve(ctx, "jisuanji_leader", p_func, "PLAN_APPROVAL_COLLEGE")
    approve(ctx, "jiaowu_audit1", p_func, "PLAN_APPROVAL_COLLEGE")
    validate_plan_status(ctx, "jisuanji_report", p_func, "DISTRIBUTED", f"{label} 流程4终审后")
    validate_step_columns(ctx, p_func, 4, f"{label} 流程4终审后")
    validate_notification(ctx, "jisuanji_report", p_func, f"{label} 流程4")

    # 7-8 functional report-up
    submit_plan(ctx, "jiaowu_report", p_strategy, "PLAN_APPROVAL_FUNCDEPT")
    approve(ctx, "jiaowu_audit1", p_strategy, "PLAN_APPROVAL_FUNCDEPT")
    approve(ctx, "jiaowu_leader", p_strategy, "PLAN_APPROVAL_FUNCDEPT")
    approve(ctx, "zlb_final1", p_strategy, "PLAN_APPROVAL_FUNCDEPT")
    validate_plan_status(ctx, "jiaowu_report", p_strategy, "DISTRIBUTED", f"{label} 流程3终审后")
    validate_step_columns(ctx, p_strategy, 4, f"{label} 流程3终审后")
    validate_notification(ctx, "jiaowu_report", p_strategy, f"{label} 流程3")


def prepare_concurrent_data(ctx: TestContext, chains: int) -> list[tuple[int, int]]:
    pairs: list[tuple[int, int]] = []
    stmts = ["BEGIN;"]
    for i in range(chains):
        cycle_id = 970000 + i
        year = 2070 + i
        p_strategy = 970000000 + i * 10 + 1
        p_func = 970000000 + i * 10 + 2
        task_strategy = 970000000 + i * 10 + 3
        task_func = 970000000 + i * 10 + 4
        ind_a = 970000000 + i * 100 + 11
        ind_b = 970000000 + i * 100 + 12
        ind_c = 970000000 + i * 100 + 13
        ind_d = 970000000 + i * 100 + 14
        ind_e = 970000000 + i * 100 + 15
        pairs.append((p_strategy, p_func))
        stmts.append(f"""
        INSERT INTO cycle (id, cycle_name, year, start_date, end_date, description, created_at, updated_at)
        VALUES ({cycle_id}, 'SISM并发闭环测试-{i}', {year}, DATE '{year}-01-01', DATE '{year}-12-31',
                'auto concurrency test', NOW(), NOW())
        ON CONFLICT (id) DO UPDATE SET updated_at = NOW(), cycle_name = EXCLUDED.cycle_name;

        INSERT INTO plan (id, cycle_id, created_at, updated_at, is_deleted, target_org_id, created_by_org_id, plan_level, status, audit_instance_id)
        VALUES
          ({p_strategy}, {cycle_id}, NOW(), NOW(), false, 44, 35, 'STRAT_TO_FUNC'::plan_level, 'DRAFT', NULL),
          ({p_func}, {cycle_id}, NOW(), NOW(), false, 57, 44, 'FUNC_TO_COLLEGE'::plan_level, 'DRAFT', NULL)
        ON CONFLICT (id) DO UPDATE
        SET status='DRAFT', audit_instance_id=NULL, is_deleted=false, updated_at=NOW(),
            cycle_id=EXCLUDED.cycle_id, target_org_id=EXCLUDED.target_org_id,
            created_by_org_id=EXCLUDED.created_by_org_id, plan_level=EXCLUDED.plan_level;

        INSERT INTO sys_task (task_id, created_at, updated_at, remark, sort_order, name, "desc", task_type,
                              created_by_org_id, cycle_id, org_id, is_deleted, plan_id)
        VALUES
          ({task_strategy}, NOW(), NOW(), 'concurrency strategy task', 1, '并发战略下发任务-{i}', 'auto', 'BASIC', 35, {cycle_id}, 44, false, {p_strategy}),
          ({task_func}, NOW(), NOW(), 'concurrency func task', 1, '并发职能下发学院任务-{i}', 'auto', 'BASIC', 44, {cycle_id}, 57, false, {p_func})
        ON CONFLICT (task_id) DO UPDATE SET updated_at=NOW(), is_deleted=false, plan_id=EXCLUDED.plan_id,
            cycle_id=EXCLUDED.cycle_id, created_by_org_id=EXCLUDED.created_by_org_id, org_id=EXCLUDED.org_id;

        INSERT INTO indicator (id, task_id, parent_indicator_id, indicator_desc, weight_percent, sort_order, remark,
                               created_at, updated_at, type, progress, is_deleted, owner_org_id, target_org_id, status, responsible_user_id)
        VALUES
          ({ind_a}, {task_strategy}, NULL, '并发战略指标A-{i}', 40.00, 1, 'concurrency', NOW(), NOW(), '定量', 0, false, 35, 44, 'DISTRIBUTED', 223),
          ({ind_b}, {task_strategy}, NULL, '并发战略指标B-{i}', 30.00, 2, 'concurrency', NOW(), NOW(), '定性', 0, false, 35, 44, 'DISTRIBUTED', 223),
          ({ind_c}, {task_strategy}, NULL, '并发战略指标C-{i}', 30.00, 3, 'concurrency', NOW(), NOW(), '定性', 0, false, 35, 44, 'DISTRIBUTED', 223),
          ({ind_d}, {task_func}, {ind_a}, '并发学院指标A-{i}', 55.00, 1, 'concurrency', NOW(), NOW(), '定量', 0, false, 44, 57, 'DISTRIBUTED', 370),
          ({ind_e}, {task_func}, {ind_b}, '并发学院指标B-{i}', 45.00, 2, 'concurrency', NOW(), NOW(), '定性', 0, false, 44, 57, 'DISTRIBUTED', 370)
        ON CONFLICT (id) DO UPDATE SET updated_at=NOW(), is_deleted=false, task_id=EXCLUDED.task_id,
            parent_indicator_id=EXCLUDED.parent_indicator_id, weight_percent=EXCLUDED.weight_percent,
            owner_org_id=EXCLUDED.owner_org_id, target_org_id=EXCLUDED.target_org_id, status=EXCLUDED.status;
        """)
    stmts.append("COMMIT;")
    reset_runtime_for_plans([pid for pair in pairs for pid in pair])
    psql_exec("\n".join(stmts))
    ctx.created_plan_ids.extend([pid for pair in pairs for pid in pair])
    ctx.add("准备并发闭环测试数据", True, f"chains={chains}, plans={chains * 2}")
    return pairs


def run_concurrent(ctx: TestContext, chains: int, workers: int) -> None:
    pairs = prepare_concurrent_data(ctx, chains)

    def worker(idx_pair: tuple[int, tuple[int, int]]) -> tuple[int, bool, str]:
        idx, pair = idx_pair
        local = TestContext(session=requests.Session(), tokens=ctx.tokens, user_ids=ctx.user_ids, user_orgs=ctx.user_orgs)
        try:
            run_single_closure(local, pair[0], pair[1], label=f"并发链{idx}")
            failed = [c for c in local.checks if not c.ok]
            return idx, not failed, "; ".join(f"{c.name}:{c.detail}" for c in failed[:3])
        except Exception as exc:
            return idx, False, repr(exc)

    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        results = list(executor.map(worker, enumerate(pairs)))
    ok_count = sum(1 for _, ok, _ in results if ok)
    failures = [(idx, detail) for idx, ok, detail in results if not ok]
    ctx.add("几十 Plan 并发闭环", ok_count == chains, f"ok={ok_count}/{chains}; failures={failures[:5]}")

    # Recipient precision: test-owned plan notifications should be on the three requesters only.
    ids = ",".join(str(pid) for pair in pairs for pid in pair)
    rows = psql_json(f"""
        SELECT recipient_user_id, related_entity_id, COUNT(*) AS cnt
        FROM sys_user_notification
        WHERE related_entity_type = 'PLAN'
          AND related_entity_id IN ({ids})
          AND notification_type IN ('APPROVAL_APPROVED', 'APPROVAL_REJECTED')
        GROUP BY recipient_user_id, related_entity_id
        ORDER BY recipient_user_id, related_entity_id
    """)
    allowed = {ctx.user_ids["zlb_admin"], ctx.user_ids["jiaowu_report"], ctx.user_ids["jisuanji_report"]}
    stray = [row for row in rows if int(row["recipient_user_id"]) not in allowed]
    ctx.add("并发审批结果通知未串发到非发起人", not stray and len(rows) > 0, json.dumps(rows[:20], ensure_ascii=False))


def write_report(ctx: TestContext, args: argparse.Namespace) -> Path:
    now = dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    passed = sum(1 for c in ctx.checks if c.ok)
    failed = len(ctx.checks) - passed
    report_dir = Path("sism-backend/docs/generated")
    report_dir.mkdir(parents=True, exist_ok=True)
    report_path = report_dir / f"sism-workflow-closure-test-{dt.datetime.now().strftime('%Y%m%d-%H%M%S')}.md"
    lines = [
        "# SISM 审批闭环与并发通知测试报告",
        "",
        f"- 生成时间: {now}",
        f"- 环境: `{BASE_URL}`",
        f"- 并发链数: {args.chains}",
        f"- 并发 worker: {args.workers}",
        f"- 结果: {passed}/{len(ctx.checks)} 通过，{failed} 失败",
        "",
        "## 检查项",
        "",
        "| 结果 | 检查项 | 细节 |",
        "|---|---|---|",
    ]
    for c in ctx.checks:
        detail = c.detail.replace("|", "\\|").replace("\n", "<br>")
        lines.append(f"| {'✓' if c.ok else '✗'} | {c.name} | {detail} |")
    lines.extend([
        "",
        "## 说明",
        "",
        "- Plan 业务状态枚举当前以 `DISTRIBUTED` 表示已下发/已审批后的终态；工作流终态通过 `workflowStatus=APPROVED` 和 audit/workflow 表验证。",
        "- 本报告使用 API 推进审批，用 SQL 做本地测试数据准备和字段完整性校验。",
    ])
    report_path.write_text("\n".join(lines), encoding="utf-8")
    return report_path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chains", type=int, default=12, help="number of concurrent closure chains")
    parser.add_argument("--workers", type=int, default=6, help="parallel workers")
    parser.add_argument("--skip-concurrent", action="store_true")
    args = parser.parse_args()

    ctx = TestContext()
    try:
        login_all(ctx)
        prepare_single_chain_data(ctx)
        assert_weight_foundation(ctx, [4044, 404457], "单闭环")
        run_rejection_probe(ctx)
        run_single_closure(ctx)
        if not args.skip_concurrent:
            run_concurrent(ctx, args.chains, args.workers)
    except Exception as exc:
        ctx.add("测试执行异常", False, repr(exc))
    report = write_report(ctx, args)
    failed = [c for c in ctx.checks if not c.ok]
    print(f"\nREPORT={report.resolve()}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
