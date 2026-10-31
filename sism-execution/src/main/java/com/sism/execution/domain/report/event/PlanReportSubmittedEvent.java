package com.sism.execution.domain.report.event;

import com.sism.shared.domain.model.base.DomainEvent;
import lombok.Getter;

/**
 * PlanReportSubmittedEvent - 计划报告提交事件
 */
@Getter
public class PlanReportSubmittedEvent implements DomainEvent {

    private final Long reportId;
    private final String reportMonth;
    private final Long reportOrgId;
    private final Long submitterId;
    /**
     * 2026-10-31 修复（重报说明被丢弃，图05）：提交人（驳回后重报场景）填写的
     * 「重报说明」，随事件透传进工作流启动/复活链路，写入提交步备注。
     * 可空——为空时下游保留「提交人重新提交（未填写说明）」兜底文案。
     */
    private final String submitComment;

    public PlanReportSubmittedEvent(Long reportId, String reportMonth, Long reportOrgId, Long submitterId) {
        this(reportId, reportMonth, reportOrgId, submitterId, null);
    }

    public PlanReportSubmittedEvent(Long reportId, String reportMonth, Long reportOrgId,
                                    Long submitterId, String submitComment) {
        this.reportId = reportId;
        this.reportMonth = reportMonth;
        this.reportOrgId = reportOrgId;
        this.submitterId = submitterId;
        this.submitComment = submitComment == null || submitComment.isBlank() ? null : submitComment.trim();
    }

    @Override
    public String getEventType() {
        return "PlanReportSubmittedEvent";
    }
}
