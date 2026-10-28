-- 2026-10-28 权重口径调整：计划内指标权重合计统一归一化为 100%
--
-- 背景：此前只校验「基础性任务」权重合计为 100，发展性任务不参与校验，
-- 导致同一任务内权重可写 110/120 而不被拦截，也不同一计划的多个任务各自 100，
-- 计划级合计远超 100。现按用户定案：一个计划内（战略链取父指标、学院链取子指标）
-- 权重合计必须正好 100。
--
-- 本迁移对存量数据做一次性归一化：
--   1) 合计已为 100 → 不动；
--   2) 全部为空或 0 → 按指标条数等分，余数计入最后一条；
--   3) 其余 → 按现有值等比缩放到 100，保留两位小数，差额计入最后一条。
-- 全程不改动指标归属、不改状态，只调整 weight_percent。

DO $$
DECLARE
    plan_rec RECORD;
    ind_rec RECORD;
    ind_rec2 RECORD;
    ind_cnt INTEGER;
    weight_total NUMERIC;
    blank_cnt INTEGER;
    assign_total NUMERIC;
    weight_new NUMERIC;
    each_weight NUMERIC;
    idx INTEGER;
    scale NUMERIC;
BEGIN
    FOR plan_rec IN
        SELECT DISTINCT t.plan_id
        FROM sys_task t
        JOIN indicator i ON i.task_id = t.task_id
        WHERE COALESCE(t.is_deleted, false) = false
          AND COALESCE(i.is_deleted, false) = false
          AND i.target_org_id IS NOT NULL
        ORDER BY t.plan_id
    LOOP
        -- 战略链（父指标）与学院链（子指标）分别归一化，互不影响
        FOR ind_rec IN
            SELECT
                (i.parent_indicator_id IS NULL) AS is_parent_group
            FROM indicator i
            JOIN sys_task t ON t.task_id = i.task_id
            WHERE t.plan_id = plan_rec.plan_id
            GROUP BY (i.parent_indicator_id IS NULL)
        LOOP
            SELECT
                COUNT(*),
                COALESCE(SUM(COALESCE(i.weight_percent, 0)), 0),
                COUNT(*) FILTER (WHERE i.weight_percent IS NULL OR i.weight_percent <= 0)
            INTO ind_cnt, weight_total, blank_cnt
            FROM indicator i
            JOIN sys_task t ON t.task_id = i.task_id
            WHERE t.plan_id = plan_rec.plan_id
              AND COALESCE(t.is_deleted, false) = false
              AND COALESCE(i.is_deleted, false) = false
              AND i.target_org_id IS NOT NULL
              AND ((i.parent_indicator_id IS NULL) = ind_rec.is_parent_group);

            IF ind_cnt = 0 THEN
                CONTINUE;
            END IF;

            -- 已经正好 100，跳过
            IF weight_total = 100 THEN
                CONTINUE;
            END IF;

            idx := 0;
            assign_total := 0;

            -- 全空 → 等分；否则 → 等比归一化
            IF blank_cnt = ind_cnt OR weight_total = 0 THEN
                each_weight := ROUND(100.0 / ind_cnt, 2);
                scale := NULL;
            ELSE
                each_weight := NULL;
                scale := 100.0 / weight_total;
            END IF;

            FOR ind_rec2 IN
                SELECT i.id, COALESCE(i.weight_percent, 0) AS w
                FROM indicator i
                JOIN sys_task t ON t.task_id = i.task_id
                WHERE t.plan_id = plan_rec.plan_id
                  AND COALESCE(t.is_deleted, false) = false
                  AND COALESCE(i.is_deleted, false) = false
                  AND i.target_org_id IS NOT NULL
                  AND ((i.parent_indicator_id IS NULL) = ind_rec.is_parent_group)
                ORDER BY i.sort_order NULLS LAST, i.id
            LOOP
                idx := idx + 1;
                IF idx = ind_cnt THEN
                    weight_new := 100 - assign_total;
                ELSIF scale IS NULL THEN
                    weight_new := each_weight;
                ELSE
                    weight_new := ROUND(ind_rec2.w * scale, 2);
                END IF;

                IF weight_new < 0 THEN
                    weight_new := 0;
                END IF;

                UPDATE indicator
                SET weight_percent = weight_new,
                    updated_at = NOW()
                WHERE id = ind_rec2.id;

                assign_total := assign_total + weight_new;
            END LOOP;
        END LOOP;
    END LOOP;
END $$;
