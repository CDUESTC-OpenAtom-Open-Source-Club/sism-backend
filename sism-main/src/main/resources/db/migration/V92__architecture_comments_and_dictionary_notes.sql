-- =====================================================
-- V92: 架构债务批次一（issue #81 · 批1-①⑤）
-- 1) audit_flow_def 表/列注释补全（原表注释为陈旧变更记录，
--    未描述当前用途；全部列注释为空）
-- 2) warn_level 五档字典标注「旧预警体系」（仅 remark 文案，
--    不动 level_code/数据行；新业务一律使用进度等级三档 AHEAD/NORMAL/DELAYED）
-- 幂等性：COMMENT 可重复执行；UPDATE 带条件防重复追加
-- =====================================================

COMMENT ON TABLE public.audit_flow_def IS
    '审批流程定义表：定义各业务审批链（flow_code 区分）的节点序列与终审位。节点实例见 audit_step_instance；与 audit_step_def 通过 step_def_id 关联。';

COMMENT ON COLUMN public.audit_flow_def.id IS '流程定义主键';
COMMENT ON COLUMN public.audit_flow_def.flow_code IS '业务流程编码：PLAN_DISPATCH_STRATEGY/PLAN_APPROVAL_FUNCDEPT/PLAN_APPROVAL_COLLEGE/PLAN_MUTATION_STRATEGY 等，审批中心按此路由';
COMMENT ON COLUMN public.audit_flow_def.flow_name IS '流程显示名称';
COMMENT ON COLUMN public.audit_flow_def.is_enabled IS '是否启用：false=停用（历史流程保留定义，不再发起新实例）';
COMMENT ON COLUMN public.audit_flow_def.created_at IS '创建时间';
COMMENT ON COLUMN public.audit_flow_def.updated_at IS '最近修改时间';
COMMENT ON COLUMN public.audit_flow_def.description IS '流程用途说明';
COMMENT ON COLUMN public.audit_flow_def.version IS '定义版本号（节点调整时递增）';

UPDATE public.warn_level SET remark = remark || '（旧预警体系档位，已由进度等级三档 AHEAD/NORMAL/DELAYED 替代，仅历史数据兼容保留）'
WHERE level_code IN ('INFO','WARN','MAJOR','CRITICAL') AND NOT (remark LIKE '%进度等级三档%');
