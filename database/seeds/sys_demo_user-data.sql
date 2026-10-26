-- Demo (full-flow test) accounts
-- Each department gets one demo account with all workflow roles assigned
-- (职能部门 1,2,3,4；二级学院 1,2,3——学院不设分管校领导席位，见 2026-10-26 定案).
-- Demo accounts still follow the same org-scope filtering as real users.
-- Extra roles let a single in-org account verify reporter + approver + strategy head + VP
-- seats without creating separate test people for each department.
-- Password for all demo accounts: admin123

-- ============================================================
-- 1. Insert demo user records (is_demo = true)
-- ============================================================

INSERT INTO public.sys_user (
    id, created_at, updated_at, is_active, password_hash, real_name, sso_id, username, org_id, avatar_url, token_version, is_demo
)
VALUES
    -- 战略发展部
    (401, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '战略发展部[演示]', NULL, 'zlb_demo', 35, NULL, 0, true),

    -- 职能部门
    (402, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '党委办[演示]', NULL, 'dangban_demo', 36, NULL, 0, true),
    (403, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '纪委办[演示]', NULL, 'jiwei_demo', 37, NULL, 0, true),
    (404, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '党委宣传部[演示]', NULL, 'dangxuan_demo', 38, NULL, 0, true),
    (405, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '党委组织部[演示]', NULL, 'zuzhi_demo', 39, NULL, 0, true),
    (406, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '人力资源部[演示]', NULL, 'renli_demo', 40, NULL, 0, true),
    (407, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '党委学工部[演示]', NULL, 'xuegong_demo', 41, NULL, 0, true),
    (408, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '保卫处[演示]', NULL, 'baowei_demo', 42, NULL, 0, true),
    (409, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '学校综合办[演示]', NULL, 'zonghe_demo', 43, NULL, 0, true),
    (410, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '教务处[演示]', NULL, 'jiaowu_demo', 44, NULL, 0, true),
    (411, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '科技处[演示]', NULL, 'keji_demo', 45, NULL, 0, true),
    (412, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '财务部[演示]', NULL, 'caiwu_demo', 46, NULL, 0, true),
    (413, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '招生工作处[演示]', NULL, 'zhaosheng_demo', 47, NULL, 0, true),
    (414, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '就业创业中心[演示]', NULL, 'jiuye_demo', 48, NULL, 0, true),
    (415, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '实验室管理处[演示]', NULL, 'shiyanshi_demo', 49, NULL, 0, true),
    (416, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '数字校园办[演示]', NULL, 'shuzi_demo', 50, NULL, 0, true),
    (417, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '图书馆档案馆[演示]', NULL, 'tushu_demo', 51, NULL, 0, true),
    (418, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '后勤资产处[演示]', NULL, 'houqin_demo', 52, NULL, 0, true),
    (419, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '继续教育部[演示]', NULL, 'jixujiaoyu_demo', 53, NULL, 0, true),
    (420, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '国际交流处[演示]', NULL, 'guoji_demo', 54, NULL, 0, true),

    -- 二级学院
    (421, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '马院[演示]', NULL, 'makesi_demo', 55, NULL, 0, true),
    (422, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '工学院[演示]', NULL, 'gongxue_demo', 56, NULL, 0, true),
    (423, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '计算机学院[演示]', NULL, 'jisuanji_demo', 57, NULL, 0, true),
    (424, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '商学院[演示]', NULL, 'shangxue_demo', 58, NULL, 0, true),
    (425, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '文理学院[演示]', NULL, 'wenli_demo', 59, NULL, 0, true),
    (426, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '艺术与科技学院[演示]', NULL, 'yishukeji_demo', 60, NULL, 0, true),
    (427, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '航空学院[演示]', NULL, 'hangkong_demo', 61, NULL, 0, true),
    (428, NOW(), NOW(), true, '$2a$10$uS55dBSn9Rhp/OTJZK2iuu2r5B3gwL/WJygS8oudo2deLaMU6m/0.', '国际教育学院[演示]', NULL, 'guojijiaoyu_demo', 62, NULL, 0, true)
