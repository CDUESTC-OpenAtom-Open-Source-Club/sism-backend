-- indicator clean seed
-- Status rule:
-- - indicator.status is an indicator-level lifecycle/projection field.
-- - It is not the authoritative package status; plan.status is the main distribution state.
-- - Clean-seed baseline keeps the WHOLE matrix consistent: every plan container is DRAFT
--   (see plan-data.sql) and every indicator is DRAFT to match its parent plan container.
-- - Distribution is a runtime action (single / batch / import). Since the backend now
--   promotes the owning DRAFT plan to DISTRIBUTED whenever an indicator is distributed,
--   the two layers can no longer diverge ("指标已下发、计划未下发" 缺陷已由代码修复封堵).

BEGIN;

INSERT INTO public.indicator (
    id,
    task_id,
    parent_indicator_id,
    indicator_desc,
    weight_percent,
    sort_order,
    remark,
    created_at,
    updated_at,
    type,
    progress,
    is_deleted,
    owner_org_id,
    target_org_id,
    status,
    responsible_user_id
)
VALUES
    -- ========== 党委办公室 (org_id=36, task_id=41001 & 41021) ==========
    (2001, 41001, NULL, '完成党委办公室年度重点工作分解与落实', 40.00, 1, '战略发展部下发至党委办公室', NOW(), NOW(), '定量', 0, false, 35, 36, 'DRAFT', 191),
    (2002, 41001, NULL, '形成党委统战领域专项推进台账', 30.00, 2, '战略发展部下发至党委办公室', NOW(), NOW(), '定性', 0, false, 35, 36, 'DRAFT', 191),
    (2039, 41001, NULL, '完成重点提案办理与反馈督办', 30.00, 3, '战略发展部下发至党委办公室', NOW(), NOW(), '定性', 0, false, 35, 36, 'DRAFT', 191),
    (2040, 41021, NULL, '落实校领导交办的临时性重要专项', 100.00, 1, '战略发展部下发至党委办公室', NOW(), NOW(), '定性', 0, false, 35, 36, 'DRAFT', 191),

    -- ========== 保卫处 (org_id=42, task_id=41002) ==========
    (2003, 41002, NULL, '完成保卫处年度安全巡检计划编制', 40.00, 1, '战略发展部拟下发至保卫处', NOW(), NOW(), '定量', 0, false, 35, 42, 'DRAFT', 215),
    (2004, 41002, NULL, '建立重点区域隐患整改闭环机制', 30.00, 2, '战略发展部拟下发至保卫处', NOW(), NOW(), '定性', 0, false, 35, 42, 'DRAFT', 215),
    (2043, 41002, NULL, '开展消防安全应急演练与培训', 30.00, 3, '战略发展部拟下发至保卫处', NOW(), NOW(), '定量', 0, false, 35, 42, 'DRAFT', 215),

    -- ========== 教务处 (org_id=44, task_id=41003 & 41022) ==========
    (2005, 41003, NULL, '推进教务处教学质量提升专项工作', 40.00, 1, '战略发展部拟下发至教务处', NOW(), NOW(), '定量', 0, false, 35, 44, 'DRAFT', 223),
    (2006, 41003, NULL, '完善课程建设与专业评估机制', 30.00, 2, '战略发展部拟下发至教务处', NOW(), NOW(), '定性', 0, false, 35, 44, 'DRAFT', 223),
    (2041, 41003, NULL, '开展通识教育课程体系改革', 30.00, 3, '战略发展部拟下发至教务处', NOW(), NOW(), '定性', 0, false, 35, 44, 'DRAFT', 223),
    (2042, 41022, NULL, '牵头组织全国大学生学科竞赛报名及指导', 100.00, 1, '战略发展部拟下发至教务处', NOW(), NOW(), '定量', 0, false, 35, 44, 'DRAFT', 223),

    -- ========== 纪委办公室 (org_id=37, task_id=41005) — 新增 ==========
    (2007, 41005, NULL, '完成年度监督执纪专项检查全覆盖', 55.00, 1, '战略发展部下发至纪委办公室', NOW(), NOW(), '定量', 0, false, 35, 37, 'DRAFT', 301),
    (2008, 41005, NULL, '建立廉政风险防控动态台账体系', 45.00, 2, '战略发展部下发至纪委办公室', NOW(), NOW(), '定性', 0, false, 35, 37, 'DRAFT', 301),

    -- ========== 党委宣传部 (org_id=38, task_id=41006) — 新增 ==========
    (2009, 41006, NULL, '完成校园品牌形象提升与对外宣传方案', 50.00, 1, '战略发展部下发至党委宣传部', NOW(), NOW(), '定量', 0, false, 35, 38, 'DRAFT', 305),
    (2010, 41006, NULL, '推进新媒体矩阵建设与舆情监测机制', 50.00, 2, '战略发展部下发至党委宣传部', NOW(), NOW(), '定性', 0, false, 35, 38, 'DRAFT', 305),

    -- ========== 党委组织部 (org_id=39, task_id=41007) — 新增 ==========
    (2011, 41007, NULL, '完成干部教育培训年度计划执行', 55.00, 1, '战略发展部下发至党委组织部', NOW(), NOW(), '定量', 0, false, 35, 39, 'DRAFT', 309),
    (2012, 41007, NULL, '推进基层党组织标准化建设达标', 45.00, 2, '战略发展部下发至党委组织部', NOW(), NOW(), '定性', 0, false, 35, 39, 'DRAFT', 309),

    -- ========== 人力资源部 (org_id=40, task_id=41008) — 新增 ==========
    (2013, 41008, NULL, '完成师资队伍结构与优化年度目标', 60.00, 1, '战略发展部下发至人力资源部', NOW(), NOW(), '定量', 0, false, 35, 40, 'DRAFT', 313),
    (2014, 41008, NULL, '推进绩效薪酬制度改革方案落地', 40.00, 2, '战略发展部下发至人力资源部', NOW(), NOW(), '定性', 0, false, 35, 40, 'DRAFT', 313),

    -- ========== 党委学工部 (org_id=41, task_id=41009) — 新增 ==========
    (2015, 41009, NULL, '完成学生发展支持服务体系年度建设', 55.00, 1, '战略发展部下发至党委学工部', NOW(), NOW(), '定量', 0, false, 35, 41, 'DRAFT', 317),
    (2016, 41009, NULL, '推进心理健康教育与危机干预机制建设', 45.00, 2, '战略发展部下发至党委学工部', NOW(), NOW(), '定性', 0, false, 35, 41, 'DRAFT', 317),

    -- ========== 学校综合办公室 (org_id=43, task_id=41010) — 新增 ==========
    (2017, 41010, NULL, '完成学校治理效能提升年度指标', 50.00, 1, '战略发展部下发至学校综合办公室', NOW(), NOW(), '定量', 0, false, 35, 43, 'DRAFT', 322),
    (2018, 41010, NULL, '建立公文运转与督办闭环管理机制', 50.00, 2, '战略发展部下发至学校综合办公室', NOW(), NOW(), '定性', 0, false, 35, 43, 'DRAFT', 322),

    -- ========== 科技处 (org_id=45, task_id=41011) — 新增 ==========
    (2019, 41011, NULL, '完成科研创新促进年度目标申报量', 55.00, 1, '战略发展部下发至科技处', NOW(), NOW(), '定量', 0, false, 35, 45, 'DRAFT', 327),
    (2020, 41011, NULL, '推进产学研合作平台建设与成果转化', 45.00, 2, '战略发展部下发至科技处', NOW(), NOW(), '定性', 0, false, 35, 45, 'DRAFT', 327),

    -- ========== 财务部 (org_id=46, task_id=41012) — 新增 ==========
    (2021, 41012, NULL, '完成预算绩效管理年度目标执行率', 60.00, 1, '战略发展部下发至财务部', NOW(), NOW(), '定量', 0, false, 35, 46, 'DRAFT', 331),
    (2022, 41012, NULL, '推进财务内控制度建设与审计整改', 40.00, 2, '战略发展部下发至财务部', NOW(), NOW(), '定性', 0, false, 35, 46, 'DRAFT', 331),

    -- ========== 招生工作处 (org_id=47, task_id=41013) — 新增 ==========
    (2023, 41013, NULL, '完成招生质量提升年度目标达成率', 50.00, 1, '战略发展部下发至招生工作处', NOW(), NOW(), '定量', 0, false, 35, 47, 'DRAFT', 335),
    (2024, 41013, NULL, '优化招生宣传渠道与生源质量分析', 50.00, 2, '战略发展部下发至招生工作处', NOW(), NOW(), '定性', 0, false, 35, 47, 'DRAFT', 335),

    -- ========== 就业创业指导中心 (org_id=48, task_id=41014) — 新增 ==========
    (2025, 41014, NULL, '完成毕业生就业率年度目标', 55.00, 1, '战略发展部下发至就业创业指导中心', NOW(), NOW(), '定量', 0, false, 35, 48, 'DRAFT', 339),
    (2026, 41014, NULL, '推进创新创业教育与实践基地建设', 45.00, 2, '战略发展部下发至就业创业指导中心', NOW(), NOW(), '定性', 0, false, 35, 48, 'DRAFT', 339),

    -- ========== 实验室建设管理处 (org_id=49, task_id=41015) — 新增 ==========
    (2027, 41015, NULL, '完成实验室安全建设年度检查达标率', 60.00, 1, '战略发展部下发至实验室建设管理处', NOW(), NOW(), '定量', 0, false, 35, 49, 'DRAFT', 343),
    (2028, 41015, NULL, '推进大型仪器设备共享平台建设', 40.00, 2, '战略发展部下发至实验室建设管理处', NOW(), NOW(), '定性', 0, false, 35, 49, 'DRAFT', 343),

    -- ========== 数字校园建设办公室 (org_id=50, task_id=41016) — 新增 ==========
    (2029, 41016, NULL, '完成数字校园建设年度重点任务推进', 55.00, 1, '战略发展部下发至数字校园建设办公室', NOW(), NOW(), '定量', 0, false, 35, 50, 'DRAFT', 347),
    (2030, 41016, NULL, '推进数据治理与信息系统整合', 45.00, 2, '战略发展部下发至数字校园建设办公室', NOW(), NOW(), '定性', 0, false, 35, 50, 'DRAFT', 347),

    -- ========== 图书馆/档案馆 (org_id=51, task_id=41017) — 新增 ==========
    (2031, 41017, NULL, '完成文献档案服务提升年度目标', 50.00, 1, '战略发展部下发至图书馆档案馆', NOW(), NOW(), '定量', 0, false, 35, 51, 'DRAFT', 351),
    (2032, 41017, NULL, '推进数字资源建设与阅读推广活动', 50.00, 2, '战略发展部下发至图书馆档案馆', NOW(), NOW(), '定性', 0, false, 35, 51, 'DRAFT', 351),

    -- ========== 后勤资产处 (org_id=52, task_id=41018) — 新增 ==========
    (2033, 41018, NULL, '完成后勤保障服务满意度年度目标', 55.00, 1, '战略发展部下发至后勤资产处', NOW(), NOW(), '定量', 0, false, 35, 52, 'DRAFT', 355),
    (2034, 41018, NULL, '推进绿色校园与节能降耗专项工作', 45.00, 2, '战略发展部下发至后勤资产处', NOW(), NOW(), '定性', 0, false, 35, 52, 'DRAFT', 355),

    -- ========== 继续教育部 (org_id=53, task_id=41019) — 新增 ==========
    (2035, 41019, NULL, '完成继续教育项目优化与招生目标', 50.00, 1, '战略发展部下发至继续教育部', NOW(), NOW(), '定量', 0, false, 35, 53, 'DRAFT', 359),
    (2036, 41019, NULL, '推进非学历教育培训体系与品牌建设', 50.00, 2, '战略发展部下发至继续教育部', NOW(), NOW(), '定性', 0, false, 35, 53, 'DRAFT', 359),

    -- ========== 国际合作与交流处 (org_id=54, task_id=41020) — 新增 ==========
    (2037, 41020, NULL, '完成国际交流拓展年度合作项目数', 55.00, 1, '战略发展部下发至国际合作与交流处', NOW(), NOW(), '定量', 0, false, 35, 54, 'DRAFT', 363),
    (2038, 41020, NULL, '推进留学生培养质量与中外合作办学', 45.00, 2, '战略发展部下发至国际合作与交流处', NOW(), NOW(), '定性', 0, false, 35, 54, 'DRAFT', 363)

ON CONFLICT (id) DO UPDATE
SET
    task_id = EXCLUDED.task_id,
    parent_indicator_id = EXCLUDED.parent_indicator_id,
    indicator_desc = EXCLUDED.indicator_desc,
    weight_percent = EXCLUDED.weight_percent,
    sort_order = EXCLUDED.sort_order,
    remark = EXCLUDED.remark,
    updated_at = EXCLUDED.updated_at,
    type = EXCLUDED.type,
    progress = EXCLUDED.progress,
    is_deleted = EXCLUDED.is_deleted,
    owner_org_id = EXCLUDED.owner_org_id,
    target_org_id = EXCLUDED.target_org_id,
    status = EXCLUDED.status,
    responsible_user_id = EXCLUDED.responsible_user_id;

COMMIT;
