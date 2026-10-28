-- 2026-10-28 权重口径调整：计划内指标权重合计统一归一化为 100%
--
-- 背景：此前只校验「基础性任务」权重合计为 100，发展性任务不参与校验，
-- 导致同一任务内权重可写 110/120 而不被拦截，也不同一计划的多个任务各自 100，
-- 计划级合计远超 100。现按用户定案：一个计划内（战略链取父指标、学院链取子指标）
-- 权重合计必须正好 100。
--
-- 分组口径与校验侧（BasicTaskWeightValidationService）严格一致：
--   战略链：按 (plan_id, target_org_id) 取父指标；
--   学院链：按 (plan_id, target_org_id) 取本部门创建、目标为该学院的子指标。
--   —— 之前按 plan_id 单独分组、且不按 target_org_id 过滤，会把不同部门的指标混在一组，
--      与校验口径不一致。
--
-- 分配规则与运行时（BusinessImportApplicationService#normalizePlanWeights）逐条一致：
--   1) 权重全部为空/待分配 → 按条数等分（每项保底 0.01，余数计入最后一条）；
--   2) 部分为空/待分配、且已填合计未满 100 → 保留已填值，空项平分剩余额度；
--   3) 部分为空/待分配、但已填合计已满/超额 → 不改写（与运行时一致，保留手填值），交给校验拦截；
--   4) 全部已填但合计不等于 100 → 存量数据专用：按现有值等比缩放到 100
--      （运行时对手填超额不会自动改写；存量数据没有"本次留空"的语义，故按比例修正）。
--
-- 硬约束：indicator 表上有 CHECK (weight_percent > 0)，
--   因此任何写入都不允许为 0 或负数；每项先保底 0.01，
--   额度不足以保底时跳过该组并输出提示（交由后续人工处理），不写非法值。
-- 全程不改动指标归属、不改状态，只调整 weight_percent。

DO $$
DECLARE
    grp RECORD;
    ind_cnt INTEGER;
    blank_cnt INTEGER;
    filled_total NUMERIC;
    remaining NUMERIC;
    each_weight NUMERIC;
    assigned NUMERIC;
    w NUMERIC;
    rec RECORD;
    idx INTEGER;
    use_all BOOLEAN;
    scope_even BOOLEAN;
    target_cnt INTEGER;
