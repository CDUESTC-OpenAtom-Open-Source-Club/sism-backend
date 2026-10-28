-- 2026-10-28 权重口径调整：计划内指标权重合计统一归一化为 100%
--
-- 背景：此前只校验「基础性任务」权重合计为 100，发展性任务不参与校验，
-- 导致同一任务内权重可写 110/120 而不被拦截，也不同一计划的多个任务各自 100，
-- 计划级合计远超 100。现按用户定案：一个计划内（战略链取父指标、学院链取子指标）
-- 权重合计必须正好 100。
--
-- 分组口径与校验侧（BasicTaskWeightValidationService）及运行时分配
-- （BusinessImportApplicationService#loadPlanIndicatorsForPlan）严格一致：
--   按 (plan_id, plan.target_org_id) 分组；
--   战略链取父指标；学院链取本部门创建（owner_org_id = plan.created_by_org_id）的子指标；
--   只处理归属该计划目标部门的指标（i.target_org_id = plan.target_org_id）。
--
-- 分配规则与运行时逐条一致：
--   1) 权重全部为空/待分配 → 按条数等分（每项保底 0.01，余数计入最后一条）；
--   2) 部分为空/待分配、且已填合计未满 100 → 保留已填值，空项平分剩余额度；
--   3) 手填合计已满/超额 → 不改写（与运行时一致），交给校验拦截；
--   4) 全部已填但合计不等于 100 → **不改写**（存量手填值同样受"不得被系统改写"约束），
--      仅输出提示交人工处理。运行时对同一情形也是不改写，此处保持一致。
--
-- 硬约束：indicator 表上有 CHECK (weight_percent > 0)，
--   任何写入都不允许为 0 或负数；每项先保底 0.01，
--   额度不足以保底时跳过该组并输出提示，不写非法值。
-- 全程不改动指标归属、不改状态，只调整 weight_percent。
--
-- 语法注意：聚合用 CASE WHEN 而不是 FILTER —— FILTER 是 PostgreSQL 9.4 才有的语法，
-- 而 openGauss 内核的国产库需要兼容更早的语法（与 migration-opentenbase-followups 下其余脚本一致）。

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
    target_cnt INTEGER;
BEGIN
    FOR grp IN
        SELECT t.plan_id,
               p.target_org_id,
               (i.parent_indicator_id IS NULL) AS is_parent_group
        FROM sys_task t
        JOIN plan p ON p.id = t.plan_id
        JOIN indicator i ON i.task_id = t.task_id
        WHERE COALESCE(t.is_deleted, false) = false
          AND COALESCE(p.is_deleted, false) = false
          AND COALESCE(i.is_deleted, false) = false
          AND i.target_org_id IS NOT NULL
          AND i.target_org_id = p.target_org_id
        GROUP BY t.plan_id, p.target_org_id, (i.parent_indicator_id IS NULL)
    LOOP
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
          AND COALESCE(p.is_deleted, false) = false
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
        --   权重全空 → 对全部指标等分；
        --   部分留空且已填合计未满 100 → 只对留空项等分剩余额度；
        --   其余（已满/超额、全部已填但合计≠100）→ 不改写，交人工处理。
        IF blank_cnt = ind_cnt THEN
            use_all := TRUE;
            remaining := 100.0;
        ELSIF blank_cnt > 0 AND filled_total < 100 THEN
            use_all := FALSE;
            remaining := 100.0 - filled_total;
        ELSE
            RAISE NOTICE '[V100] plan=% org=% 组内 % 项（已填 %，留空 % 项）无法在不改动手填值的前提下归一化，保留原值交人工处理',
                    grp.plan_id, grp.target_org_id, ind_cnt, filled_total, blank_cnt;
            CONTINUE;
        END IF;

        IF use_all THEN
            target_cnt := ind_cnt;
        ELSE
            target_cnt := blank_cnt;
        END IF;

        IF remaining < 0.01 * target_cnt THEN
            RAISE NOTICE '[V100] plan=% org=% 额度 % 不足以给 % 项各保底 0.01，跳过',
                    grp.plan_id, grp.target_org_id, remaining, target_cnt;
            CONTINUE;
        END IF;

        each_weight := TRUNC((remaining - 0.01 * target_cnt) / target_cnt, 2);

        assigned := 0;
        idx := 0;
        FOR rec IN
            SELECT i.id
            FROM indicator i
            JOIN sys_task t ON t.task_id = i.task_id
            JOIN plan p ON p.id = t.plan_id
            WHERE t.plan_id = grp.plan_id
              AND i.target_org_id = grp.target_org_id
              AND ((i.parent_indicator_id IS NULL) = grp.is_parent_group)
              AND COALESCE(t.is_deleted, false) = false
              AND COALESCE(p.is_deleted, false) = false
              AND COALESCE(i.is_deleted, false) = false
              AND (grp.is_parent_group
                   OR (i.owner_org_id IS NOT NULL AND i.owner_org_id = p.created_by_org_id))
              AND (use_all OR i.weight_percent IS NULL OR i.weight_percent <= 0.01)
            ORDER BY i.id
        LOOP
            idx := idx + 1;
            IF idx = target_cnt THEN
                w := remaining - assigned;
            ELSE
                w := each_weight + 0.01;
            END IF;
            IF w <= 0 THEN
                w := 0.01;
            END IF;
            UPDATE indicator SET weight_percent = w WHERE id = rec.id;
            assigned := assigned + w;
        END LOOP;

        RAISE NOTICE '[V100] plan=% org=% 等分 % 项，分配额度 %',
                grp.plan_id, grp.target_org_id, target_cnt, remaining;
    END LOOP;
END $$;
