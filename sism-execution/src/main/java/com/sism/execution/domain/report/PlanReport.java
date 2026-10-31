package com.sism.execution.domain.report;

import com.sism.execution.domain.report.event.PlanReportSubmittedEvent;
import com.sism.execution.domain.report.event.PlanReportApprovedEvent;
import com.sism.execution.domain.report.event.PlanReportRejectedEvent;
import com.sism.shared.domain.model.base.AggregateRoot;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PlanReport - 月度进展报告聚合根
 * 表示指标执行过程中的月度报告
 */
@Getter
@Setter
@Entity
@Table(name = "plan_report")
@Access(AccessType.FIELD)
public class PlanReport extends AggregateRoot<Long> {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_SUBMITTED_LEGACY = "IN_REVIEW";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    @Id
    @SequenceGenerator(name = "PlanReport_IdSeq", sequenceName = "plan_report_id_seq", allocationSize = 1)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "PlanReport_IdSeq")
    @Column(name = "id")
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "report_month", nullable = false)
    private String reportMonth;

    @Column(name = "report_org_id", nullable = false)
    private Long reportOrgId;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_org_type", nullable = false)
    private ReportOrgType reportOrgType;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Convert(converter = PlanReportStatusConverter.class)
    @Column(name = "status", length = 20, nullable = false)
    private PlanReportStatus status = PlanReportStatus.DRAFT;

    @Column(name = "audit_instance_id")
    private Long auditInstanceId;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "progress")
    private Integer progress;

    @Column(name = "issues", columnDefinition = "TEXT")
    private String issues;

    @Column(name = "next_plan", columnDefinition = "TEXT")
    private String nextPlan;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Transient
    private List<PlanReportIndicatorSnapshot> indicatorDetails = List.of();

    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted = false;

    /**
     * 创建月度报告（草稿状态）
     * @param reportMonth 报告月份 (格式: yyyy-MM)
     * @param reportOrgId 报告组织ID
     * @param reportOrgType 报告组织类型 (FUNC_DEPT 或 COLLEGE)
     * @param planId 计划ID
     */
    public static PlanReport createDraft(String reportMonth, Long reportOrgId,
                                          ReportOrgType reportOrgType, Long planId) {
        return createDraft(reportMonth, reportOrgId, reportOrgType, planId, null);
    }

    public static PlanReport createDraft(String reportMonth, Long reportOrgId,
                                         ReportOrgType reportOrgType, Long planId, Long createdBy) {
        String normalizedMonth = normalizeReportMonth(reportMonth);
        if (normalizedMonth == null) {
            throw new IllegalArgumentException("Report month cannot be null or empty");
        }
        if (reportOrgId == null) {
            throw new IllegalArgumentException("Report organization ID cannot be null");
        }
        if (reportOrgType == null) {
            throw new IllegalArgumentException("Report organization type cannot be null");
        }
        if (planId == null) {
            throw new IllegalArgumentException("Plan ID is required");
        }

        PlanReport report = new PlanReport();
        report.reportMonth = normalizedMonth;
        report.reportOrgId = reportOrgId;
        report.reportOrgType = reportOrgType;
        report.planId = planId;
        report.status = PlanReportStatus.DRAFT;
        report.isDeleted = false;
        report.createdBy = createdBy;
        return report;
    }

    /**
     * 提交报告
     */
    public void submit(Long userId) {
        submit(userId, null);
    }

    /**
     * 提交报告（携带重报说明）。2026-10-31 修复（图05）：驳回后重报时前端收集的
     * 「重报说明」随事件透传进工作流链路写入提交步备注；说明为空时行为与改前一致。
     */
    public void submit(Long userId, String submitComment) {
        if (!isDraft()) {
            throw new IllegalStateException("Cannot submit report: not in DRAFT status");
        }
        this.status = PlanReportStatus.SUBMITTED;
        this.submittedBy = userId;
        this.approvedBy = null;
        this.approvedAt = null;
        this.rejectionReason = null;
        this.submittedAt = LocalDateTime.now();
        setUpdatedAt(LocalDateTime.now());
        addEvent(new PlanReportSubmittedEvent(this.id, this.reportMonth, this.reportOrgId, userId, submitComment));
    }

    /**
     * 审批通过
     */
    public void approve(Long userId) {
        if (!isSubmitted()) {
            throw new IllegalStateException("Cannot approve report: not in SUBMITTED status");
        }
        this.status = PlanReportStatus.APPROVED;
        this.approvedBy = userId;
        this.approvedAt = LocalDateTime.now();
        this.rejectionReason = null;
        setUpdatedAt(LocalDateTime.now());
        addEvent(new PlanReportApprovedEvent(this.id, this.reportMonth, this.reportOrgId, userId));
    }

    /**
     * 审批驳回
     */
    public void reject(Long userId, String reason) {
        if (!isSubmitted()) {
            throw new IllegalStateException("Cannot reject report: not in SUBMITTED status");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Rejection reason cannot be null or empty");
        }
        this.status = PlanReportStatus.REJECTED;
        this.approvedBy = userId;
        this.approvedAt = null;
        this.rejectionReason = reason;
        setUpdatedAt(LocalDateTime.now());
        addEvent(new PlanReportRejectedEvent(this.id, this.reportMonth, this.reportOrgId, userId, reason));
    }

    /**
     * 更新报告内容
     * Note: content, summary, progress, issues, next_plan are not stored in DB
     * This method only updates the timestamp for now
     */
    public void updateContent(String content, String summary, Integer progress,
                              String issues, String nextPlan) {
        if (!isDraft()) {
            throw new IllegalStateException("Cannot update report: not in DRAFT status");
        }
        this.content = content;
        this.summary = summary;
        this.progress = progress;
        this.issues = issues;
        this.nextPlan = nextPlan;
        setUpdatedAt(LocalDateTime.now());
    }

    public void markCreatedByIfAbsent(Long userId) {
        if (this.createdBy == null && userId != null && userId > 0) {
            this.createdBy = userId;
        }
    }

    /**
     * 判断是否已提交
     */
    public boolean isSubmitted() {
        return this.status == PlanReportStatus.SUBMITTED;
    }

    /**
     * 判断是否已审批通过
     */
    public boolean isApproved() {
        return this.status == PlanReportStatus.APPROVED;
    }

    public void setDeleted(boolean deleted) {
        this.isDeleted = deleted;
    }

    /**
     * 判断是否被驳回
     */
    public boolean isRejected() {
        return this.status == PlanReportStatus.REJECTED;
    }

    public boolean isDraft() {
        return this.status == null || this.status == PlanReportStatus.DRAFT;
    }

    public String getStatus() {
        return status == null ? null : status.name();
    }

    public void setStatus(String status) {
        this.status = PlanReportStatus.from(status);
    }

    public void setStatus(PlanReportStatus status) {
        this.status = status;
    }

    public PlanReportStatus getStatusEnum() {
        return status;
    }

    @Override
    public void validate() {
        if (normalizeReportMonth(reportMonth) == null) {
            throw new IllegalArgumentException("Report month is required");
        }
        if (reportOrgId == null) {
            throw new IllegalArgumentException("Report organization ID is required");
        }
        if (reportOrgType == null) {
            throw new IllegalArgumentException("Report organization type is required");
        }
        if (planId == null) {
            throw new IllegalArgumentException("Plan ID is required");
        }
    }

    @Override
    public boolean canPublish() {
        return STATUS_APPROVED.equals(status);
    }

    @PrePersist
    protected void onCreate() {
        if (status == null) {
            status = PlanReportStatus.DRAFT;
        }
        if (getCreatedAt() == null) {
            setCreatedAt(LocalDateTime.now());
        }
        if (getUpdatedAt() == null) {
            setUpdatedAt(LocalDateTime.now());
        }
    }

    @PreUpdate
    protected void onUpdate() {
        setUpdatedAt(LocalDateTime.now());
    }

    public static String normalizeReportMonth(String reportMonth) {
        if (reportMonth == null) {
            return null;
        }

        String trimmed = reportMonth.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.matches("\\d{6}")) {
            return trimmed;
        }

        if (trimmed.matches("\\d{4}-\\d{2}")) {
            return trimmed.substring(0, 4) + trimmed.substring(5, 7);
        }

        return null;
    }

    public void resetToDraft(Long createdBy) {
        this.status = PlanReportStatus.DRAFT;
        this.submittedAt = null;
        this.submittedBy = null;
        this.approvedBy = null;
        this.approvedAt = null;
        this.rejectionReason = null;
        this.createdBy = createdBy != null ? createdBy : this.createdBy;
    }
}
