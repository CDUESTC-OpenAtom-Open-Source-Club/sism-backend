-- 孤儿指标体检（issue #81 项①/D2 配套，只读）
-- 场景 A：软删父 + 存活子（D2 已容忍：子指标会以根指标身份出现在任务接口）
-- 场景 B：parent 指向不存在的 id（物理残留）
-- 输出：孤儿子清单 + 各任务统计；全部只读，可随时重跑
SELECT
    child.id                       AS child_id,
    child.task_id                  AS task_id,
    child.parent_indicator_id      AS deleted_parent_id,
    left(child.indicator_desc, 30) AS child_desc,
    parent.is_deleted              AS parent_deleted
FROM public.indicator child
LEFT JOIN public.indicator parent ON parent.id = child.parent_indicator_id
WHERE child.is_deleted = false
  AND child.parent_indicator_id IS NOT NULL
  AND (parent.id IS NULL OR parent.is_deleted = true)
ORDER BY child.task_id, child.id;

-- 汇总：各任务的孤儿子指标数量
SELECT child.task_id, COUNT(*) AS orphan_children
FROM public.indicator child
JOIN public.indicator parent ON parent.id = child.parent_indicator_id
WHERE child.is_deleted = false
  AND child.parent_indicator_id IS NOT NULL
  AND parent.is_deleted = true
GROUP BY child.task_id
ORDER BY orphan_children DESC;
