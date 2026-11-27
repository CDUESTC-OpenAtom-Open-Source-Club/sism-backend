-- sys_org clean seed
-- Scope:
-- - Keep all organizations currently used by the database.
-- - Rebuild logical type / parent / level instead of inheriting dirty database values.

BEGIN;

INSERT INTO public.sys_org (
    id,
    name,
    type,
    is_active,
    sort_order,
    created_at,
    updated_at,
    parent_org_id,
    level,
    is_deleted
)
VALUES
    (35, '战略发展部', 'admin', true, 0, NOW(), NOW(), NULL, 1, false),

    (36, '党委办公室 | 党委统战部', 'functional', true, 1, NOW(), NOW(), 35, 2, false),
    (38, '党委宣传部 | 宣传策划部', 'functional', true, 3, NOW(), NOW(), 35, 2, false),
    (40, '人力资源部', 'functional', true, 5, NOW(), NOW(), 35, 2, false),
    (41, '党委学工部 | 学生工作处', 'functional', true, 6, NOW(), NOW(), 35, 2, false),
    (42, '党委保卫部 | 保卫处', 'functional', true, 7, NOW(), NOW(), 35, 2, false),
    (43, '学校综合办公室', 'functional', true, 8, NOW(), NOW(), 35, 2, false),
    (44, '教务处', 'functional', true, 9, NOW(), NOW(), 35, 2, false),
    (45, '科技处', 'functional', true, 10, NOW(), NOW(), 35, 2, false),
    (46, '财务部', 'functional', true, 11, NOW(), NOW(), 35, 2, false),
    (47, '招生工作处', 'functional', true, 12, NOW(), NOW(), 35, 2, false),
    (48, '就业创业指导中心', 'functional', true, 13, NOW(), NOW(), 35, 2, false),
    (49, '实验室建设管理处', 'functional', true, 14, NOW(), NOW(), 35, 2, false),
    (50, '数字校园建设办公室', 'functional', true, 15, NOW(), NOW(), 35, 2, false),
    (51, '图书馆 | 档案馆', 'functional', true, 16, NOW(), NOW(), 35, 2, false),
    (52, '后勤资产处', 'functional', true, 17, NOW(), NOW(), 35, 2, false),
    (53, '继续教育部', 'functional', true, 18, NOW(), NOW(), 35, 2, false),
    (62, '国际教育学院', 'functional', true, 27, NOW(), NOW(), 35, 2, false),
    (63, '预算部', 'functional', true, 28, NOW(), NOW(), 35, 2, false),
    (64, '什邡管委会', 'functional', true, 29, NOW(), NOW(), 35, 2, false),
    (65, '产教合作部', 'functional', true, 30, NOW(), NOW(), 35, 2, false),
    (66, '战略发展·部', 'functional', true, 31, NOW(), NOW(), 35, 2, false),

    (55, '马克思主义学院', 'academic', true, 20, NOW(), NOW(), 35, 2, false),
    (56, '工学院', 'academic', true, 21, NOW(), NOW(), 35, 2, false),
    (57, '计算机学院', 'academic', true, 22, NOW(), NOW(), 35, 2, false),
    (58, '商学院', 'academic', true, 23, NOW(), NOW(), 35, 2, false),
    (59, '文理学院', 'academic', true, 24, NOW(), NOW(), 35, 2, false),
    (60, '艺术与科技学院', 'academic', true, 25, NOW(), NOW(), 35, 2, false),
    (61, '航空学院', 'academic', true, 26, NOW(), NOW(), 35, 2, false)
ON CONFLICT (id) DO UPDATE
SET
    name = EXCLUDED.name,
    type = EXCLUDED.type,
    is_active = EXCLUDED.is_active,
    sort_order = EXCLUDED.sort_order,
    updated_at = EXCLUDED.updated_at,
    parent_org_id = EXCLUDED.parent_org_id,
    level = EXCLUDED.level,
    is_deleted = EXCLUDED.is_deleted;

COMMIT;