ON CONFLICT (id) DO UPDATE SET
    updated_at = EXCLUDED.updated_at,
    is_active  = EXCLUDED.is_active,
    password_hash = EXCLUDED.password_hash,
    real_name  = EXCLUDED.real_name,
    username   = EXCLUDED.username,
    org_id     = EXCLUDED.org_id,
    is_demo    = EXCLUDED.is_demo,
    token_version = EXCLUDED.token_version;

-- ============================================================
-- 2. Assign ALL 4 roles to each demo account
-- ============================================================

INSERT INTO public.sys_user_role (user_id, role_id, created_at)
VALUES
    -- 战略发展部 (roles 1,3,4 — 不需要 role 2)
    (401, 1, NOW()), (401, 2, NOW()), (401, 3, NOW()), (401, 4, NOW()),

    -- 职能部门 (roles 1,2,3,4)
    (402, 1, NOW()), (402, 2, NOW()), (402, 3, NOW()), (402, 4, NOW()),
    (403, 1, NOW()), (403, 2, NOW()), (403, 3, NOW()), (403, 4, NOW()),
    (404, 1, NOW()), (404, 2, NOW()), (404, 3, NOW()), (404, 4, NOW()),
    (405, 1, NOW()), (405, 2, NOW()), (405, 3, NOW()), (405, 4, NOW()),
    (406, 1, NOW()), (406, 2, NOW()), (406, 3, NOW()), (406, 4, NOW()),
    (407, 1, NOW()), (407, 2, NOW()), (407, 3, NOW()), (407, 4, NOW()),
    (408, 1, NOW()), (408, 2, NOW()), (408, 3, NOW()), (408, 4, NOW()),
    (409, 1, NOW()), (409, 2, NOW()), (409, 3, NOW()), (409, 4, NOW()),
    (410, 1, NOW()), (410, 2, NOW()), (410, 3, NOW()), (410, 4, NOW()),
    (411, 1, NOW()), (411, 2, NOW()), (411, 3, NOW()), (411, 4, NOW()),
    (412, 1, NOW()), (412, 2, NOW()), (412, 3, NOW()), (412, 4, NOW()),
    (413, 1, NOW()), (413, 2, NOW()), (413, 3, NOW()), (413, 4, NOW()),
    (414, 1, NOW()), (414, 2, NOW()), (414, 3, NOW()), (414, 4, NOW()),
    (415, 1, NOW()), (415, 2, NOW()), (415, 3, NOW()), (415, 4, NOW()),
    (416, 1, NOW()), (416, 2, NOW()), (416, 3, NOW()), (416, 4, NOW()),
    (417, 1, NOW()), (417, 2, NOW()), (417, 3, NOW()), (417, 4, NOW()),
    (418, 1, NOW()), (418, 2, NOW()), (418, 3, NOW()), (418, 4, NOW()),
    (419, 1, NOW()), (419, 2, NOW()), (419, 3, NOW()), (419, 4, NOW()),
    (420, 1, NOW()), (420, 2, NOW()), (420, 3, NOW()), (420, 4, NOW()),

    -- 二级学院 (roles 1,2,3)
    -- 2026-10-26 用户定案：二级学院不设「分管校领导」席位（role 4）——
    -- 学院只设「填报人」（role 1）与「审核人」（role 2）两个席位；
    -- role 3 保留给演示账号跨环节调试（真实账号不绑定）。
    (421, 1, NOW()), (421, 2, NOW()), (421, 3, NOW()),
    (422, 1, NOW()), (422, 2, NOW()), (422, 3, NOW()),
    (423, 1, NOW()), (423, 2, NOW()), (423, 3, NOW()),
    (424, 1, NOW()), (424, 2, NOW()), (424, 3, NOW()),
    (425, 1, NOW()), (425, 2, NOW()), (425, 3, NOW()),
    (426, 1, NOW()), (426, 2, NOW()), (426, 3, NOW()),
    (427, 1, NOW()), (427, 2, NOW()), (427, 3, NOW()),
    (428, 1, NOW()), (428, 2, NOW()), (428, 3, NOW())
ON CONFLICT DO NOTHING;

-- Keep the user sequence aligned after explicit ID inserts.
SELECT setval(
    pg_get_serial_sequence('public.sys_user', 'id'),
    COALESCE((SELECT MAX(id) FROM public.sys_user), 1),
    (SELECT COUNT(*) > 0 FROM public.sys_user)
);
