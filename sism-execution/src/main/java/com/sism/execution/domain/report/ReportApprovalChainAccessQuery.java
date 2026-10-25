package com.sism.execution.domain.report;

/**
 * 报告审批链可见性查询。
 *
 * 2026-10-24（上报内容可查阅修复）：报告详情此前按 reportOrgId 收权，
 * 审批方（上级部门/学院内部审批人）打开审批详情时拿不到报告快照，
 * 内容区渲染为空——历史上报无法查阅、归档失去意义。
 * 本网关回答「该组织是否在这份报告的审批链上」。
 */
public interface ReportApprovalChainAccessQuery {

    /**
     * 指定组织是否参与了该报告的任一审批实例
     * （作为审批链定义席位：requester_org_id 或任一步骤 approver_org_id）。
     */
    boolean isOrgOnApprovalChain(Long reportId, Long orgId);
}
