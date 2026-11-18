-- 2026-11-17（学院线粒度改造：学院 × 归口部门 = 一条「线」）
--
-- 业务口径（2026-11-16 用户拍板，不可再自行改动）：
--   ① 一条下发关系 = 某个职能部门（归口） → 某个学院，是独立关联；不是「给这个学院发一次就全部发完」。
--   ② 战略发展部只是代发通道（created_by_org_id 恒为 35），内容归属方仍是职能部门。
--   ③ 因此：一个学院在同一考核年度可以有多个归口部门各自的「线」，每条线独立下发审批、独立状态。
--
-- 本迁移把 plan.functional_org_id 从「属性」升格为「线身份」：
--   线身份 = (cycle_id, plan_level=FUNC_TO_COLLEGE, created_by_org_id=35 战略发展部,
--              target_org_id=学院, functional_org_id=归口部门)
--
-- 做三件事（顺序有关，均可重复执行）：
--   ① 存量回填：created_by=35 / FUNC_TO_COLLEGE / functional_org_id 为空、且**有存活子指标**的计划，
--      按「子指标 → 父级核心指标.target_org_id 取 MIN」回填归口（与 V104 逐字同口径）。
--   ② 空壳归档：created_by=35 / FUNC_TO_COLLEGE / functional_org_id 仍为空、且**无存活子指标**的空壳行
--      归档（is_deleted=true）。这些行是矩阵自愈按「战略部 × 学院」笛卡尔积造出来的，
--      既不携带归口身份、也没有任何指标，在新唯一索引下会抢占「不带归口维度」的查询
--      （学院导入取计划时命中它 → 「该线已下发」误判 / 同名子指标被重复创建）。
--   ③ 唯一索引换代：删 ux_plan_active_business_key（4 键）→ 建 5 键唯一索引。
--
-- legacy 空壳（created_by≠35，即改造前「职能部门创建」的下发计划）一律不动：
--   2026-11-05 需求 4 定的是「存量保留为只读历史、禁止删除」，它们与新链路并存是刻意保留的。
--
-- 为什么 5 键索引不会与存量数据冲突：
--   旧索引 ux_plan_active_business_key (cycle_id, plan_level, created_by_org_id, target_org_id)
--   WHERE NOT is_deleted 保证「同一 (cycle, level, created_by, target)」最多只有一条存活行；
--   新索引只是在这个键后面追加一列 functional_org_id，唯一性是旧索引的超集 → 建索引必然成功。

-- ① 存量回填：有存活子指标的行按父级核心指标.target_org_id 取 MIN 回填归口（V104 同口径）
UPDATE public.plan p
SET functional_org_id = backfill.min_target_org,
    updated_at = LOCALTIMESTAMP
FROM (
    SELECT t.plan_id AS plan_id, MIN(parent.target_org_id) AS min_target_org
    FROM public.sys_task t
    JOIN public.indicator child ON child.task_id = t.task_id
         AND child.parent_indicator_id IS NOT NULL
         AND COALESCE(child.is_deleted, false) = false
    JOIN public.indicator parent ON parent.id = child.parent_indicator_id
    WHERE parent.target_org_id IS NOT NULL
    GROUP BY t.plan_id
) backfill
WHERE p.id = backfill.plan_id
  AND p.plan_level = 'FUNC_TO_COLLEGE'
  AND p.created_by_org_id = 35
  AND p.functional_org_id IS NULL
  AND COALESCE(p.is_deleted, false) = false;

-- ② 空壳归档：归口仍为空（回填后仍无归口 = 父级反查不出）与「本就没有存活子指标」的空壳行一律归档。
--    条件用「无存活子指标」而不是「归口为空」兜底，避免把「有存活子指标但父级反查不出归口」
--    的异常行误归档（那是数据异常，应保留待人工处理）。
UPDATE public.plan p
SET is_deleted = true,
    updated_at = LOCALTIMESTAMP
WHERE p.plan_level = 'FUNC_TO_COLLEGE'
  AND p.created_by_org_id = 35
  AND p.functional_org_id IS NULL
  AND COALESCE(p.is_deleted, false) = false
  AND NOT EXISTS (
      SELECT 1
      FROM public.sys_task t
      JOIN public.indicator child ON child.task_id = t.task_id
           AND child.parent_indicator_id IS NOT NULL
           AND COALESCE(child.is_deleted, false) = false
      WHERE t.plan_id = p.id
  );

-- ③ 唯一索引换代：线身份（含归口部门）才是唯一键
--    COALESCE 是必须的：PG 的 NULL 不参与唯一性判定，不包 COALESCE 等于没加约束。
DROP INDEX IF EXISTS public.ux_plan_active_business_key;

CREATE UNIQUE INDEX IF NOT EXISTS ux_plan_active_line_key
ON public.plan (cycle_id, plan_level, created_by_org_id, target_org_id, (COALESCE(functional_org_id, 0)))
WHERE COALESCE(is_deleted, false) = false;

COMMENT ON INDEX public.ux_plan_active_line_key IS
    '2026-11-17 学院线粒度：一条线 = (考核年度, 计划级别, 代发通道=战略发展部, 目标学院, 归口部门)。'
    '同一学院可并存多条归口不同的线；COALESCE(functional_org_id, 0) 必带，否则 PG 的 NULL 不参与唯一性判定。';