BEGIN
    FOR grp IN
        SELECT t.plan_id,
               i.target_org_id,
               (i.parent_indicator_id IS NULL) AS is_parent_group
        FROM sys_task t
        JOIN indicator i ON i.task_id = t.task_id
        WHERE COALESCE(t.is_deleted, false) = false
          AND COALESCE(i.is_deleted, false) = false
          AND i.target_org_id IS NOT NULL
        GROUP BY t.plan_id, i.target_org_id, (i.parent_indicator_id IS NULL)
    LOOP
        -- 注意：这里用 CASE WHEN 而不是聚合 FILTER —— FILTER 是 PostgreSQL 9.4 才有的语法，
        -- 而 openTenbase（openGauss 内核）目录下的迁移脚本需要兼容更早的语法。
        SELECT COUNT(*),
               COALESCE(SUM(CASE WHEN i.weight_percent IS NULL OR i.weight_percent <= 0.01
                                 THEN 1 ELSE 0 END), 0),
               COALESCE(SUM(CASE WHEN i.weight_percent > 0.01
                                 THEN i.weight_percent ELSE 0 END), 0)
        INTO ind_cnt, blank_cnt, filled_total
        FROM indicator i
        JOIN sys_task t ON t.task_id = i.task_id
        JOIN plan p ON p.id = t.plan_id
        WHERE t.plan_id = grp.plan_id
          AND i.target_org_id = grp.target_org_id
          AND ((i.parent_indicator_id IS NULL) = grp.is_parent_group)
          AND COALESCE(t.is_deleted, false) = false
          AND COALESCE(i.is_deleted, false) = false
          -- 学院链只算本部门创建的子指标（与校验侧一致）
          AND (grp.is_parent_group
               OR (i.owner_org_id IS NOT NULL AND i.owner_org_id = p.created_by_org_id));

        IF ind_cnt = 0 THEN
            CONTINUE;
        END IF;

        -- 已经正好 100，跳过
        IF blank_cnt = 0 AND filled_total = 100 THEN
            CONTINUE;
        END IF;

        -- 决定分配方式：
        --   scope_even = TRUE  → 等分（每项保底 0.01，余数给最后一项）
        --   use_all    = TRUE  → 对该组全部指标分配，否则只对留空项分配
        IF blank_cnt = ind_cnt THEN
            scope_even := TRUE;
            use_all := TRUE;
            remaining := 100.0;
        ELSIF blank_cnt > 0 AND filled_total < 100 THEN
            scope_even := TRUE;
            use_all := FALSE;
            remaining := 100.0 - filled_total;
        ELSIF blank_cnt > 0 THEN
            -- 已填合计已满/超额 → 与运行时一致：**不改写**（包括不整体等分），
            -- 保留手填值与待分配标记，交给下发校验拦截并提示人工调整。
            RAISE NOTICE '[V100] plan=% org=% 已填合计 % 已满/超额，保留手填值不改写，交由校验拦截',
                    grp.plan_id, grp.target_org_id, filled_total;
            CONTINUE;
        ELSE
            -- 全部已填但合计不等于 100 → 存量数据等比缩放
            scope_even := FALSE;
            use_all := TRUE;
            remaining := 100.0;
        END IF;

        IF scope_even THEN
            -- 本次实际参与分配的指标条数（整体等分=全部；只填空项=空项数）
            IF use_all THEN
                target_cnt := ind_cnt;
            ELSE
                target_cnt := blank_cnt;
            END IF;
            IF remaining < 0.01 * target_cnt THEN
                RAISE NOTICE '[V100] plan=% org=% 指标数=% 额度不足，无法保证每项>0，跳过',
                        grp.plan_id, grp.target_org_id, target_cnt;
                CONTINUE;
            END IF;
            each_weight := TRUNC((remaining - 0.01 * target_cnt) / target_cnt, 2);
        ELSE
            target_cnt := ind_cnt;
            if filled_total <= 0 THEN
                CONTINUE;
            END IF;
            IF 100.0 < 0.01 * ind_cnt THEN
                RAISE NOTICE '[V100] plan=% org=% 指标数=% 过多，无法保证每项>0，跳过',
                        grp.plan_id, grp.target_org_id, ind_cnt;
                CONTINUE;
            END IF;
            each_weight := TRUNC((100.0 - 0.01 * ind_cnt) / ind_cnt, 2);
        END IF;

        assigned := 0;
        idx := 0;
        FOR rec IN
            SELECT i.id, i.weight_percent
            FROM indicator i
            JOIN sys_task t ON t.task_id = i.task_id
            JOIN plan p ON p.id = t.plan_id
            WHERE t.plan_id = grp.plan_id
              AND i.target_org_id = grp.target_org_id
              AND ((i.parent_indicator_id IS NULL) = grp.is_parent_group)
              AND COALESCE(t.is_deleted, false) = false
              AND COALESCE(i.is_deleted, false) = false
              AND (grp.is_parent_group
                   OR (i.owner_org_id IS NOT NULL AND i.owner_org_id = p.created_by_org_id))
              AND (use_all OR i.weight_percent IS NULL OR i.weight_percent <= 0.01)
            ORDER BY i.id
        LOOP
            idx := idx + 1;
            IF scope_even THEN
                IF idx = target_cnt THEN
                    w := remaining - assigned;
                ELSE
                    w := each_weight + 0.01;
                END IF;
            ELSE
                IF idx = target_cnt THEN
                    w := 100.0 - assigned;
                ELSE
                    w := TRUNC(rec.weight_percent / filled_total * 100.0, 2);
                    -- 不允许吃掉后续项的保底空间，保证每项都能留出 0.01
                    w := LEAST(w, 100.0 - 0.01 * (ind_cnt - idx));
                END IF;
            END IF;
            IF w <= 0 THEN
                w := 0.01;
            END IF;
            UPDATE indicator SET weight_percent = w WHERE id = rec.id;
            assigned := assigned + w;
        END LOOP;

        IF scope_even THEN
            RAISE NOTICE '[V100] plan=% org=% 等分 % 项，分配额度 %',
                    grp.plan_id, grp.target_org_id, ind_cnt, remaining;
        ELSE
            RAISE NOTICE '[V100] plan=% org=% 已按比例缩放 % 项到 100%%', grp.plan_id, grp.target_org_id, ind_cnt;
        END IF;
    END LOOP;
END $$;
