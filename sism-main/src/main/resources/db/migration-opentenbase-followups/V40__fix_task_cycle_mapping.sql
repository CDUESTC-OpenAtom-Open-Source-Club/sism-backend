-- ============================================================
-- V40__fix_task_cycle_mapping.sql
-- 修复任务周期映射问题
--
-- 问题：任务被错误地关联到示例数据周期(id=90)，而前端查询的是正式周期(id=4)
-- 导致前端无法获取任务类型，显示为"其他"
--
-- 修复：将 cycle_id = 90 的任务更新为 cycle_id = 4 (2026年度正式周期)
-- ============================================================

-- 步骤1: 查看当前状态（仅查询不更新）
SELECT '=== 修复前 ===' as status;
SELECT 'cycle_id=90的任务' as info, COUNT(*) as count FROM sys_task WHERE cycle_id = 90 AND is_deleted = false;
SELECT 'cycle_id=4的任务' as info, COUNT(*) as count FROM sys_task WHERE cycle_id = 4 AND is_deleted = false;

-- 步骤2: 更新任务的周期ID
UPDATE sys_task
SET cycle_id = 4
WHERE cycle_id = 90 AND is_deleted = false;

-- 步骤3: 验证修复结果
SELECT '=== 修复后 ===' as status;
SELECT 'cycle_id=90的任务' as info, COUNT(*) as count FROM sys_task WHERE cycle_id = 90 AND is_deleted = false;
SELECT 'cycle_id=4的任务' as info, COUNT(*) as count FROM sys_task WHERE cycle_id = 4 AND is_deleted = false;

-- 显示修复后的任务列表
DO $$
DECLARE
    row_data RECORD;
    task_name_column TEXT;
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'sys_task'
          AND column_name = 'name'
    ) THEN
        task_name_column := 'name';
    ELSIF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'sys_task'
          AND column_name = 'task_name'
    ) THEN
        task_name_column := 'task_name';
    END IF;

    IF task_name_column IS NULL THEN
        RAISE NOTICE 'sys_task.name / sys_task.task_name 均不存在，跳过任务列表展示';
        RETURN;
    END IF;

    FOR row_data IN EXECUTE format(
        'SELECT task_id, %I AS task_name, task_type, cycle_id
         FROM sys_task
         WHERE is_deleted = false
         ORDER BY cycle_id, task_id',
        task_name_column
    )
    LOOP
        RAISE NOTICE 'task_id=%, task_name=%, task_type=%, cycle_id=%',
            row_data.task_id, row_data.task_name, row_data.task_type, row_data.cycle_id;
    END LOOP;
END
$$;
