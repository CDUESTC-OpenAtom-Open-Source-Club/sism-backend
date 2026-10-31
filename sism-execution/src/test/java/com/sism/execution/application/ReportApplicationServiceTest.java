package com.sism.execution.application;

import com.sism.exception.ConflictException;
import com.sism.exception.ResourceNotFoundException;
import com.sism.execution.domain.report.PlanReport;
import com.sism.execution.domain.report.PlanReportIndicatorRepository;
import com.sism.execution.domain.report.PlanReportIndicatorSnapshot;
import com.sism.execution.domain.report.PlanReportRepository;
import com.sism.execution.domain.report.ReportOrgType;
import com.sism.execution.domain.report.WorkflowApprovalMetadata;
import com.sism.execution.domain.report.WorkflowApprovalMetadataQuery;
import com.sism.execution.domain.report.WorkflowAuditSyncGateway;
import com.sism.execution.interfaces.dto.PlanReportQueryRequest;
import com.sism.execution.interfaces.dto.UpdatePlanReportIndicatorDetailRequest;
import com.sism.shared.domain.exception.TechnicalException;
import com.sism.shared.infrastructure.event.DomainEventPublisher;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.repository.IndicatorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class ReportApplicationServiceTest {

    @Mock
    private PlanReportRepository planReportRepository;

    @Mock
    private PlanReportIndicatorRepository planReportIndicatorRepository;

    @Mock
    private IndicatorRepository indicatorRepository;

    @Mock
    private DomainEventPublisher eventPublisher;

    @Mock
    private WorkflowAuditSyncGateway workflowAuditSyncGateway;

    @Mock
    private WorkflowApprovalMetadataQuery workflowApprovalMetadataQuery;

    @Mock
    private com.sism.shared.domain.workflow.WorkflowBusinessContextPort workflowBusinessContextPort;

    private ReportApplicationService reportApplicationService;

    @BeforeEach
    void setUp() {
        reportApplicationService = newService(java.util.List.of());
    }

    private ReportApplicationService newService(List<com.sism.shared.domain.workflow.WorkflowBusinessContextPort> ports) {
        return new ReportApplicationService(
                planReportRepository,
                planReportIndicatorRepository,
                indicatorRepository,
                eventPublisher,
                workflowApprovalMetadataQuery,
                workflowAuditSyncGateway,
                null,
                ports
        );
    }

    @Test
    void createReport_restoresLatestDeletedReportInMonthlyScope() {
        PlanReport deletedReport = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        deletedReport.setId(6L);
        deletedReport.setDeleted(true);
        deletedReport.setStatus(PlanReport.STATUS_REJECTED);

        when(planReportRepository.findLatestByMonthlyScope(111L, "202603", ReportOrgType.FUNC_DEPT, 39L))
                .thenReturn(Optional.of(deletedReport));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PlanReport restored = reportApplicationService.createReport("202603", 39L, ReportOrgType.FUNC_DEPT, 111L, 501L);

        assertThat(restored.getId()).isEqualTo(6L);
        assertThat(restored.isDeleted()).isFalse();
        assertThat(restored.getStatus()).isEqualTo(PlanReport.STATUS_DRAFT);
        assertThat(restored.getSubmittedAt()).isNull();
        assertThat(restored.getCreatedBy()).isEqualTo(501L);
        verify(planReportRepository).save(deletedReport);
    }

    @Test
    void createReport_reusesLatestDraftReportInMonthlyScope() {
        PlanReport activeReport = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        activeReport.setId(7L);
        activeReport.setDeleted(false);

        when(planReportRepository.findLatestByMonthlyScope(111L, "202603", ReportOrgType.FUNC_DEPT, 39L))
                .thenReturn(Optional.of(activeReport));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PlanReport reused = reportApplicationService.createReport("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);

        assertThat(reused.getId()).isEqualTo(7L);
        verify(planReportRepository).save(activeReport);
    }

    @Test
    void createReport_shouldPersistFirstFiller() {
        when(planReportRepository.findLatestByMonthlyScope(111L, "202603", ReportOrgType.FUNC_DEPT, 39L))
                .thenReturn(Optional.empty());
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PlanReport created = reportApplicationService.createReport("202603", 39L, ReportOrgType.FUNC_DEPT, 111L, 7001L);

        assertThat(created.getCreatedBy()).isEqualTo(7001L);
    }

    @Test
    void createDraft_shouldRejectMissingPlanId() {
        assertThatThrownBy(() -> PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Plan ID is required");
    }

    @Test
    void findAllActiveReports_shouldCapPageSize() {
        var pageable = PageRequest.of(1, 100, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(planReportRepository.findAllActive(pageable)).thenReturn(Page.empty(pageable));

        reportApplicationService.findAllActiveReports(2, 250);

        verify(planReportRepository).findAllActive(pageable);
    }

    @Test
    void findReportsByConditions_shouldCapPageSize() {
        var pageable = PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(planReportRepository.findByConditions(null, null, null, null, (String) null, pageable))
                .thenReturn(Page.empty(pageable));

        PlanReportQueryRequest queryRequest = new PlanReportQueryRequest();
        queryRequest.setSize(500);

        reportApplicationService.findReportsByConditions(queryRequest);

        verify(planReportRepository).findByConditions(null, null, null, null, (String) null, pageable);
    }

    @Test
    void createReport_createsNewRoundWhenLatestMonthlyReportIsApproved() {
        PlanReport approvedReport = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        approvedReport.setId(17L);
        approvedReport.setStatus(PlanReport.STATUS_APPROVED);

        when(planReportRepository.findLatestByMonthlyScope(111L, "202603", ReportOrgType.FUNC_DEPT, 39L))
                .thenReturn(Optional.of(approvedReport));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> {
            PlanReport report = invocation.getArgument(0);
            report.setId(27L);
            return report;
        });

        PlanReport created = reportApplicationService.createReport("202603", 39L, ReportOrgType.FUNC_DEPT, 111L, 7002L);

        assertThat(created.getId()).isEqualTo(27L);
        assertThat(created.getStatus()).isEqualTo(PlanReport.STATUS_DRAFT);
        assertThat(created.getCreatedBy()).isEqualTo(7002L);
        assertThat(created).isNotSameAs(approvedReport);
        verify(planReportRepository).save(any(PlanReport.class));
    }

    @Test
    void createReport_reusesRejectedRoundInsteadOfCreatingNewOne() {
        PlanReport rejectedReport = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        rejectedReport.setId(19L);
        rejectedReport.setStatus(PlanReport.STATUS_REJECTED);
        rejectedReport.setAuditInstanceId(919L);
        rejectedReport.setSubmittedAt(java.time.LocalDateTime.now());

        when(planReportRepository.findLatestByMonthlyScope(111L, "202603", ReportOrgType.FUNC_DEPT, 39L))
                .thenReturn(Optional.of(rejectedReport));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PlanReport created = reportApplicationService.createReport("202603", 39L, ReportOrgType.FUNC_DEPT, 111L, 7003L);

        assertThat(created.getId()).isEqualTo(19L);
        assertThat(created.getStatus()).isEqualTo(PlanReport.STATUS_DRAFT);
        assertThat(created.getAuditInstanceId()).isEqualTo(919L);
        assertThat(created.getSubmittedAt()).isNull();
        verify(planReportRepository).save(rejectedReport);
    }

    @Test
    void createReport_rejectsNewRoundWhenLatestMonthlyReportIsInReview() {
        PlanReport inReviewReport = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        inReviewReport.setId(18L);
        inReviewReport.setStatus(PlanReport.STATUS_SUBMITTED);

        when(planReportRepository.findLatestByMonthlyScope(111L, "202603", ReportOrgType.FUNC_DEPT, 39L))
                .thenReturn(Optional.of(inReviewReport));

        assertThatThrownBy(() -> reportApplicationService.createReport("202603", 39L, ReportOrgType.FUNC_DEPT, 111L))
                .isInstanceOf(ConflictException.class)
                .hasMessage("当前月份已有报告正在审批中，请等待审批完成或先撤回");
    }

    @Test
    void createReport_allowsNextMonthWhenEarlierMonthIsApproved() {
        // 防跳月守卫（缺陷修复）：202601 已批准（已结清）时，创建 202602 应放行，
        // 否则已批准月份永远占据「最早未填报月」，月度上报无法推进。
        PlanReport approvedJanuary = PlanReport.createDraft("202601", 39L, ReportOrgType.FUNC_DEPT, 111L);
        approvedJanuary.setId(31L);
        approvedJanuary.setStatus(PlanReport.STATUS_APPROVED);

        when(planReportRepository.findByReportOrgId(39L)).thenReturn(List.of(approvedJanuary));
        when(planReportRepository.findLatestByMonthlyScope(111L, "202602", ReportOrgType.FUNC_DEPT, 39L))
                .thenReturn(Optional.empty());
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PlanReport created = reportApplicationService.createReport("202602", 39L, ReportOrgType.FUNC_DEPT, 111L);

        assertThat(created.getReportMonth()).isEqualTo("202602");
        assertThat(created.getStatus()).isEqualTo(PlanReport.STATUS_DRAFT);
    }

    @Test
    void createReport_stillBlocksNextMonthWhenEarlierMonthOnlyHasUnsettledReport() {
        // 202601 只有 DRAFT（未结清）时，创建 202602 仍应 409（必须先补最早的欠账）
        PlanReport draftJanuary = PlanReport.createDraft("202601", 39L, ReportOrgType.FUNC_DEPT, 111L);
        draftJanuary.setId(32L);
        draftJanuary.setStatus(PlanReport.STATUS_DRAFT);

        when(planReportRepository.findByReportOrgId(39L)).thenReturn(List.of(draftJanuary));

        assertThatThrownBy(() -> reportApplicationService.createReport("202602", 39L, ReportOrgType.FUNC_DEPT, 111L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("存在更早的未填报月份 202601");
    }

    @Test
    void createReport_stillBlocksEarlierMonthWhenLaterMonthIsApproved() {
        // 已批准月之后的未结清月（SUBMITTED）仍算欠账：创建更晚月份应被拦截
        PlanReport approvedJanuary = PlanReport.createDraft("202601", 39L, ReportOrgType.FUNC_DEPT, 111L);
        approvedJanuary.setId(33L);
        approvedJanuary.setStatus(PlanReport.STATUS_APPROVED);
        PlanReport submittedFebruary = PlanReport.createDraft("202602", 39L, ReportOrgType.FUNC_DEPT, 111L);
        submittedFebruary.setId(34L);
        submittedFebruary.setStatus(PlanReport.STATUS_SUBMITTED);

        when(planReportRepository.findByReportOrgId(39L)).thenReturn(List.of(approvedJanuary, submittedFebruary));

        assertThatThrownBy(() -> reportApplicationService.createReport("202603", 39L, ReportOrgType.FUNC_DEPT, 111L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("存在更早的未填报月份 202602");
    }

    @Test
    void submitReport_shouldThrowResourceNotFoundWhenReportMissing() {
        when(planReportRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportApplicationService.submitReport(404L, 7001L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Report not found with id: 404");
    }

    @Test
    void attachAuditInstance_shouldPersistRuntimeLink() {
        PlanReport report = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(8L);

        when(planReportRepository.findById(8L)).thenReturn(Optional.of(report));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PlanReport updated = reportApplicationService.attachAuditInstance(8L, 901L);

        assertThat(updated.getAuditInstanceId()).isEqualTo(901L);
        verify(planReportRepository).save(report);
    }

    @Test
    void markWorkflowRejected_shouldUpdateStatusWithoutPublishingEvents() {
        PlanReport report = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(9L);
        report.setStatus(PlanReport.STATUS_SUBMITTED);

        when(planReportRepository.findById(9L)).thenReturn(Optional.of(report));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PlanReport updated = reportApplicationService.markWorkflowRejected(9L, 33L, "退回修改");

        assertThat(updated.getStatus()).isEqualTo(PlanReport.STATUS_REJECTED);
        assertThat(updated.getRejectionReason()).isEqualTo("退回修改");
        verify(planReportRepository).save(report);
        verify(workflowAuditSyncGateway, never()).markRejected(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void markWorkflowRejected_shouldSyncAuditInstanceWhenLinked() {
        PlanReport report = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(29L);
        report.setStatus(PlanReport.STATUS_SUBMITTED);
        report.setAuditInstanceId(9029L);

        when(planReportRepository.findById(29L)).thenReturn(Optional.of(report));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        reportApplicationService.markWorkflowRejected(29L, 33L, "退回修改");

        verify(workflowAuditSyncGateway).markRejected(9029L);
    }

    @Test
    void markWorkflowApproved_shouldCreateNextDraftRoundWhenLatestRoundWasApproved() {
        PlanReport approvedReport = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L, 8008L);
        approvedReport.setId(21L);
        approvedReport.setStatus(PlanReport.STATUS_SUBMITTED);

        when(planReportRepository.findById(21L)).thenReturn(Optional.of(approvedReport));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(planReportRepository.findLatestByMonthlyScope(111L, "202603", ReportOrgType.FUNC_DEPT, 39L))
                .thenReturn(Optional.of(approvedReport), Optional.of(approvedReport));

        PlanReport updated = reportApplicationService.markWorkflowApproved(21L, 33L);

        assertThat(updated.getStatus()).isEqualTo(PlanReport.STATUS_APPROVED);
        verify(planReportRepository, times(2)).save(any(PlanReport.class));
    }

    @Test
    void markWorkflowApproved_shouldBatchSyncIndicatorProgressWithoutNulls() {
        PlanReport approvedReport = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L, 8008L);
        approvedReport.setId(41L);
        approvedReport.setStatus(PlanReport.STATUS_SUBMITTED);

        Indicator first = new Indicator();
        first.setId(1001L);
        Indicator second = new Indicator();
        second.setId(1002L);

        when(planReportRepository.findById(41L)).thenReturn(Optional.of(approvedReport));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(planReportRepository.findLatestByMonthlyScope(111L, "202603", ReportOrgType.FUNC_DEPT, 39L))
                .thenReturn(Optional.of(approvedReport), Optional.of(approvedReport));
        when(planReportIndicatorRepository.findByReportId(41L)).thenReturn(List.of(
                new PlanReportIndicatorSnapshot(1001L, 80, "content", null, null, null, List.of()),
                new PlanReportIndicatorSnapshot(1002L, null, "content", null, null, null, List.of())
        ));
        when(indicatorRepository.findByIds(List.of(1001L, 1002L))).thenReturn(List.of(first, second));

        reportApplicationService.markWorkflowApproved(41L, 33L);

        verify(indicatorRepository).saveAll(argThat(indicators -> {
            List<Indicator> copied = new ArrayList<>();
            indicators.forEach(copied::add);
            return copied.size() == 1 && copied.get(0).getId().equals(1001L) && copied.get(0).getProgress().equals(80);
        }));
    }

    @Test
    void markWorkflowApproved_shouldSyncAuditInstanceWhenLinked() {
        PlanReport approvedReport = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L, 8008L);
        approvedReport.setId(31L);
        approvedReport.setStatus(PlanReport.STATUS_SUBMITTED);
        approvedReport.setAuditInstanceId(9031L);

        when(planReportRepository.findById(31L)).thenReturn(Optional.of(approvedReport));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(planReportRepository.findLatestByMonthlyScope(111L, "202603", ReportOrgType.FUNC_DEPT, 39L))
                .thenReturn(Optional.of(approvedReport), Optional.of(approvedReport));

        reportApplicationService.markWorkflowApproved(31L, 33L);

        verify(workflowAuditSyncGateway).markApproved(9031L);
    }

    @Test
    void markWorkflowApproved_shouldFailWhenWorkflowAuditSyncFails() {
        PlanReport approvedReport = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L, 8008L);
        approvedReport.setId(33L);
        approvedReport.setStatus(PlanReport.STATUS_SUBMITTED);
        approvedReport.setAuditInstanceId(9033L);

        when(planReportRepository.findById(33L)).thenReturn(Optional.of(approvedReport));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doThrow(new RuntimeException("workflow unavailable"))
                .when(workflowAuditSyncGateway).markApproved(9033L);

        assertThatThrownBy(() -> reportApplicationService.markWorkflowApproved(33L, 33L))
                .isInstanceOf(TechnicalException.class)
                .hasMessageContaining("Failed to sync audit_instance state");

        verify(workflowAuditSyncGateway).markApproved(9033L);
        verify(planReportIndicatorRepository, never()).findByReportId(any());
        verify(planReportIndicatorRepository, never()).findByReportIds(any());
        verify(indicatorRepository, never()).save(any());
    }

    @Test
    void markWorkflowWithdrawn_shouldResetDraftStateAndClearAuditLink() {
        PlanReport report = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(19L);
        report.setStatus(PlanReport.STATUS_SUBMITTED);
        report.setAuditInstanceId(901L);
        report.setSubmittedAt(java.time.LocalDateTime.now());

        when(planReportRepository.findById(19L)).thenReturn(Optional.of(report));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PlanReport updated = reportApplicationService.markWorkflowWithdrawn(19L);

        assertThat(updated.getStatus()).isEqualTo(PlanReport.STATUS_DRAFT);
        assertThat(updated.getAuditInstanceId()).isNull();
        assertThat(updated.getSubmittedAt()).isNull();
    }

    @Test
    void markWorkflowReturnedForResubmission_shouldResetDraftStateAndKeepAuditLink() {
        PlanReport report = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(20L);
        report.setStatus(PlanReport.STATUS_SUBMITTED);
        report.setAuditInstanceId(902L);
        report.setSubmittedAt(java.time.LocalDateTime.now());

        when(planReportRepository.findById(20L)).thenReturn(Optional.of(report));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PlanReport updated = reportApplicationService.markWorkflowReturnedForResubmission(20L, 902L);

        assertThat(updated.getStatus()).isEqualTo(PlanReport.STATUS_DRAFT);
        assertThat(updated.getAuditInstanceId()).isEqualTo(902L);
        assertThat(updated.getSubmittedAt()).isNull();
    }

    @Test
    void updateReport_shouldPersistIndicatorDraftDetailWhenIndicatorIdProvided() {
        PlanReport report = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(12L);
        Indicator indicator = new Indicator();
        indicator.setId(2001L);
        indicator.setProgress(20);

        when(planReportRepository.findById(12L)).thenReturn(Optional.of(report));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(indicatorRepository.findById(2001L)).thenReturn(Optional.of(indicator));

        PlanReport updated = reportApplicationService.updateReport(
                12L,
                "指标 A",
                2001L,
                "本月推进完成",
                "本月推进完成",
                45,
                null,
                "本月推进完成",
                "本月推进完成",
                8001L
        );

        assertThat(updated.getId()).isEqualTo(12L);
        assertThat(updated.getCreatedBy()).isEqualTo(8001L);
        verify(planReportIndicatorRepository)
                .upsertDraftIndicator(12L, 2001L, 45, "本月推进完成", null, "本月推进完成");
    }

    @Test
    void updateReport_shouldRejectProgressNotGreaterThanActualIndicatorProgress() {
        PlanReport report = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(18L);
        Indicator indicator = new Indicator();
        indicator.setId(2008L);
        indicator.setProgress(30);

        when(planReportRepository.findById(18L)).thenReturn(Optional.of(report));
        when(indicatorRepository.findById(2008L)).thenReturn(Optional.of(indicator));

        assertThatThrownBy(() -> reportApplicationService.updateReport(
                18L,
                "指标 B",
                2008L,
                "进度未增长",
                "进度未增长",
                30,
                null,
                "进度未增长",
                "进度未增长",
                9001L
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("填报进度必须大于真实进度，当前真实进度为 30%");

        verify(planReportRepository, never()).save(any(PlanReport.class));
        verify(planReportIndicatorRepository, never())
                .upsertDraftIndicator(any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateReportBatch_shouldPassAttachmentIdsToRepository() {
        PlanReport report = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(22L);

        Indicator indicator = new Indicator();
        indicator.setId(2010L);
        indicator.setProgress(10);

        UpdatePlanReportIndicatorDetailRequest detail = new UpdatePlanReportIndicatorDetailRequest();
        detail.setIndicatorId(2010L);
        detail.setContent("带附件的填报");
        detail.setProgress(45);
        detail.setAttachmentIds(List.of(301L, 302L));

        when(planReportRepository.findById(22L)).thenReturn(Optional.of(report));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(indicatorRepository.findById(2010L)).thenReturn(Optional.of(indicator));
        when(planReportIndicatorRepository.upsertDraftIndicator(22L, 2010L, 45, "带附件的填报", null, "带附件的填报"))
                .thenReturn(7001L);

        PlanReport updated = reportApplicationService.updateReportBatch(
                22L,
                "标题",
                "内容",
                "摘要",
                45,
                "问题",
                "计划",
                8002L,
                List.of(detail)
        );

        assertThat(updated.getId()).isEqualTo(22L);
        assertThat(updated.getCreatedBy()).isEqualTo(8002L);
        verify(planReportIndicatorRepository)
                .attachFiles(7001L, List.of(301L, 302L), 8002L);
    }

    // ==================== 2026-10-30 回归：等级制填报保存必失败 ====================
    //
    // 线上现象：二级学院在「指标填报」页点保存，报「保存指标填报失败」。
    // 根因：后端「填报进度必须大于真实进度」是百分比时代的校验，等级制下
    // progress 恒为 0、indicator.progress 也是 0 → `0 <= 0` 恒成立 → 必 400。

    @Test
    void updateReport_shouldSaveLevelBasedFillEvenWhenProgressIsZero() {
        // 等级制填报：progress 恒为 0（占位），indicator.progress 也是 0。
        // 修复前会在这里抛「填报进度必须大于真实进度，当前真实进度为 0%」。
        PlanReport report = PlanReport.createDraft("202610", 57L, ReportOrgType.COLLEGE, 370L);
        report.setId(70L);
        Indicator indicator = new Indicator();
        indicator.setId(2044L);
        indicator.setProgress(0);

        when(planReportRepository.findById(70L)).thenReturn(Optional.of(report));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(planReportIndicatorRepository.upsertDraftIndicator(
                70L, 2044L, 0, "我觉得是", "AHEAD", "我觉得是"))
                .thenReturn(7001L);

        PlanReport updated = reportApplicationService.updateReport(
                70L,
                "指标 A",
                2044L,
                "我觉得是",
                "我觉得是",
                0,
                "AHEAD",
                "我觉得是",
                "我觉得是",
                370L
        );

        assertThat(updated.getId()).isEqualTo(70L);
        verify(planReportIndicatorRepository)
                .upsertDraftIndicator(70L, 2044L, 0, "我觉得是", "AHEAD", "我觉得是");
        verify(planReportIndicatorRepository).attachFiles(7001L, List.of(), 370L);
    }

    @Test
    void updateReportBatch_shouldSaveWhenOnlySomeItemsCarrySelfRating() {
        // 线上实测的请求形态：批量提交里「非当前编辑条目」没有 selfRating（前端列表接口
        // 不返回该字段），只有当前编辑的那条带等级。修复前，非当前条目会落回百分比校验，
        // 而等级制下 progress 恒为 0 → `0 <= 0` → 整次保存回滚。
        PlanReport report = PlanReport.createDraft("202610", 57L, ReportOrgType.COLLEGE, 370L);
        report.setId(71L);

        UpdatePlanReportIndicatorDetailRequest otherDetail = new UpdatePlanReportIndicatorDetailRequest();
        otherDetail.setIndicatorId(2044L);
        otherDetail.setContent("我觉得是");
        otherDetail.setProgress(0);
        // 故意不设 selfRating —— 复现线上「非当前条目无等级」的真实形态

        UpdatePlanReportIndicatorDetailRequest currentDetail = new UpdatePlanReportIndicatorDetailRequest();
        currentDetail.setIndicatorId(2047L);
        currentDetail.setContent("本月已完成");
        currentDetail.setProgress(0);
        currentDetail.setSelfRating("AHEAD");

        when(planReportRepository.findById(71L)).thenReturn(Optional.of(report));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // 等级制豁免后不会再去读 indicator 的真实进度，故不 stub indicatorRepository
        when(planReportIndicatorRepository.upsertDraftIndicator(71L, 2044L, 0, "我觉得是", null, "我觉得是"))
                .thenReturn(7002L);
        when(planReportIndicatorRepository.upsertDraftIndicator(71L, 2047L, 0, "本月已完成", "AHEAD", "本月已完成"))
                .thenReturn(7003L);

        PlanReport updated = reportApplicationService.updateReportBatch(
                71L,
                "标题",
                "内容",
                "摘要",
                0,
                "问题",
                "计划",
                370L,
                List.of(otherDetail, currentDetail)
        );

        assertThat(updated.getId()).isEqualTo(71L);
        // 两条明细都要落库——修复前第二条会抛异常导致整次回滚
        verify(planReportIndicatorRepository)
                .upsertDraftIndicator(71L, 2044L, 0, "我觉得是", null, "我觉得是");
        verify(planReportIndicatorRepository)
                .upsertDraftIndicator(71L, 2047L, 0, "本月已完成", "AHEAD", "本月已完成");
    }

    @Test
    void updateReportBatch_shouldStillRejectPercentageRegressionWhenNoItemCarriesLevel() {
        // 兜底：真正的历史百分比路径（整批都无等级）必须保留「进度必须大于真实进度」校验，
        // 不能被等级制豁免规则一并放过。
        PlanReport report = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(72L);
        Indicator indicator = new Indicator();
        indicator.setId(2009L);
        indicator.setProgress(30);

        UpdatePlanReportIndicatorDetailRequest detail = new UpdatePlanReportIndicatorDetailRequest();
        detail.setIndicatorId(2009L);
        detail.setContent("百分比回退");
        detail.setProgress(30);

        when(planReportRepository.findById(72L)).thenReturn(Optional.of(report));
        when(indicatorRepository.findById(2009L)).thenReturn(Optional.of(indicator));

        assertThatThrownBy(() -> reportApplicationService.updateReportBatch(
                72L,
                "标题",
                "内容",
                "摘要",
                30,
                "问题",
                "计划",
                9001L,
                List.of(detail)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("填报进度必须大于真实进度，当前真实进度为 30%");
    }

    @Test
    void findReportById_shouldHydrateIndicatorDetailsFromDraftTable() {
        PlanReport report = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(16L);

        when(planReportRepository.findById(16L)).thenReturn(Optional.of(report));
        when(planReportIndicatorRepository.findByReportIds(List.of(16L)))
                .thenReturn(Map.of(16L, List.of(new PlanReportIndicatorSnapshot(2003L, 15, "已填报草稿", null, null, null, List.of()))));

        PlanReport hydrated = reportApplicationService.findReportById(16L).orElseThrow();

        assertThat(hydrated.getIndicatorDetails()).hasSize(1);
        assertThat(hydrated.getIndicatorDetails().get(0).indicatorId()).isEqualTo(2003L);
        assertThat(hydrated.getIndicatorDetails().get(0).progress()).isEqualTo(15);
        assertThat(hydrated.getIndicatorDetails().get(0).comment()).isEqualTo("已填报草稿");
        verify(planReportIndicatorRepository).findByReportIds(List.of(16L));
    }

    @Test
    void markWorkflowApproved_shouldSyncIndicatorProgressFromReportDetails() {
        PlanReport report = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(13L);
        report.setStatus(PlanReport.STATUS_SUBMITTED);

        Indicator indicator = new Indicator();
        indicator.setId(2001L);
        indicator.setProgress(0);

        when(planReportRepository.findById(13L)).thenReturn(Optional.of(report));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(planReportIndicatorRepository.findByReportId(13L))
                .thenReturn(List.of(new PlanReportIndicatorSnapshot(2001L, 67, "审批通过备注", null, null, null, List.of())));
        when(planReportIndicatorRepository.findByReportIds(List.of(13L)))
                .thenReturn(Map.of(13L, List.of(new PlanReportIndicatorSnapshot(2001L, 67, "审批通过备注", null, null, null, List.of()))));
        when(indicatorRepository.findByIds(List.of(2001L))).thenReturn(List.of(indicator));
        when(indicatorRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PlanReport updated = reportApplicationService.markWorkflowApproved(13L, 33L);

        assertThat(updated.getStatus()).isEqualTo(PlanReport.STATUS_APPROVED);
        assertThat(indicator.getProgress()).isEqualTo(67);
        verify(indicatorRepository).saveAll(argThat(indicators -> {
            List<Indicator> copied = new ArrayList<>();
            indicators.forEach(copied::add);
            return copied.size() == 1 && copied.get(0) == indicator;
        }));
        verify(workflowAuditSyncGateway, never()).markApproved(org.mockito.ArgumentMatchers.anyLong());
        verify(planReportIndicatorRepository).findByReportIds(List.of(13L));
    }

    @Test
    void approveReport_shouldSyncIndicatorProgressFromReportDetails() {
        PlanReport report = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(15L);
        report.setStatus(PlanReport.STATUS_SUBMITTED);

        Indicator indicator = new Indicator();
        indicator.setId(2002L);
        indicator.setProgress(0);

        when(planReportRepository.findById(15L)).thenReturn(Optional.of(report));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(planReportIndicatorRepository.findByReportId(15L))
                .thenReturn(List.of(new PlanReportIndicatorSnapshot(2002L, 20, "审批完成", null, null, null, List.of())));
        when(planReportIndicatorRepository.findByReportIds(List.of(15L)))
                .thenReturn(Map.of(15L, List.of(new PlanReportIndicatorSnapshot(2002L, 20, "审批完成", null, null, null, List.of()))));
        when(indicatorRepository.findByIds(List.of(2002L))).thenReturn(List.of(indicator));
        when(indicatorRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PlanReport updated = reportApplicationService.approveReport(15L, 33L);

        assertThat(updated.getStatus()).isEqualTo(PlanReport.STATUS_APPROVED);
        assertThat(indicator.getProgress()).isEqualTo(20);
        verify(indicatorRepository).saveAll(argThat(indicators -> {
            List<Indicator> copied = new ArrayList<>();
            indicators.forEach(copied::add);
            return copied.size() == 1 && copied.get(0) == indicator;
        }));
        verify(planReportIndicatorRepository).findByReportIds(List.of(15L));
    }

    @Test
    void updateReport_shouldNotPersistIndicatorDetailWhenIndicatorIdMissing() {
        PlanReport report = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(14L);

        when(planReportRepository.findById(14L)).thenReturn(Optional.of(report));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        reportApplicationService.updateReport(14L, "纯报表", null, 20, "问题", "计划");

        verify(planReportIndicatorRepository, never())
                .upsertDraftIndicator(any(), any(), any(), any(), any(), any());
    }

    @Test
    void findReportsByStatusPage_shouldUseNormalizedPageable() {
        var pageable = PageRequest.of(2, 15, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(planReportRepository.findByStatus("SUBMITTED", pageable))
                .thenReturn(org.springframework.data.domain.Page.empty(pageable));

        reportApplicationService.findReportsByStatus("SUBMITTED", 3, 15);

        verify(planReportRepository).findByStatus("SUBMITTED", pageable);
    }

    @Test
    void findReportById_shouldHydrateWorkflowMetadataViaQueryGateway() {
        PlanReport report = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(41L);
        report.setAuditInstanceId(9041L);
        report.setCreatedBy(8001L);

        when(planReportRepository.findById(41L)).thenReturn(Optional.of(report));
        when(planReportIndicatorRepository.findByReportIds(List.of(41L))).thenReturn(Map.of(41L, List.of()));
        when(workflowApprovalMetadataQuery.findByAuditInstanceIds(List.of(9041L)))
                .thenReturn(Map.of(9041L, new WorkflowApprovalMetadata(7001L, 9001L, java.time.LocalDateTime.of(2026, 4, 7, 1, 0))));

        PlanReport hydrated = reportApplicationService.findReportById(41L).orElseThrow();

        assertThat(hydrated.getSubmittedBy()).isEqualTo(7001L);
        assertThat(hydrated.getApprovedBy()).isEqualTo(9001L);
        assertThat(hydrated.getApprovedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 4, 7, 1, 0));
        verify(workflowApprovalMetadataQuery).findByAuditInstanceIds(List.of(9041L));
        verify(planReportIndicatorRepository).findByReportIds(List.of(41L));
    }

    @Test
    void findReportById_shouldFallbackToCreatedByWhenWorkflowMetadataHasNoSubmitter() {
        PlanReport report = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(42L);
        report.setAuditInstanceId(9042L);
        report.setCreatedBy(8002L);

        when(planReportRepository.findById(42L)).thenReturn(Optional.of(report));
        when(planReportIndicatorRepository.findByReportIds(List.of(42L))).thenReturn(Map.of(42L, List.of()));
        when(workflowApprovalMetadataQuery.findByAuditInstanceIds(List.of(9042L)))
                .thenReturn(Map.of(9042L, new WorkflowApprovalMetadata(null, null, null)));

        PlanReport hydrated = reportApplicationService.findReportById(42L).orElseThrow();

        assertThat(hydrated.getSubmittedBy()).isEqualTo(8002L);
        assertThat(hydrated.getApprovedBy()).isNull();
        assertThat(hydrated.getApprovedAt()).isNull();
        verify(planReportIndicatorRepository).findByReportIds(List.of(42L));
    }

    @Test
    void findReportsByStatus_shouldBatchHydrateIndicatorDetailsForPageContent() {
        PlanReport first = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        first.setId(51L);
        PlanReport second = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        second.setId(52L);
        var pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));

        when(planReportRepository.findByStatus("SUBMITTED", pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(first, second), pageable, 2));
        when(planReportIndicatorRepository.findByReportIds(List.of(51L, 52L)))
                .thenReturn(Map.of(
                        51L, List.of(new PlanReportIndicatorSnapshot(3001L, 11, "A", null, null, null, List.of())),
                        52L, List.of(new PlanReportIndicatorSnapshot(3002L, 22, "B", null, null, null, List.of()))
                ));

        var page = reportApplicationService.findReportsByStatus("SUBMITTED", 1, 10);

        assertThat(page.getContent()).extracting(PlanReport::getId).containsExactly(51L, 52L);
        assertThat(page.getContent().get(0).getIndicatorDetails()).hasSize(1);
        assertThat(page.getContent().get(1).getIndicatorDetails()).hasSize(1);
        verify(planReportIndicatorRepository).findByReportIds(List.of(51L, 52L));
    }

    @Test
    void createReport_shouldNotBeBlockedByOrgMutationLock() {
        // 2026-10-20 用户定案：异动期间不再锁死填报/导入等模块（人为处理）。
        // 即使组织存在异动中指标，建草稿也应正常进行。
        ReportApplicationService service = newService(List.of(workflowBusinessContextPort));

        assertThatCode(() -> service.createReport("202603", 39L, ReportOrgType.FUNC_DEPT, 111L, 7001L))
                .doesNotThrowAnyException();
    }

    @Test
    void updateReport_shouldNotBeBlockedByOrgMutationLock() {
        // 2026-10-20 定案：异动期间不再锁死填报修改
        PlanReport report = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(60L);
        Indicator indicator = new Indicator();
        indicator.setId(2001L);
        indicator.setProgress(20);
        when(planReportRepository.findById(60L)).thenReturn(Optional.of(report));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(inv -> inv.getArgument(0));
        when(indicatorRepository.findById(2001L)).thenReturn(Optional.of(indicator));
        ReportApplicationService service = newService(List.of(workflowBusinessContextPort));

        assertThatCode(() -> service.updateReport(
                60L, "指标 A", 2001L, "内容", "摘要", 45, null, "问题", "计划", 8001L))
                .doesNotThrowAnyException();
    }

    @Test
    void submitReport_shouldRejectWhenMutationApprovalInFlight() {
        // 2026-10-28 迭代计划 M8-③ 回归：部门存在 IN_REVIEW 的 PLAN_MUTATION_STRATEGY
        // 异动审批时，提交上报必须被互斥校验拦截（上报/异动二选一）。
        PlanReport report = PlanReport.createDraft("202610", 44L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(62L);
        when(planReportRepository.findById(62L)).thenReturn(Optional.of(report));
        // 明细完整（自评+说明齐全），确保拦截点落在互斥校验而非完整性校验
        when(planReportIndicatorRepository.findByReportId(62L)).thenReturn(List.of(
                new PlanReportIndicatorSnapshot(2001L, 50, "内容", "NORMAL", "说明", null, List.of())
        ));
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate =
                org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Integer.class),
                org.mockito.ArgumentMatchers.eq(44L),
                org.mockito.ArgumentMatchers.eq(44L),
                org.mockito.ArgumentMatchers.eq(44L)))
                .thenReturn(1);
        ReportApplicationService service = new ReportApplicationService(
                planReportRepository,
                planReportIndicatorRepository,
                indicatorRepository,
                eventPublisher,
                workflowApprovalMetadataQuery,
                workflowAuditSyncGateway,
                jdbcTemplate,
                java.util.List.of());

        assertThatThrownBy(() -> service.submitReport(62L, 8001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("存在进行中的异动审批");
        verify(planReportRepository, never()).save(any(PlanReport.class));
    }

    // ==================== 2026-10-30 用户定案（门禁前移到提交时，自评口径） ====================

    @Test
    void submitReport_shouldBlockUnfilledSelfRatingWithIndicatorNames() {
        // 门禁前移：填报人提交上报前必须完成全部指标的自评进度评定；未填的按指标名点名拦截
        // （BusinessConflictException 原文透传——不含指标 ID、不出现「资源冲突」前缀）。
        PlanReport report = PlanReport.createDraft("202610", 44L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(64L);
        when(planReportRepository.findById(64L)).thenReturn(Optional.of(report));
        when(planReportIndicatorRepository.findByReportId(64L)).thenReturn(List.of(
                new PlanReportIndicatorSnapshot(2001L, 50, "内容", "NORMAL", "说明", null, List.of()),
                new PlanReportIndicatorSnapshot(2002L, 30, "内容", null, "说明", null, List.of())
        ));
        when(planReportIndicatorRepository.findIndicatorNamesByReport(64L))
                .thenReturn(Map.of(2001L, "教学运行质量", 2002L, "科研产出增长率"));

        assertThatThrownBy(() -> reportApplicationService.submitReport(64L, 8001L))
                .isInstanceOf(com.sism.exception.BusinessConflictException.class)
                .hasMessage("自评未完成：还有 1 项指标的自评进度未填写（『科研产出增长率』）。"
                        + "请先完成全部指标的自评进度评定，再提交上报。");

        // 门禁在启动审批链之前拦下，报告状态与落库都不动
        assertThat(report.getStatus()).isEqualTo(PlanReport.STATUS_DRAFT);
        verify(planReportRepository, never()).save(any(PlanReport.class));
    }

    @Test
    void submitReport_shouldSummarizeManyUnfilledSelfRatingsWithTruncatedNames() {
        // 超 3 项：列前 3 个 + 「等 N 项」；名称超 20 字截断补省略号；名称缺失兜底「未知指标」；
        // 文案只报指标名，不报指标 ID、不出现「资源冲突」字样。
        PlanReport report = PlanReport.createDraft("202610", 44L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(65L);
        when(planReportRepository.findById(65L)).thenReturn(Optional.of(report));
        when(planReportIndicatorRepository.findByReportId(65L)).thenReturn(List.of(
                new PlanReportIndicatorSnapshot(7001L, 10, null, null, null, null, List.of()),
                new PlanReportIndicatorSnapshot(7002L, 20, null, "  ", null, null, List.of()),
                new PlanReportIndicatorSnapshot(7003L, 30, null, null, null, null, List.of()),
                new PlanReportIndicatorSnapshot(7004L, 40, null, null, null, null, List.of())
        ));
        when(planReportIndicatorRepository.findIndicatorNamesByReport(65L)).thenReturn(Map.of(
                7001L, "指导思想贯彻情况落实与督查考核评价机制建设",
                7002L, "教学质量",
                7004L, "科研产出"));

        String message = org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> reportApplicationService.submitReport(65L, 8001L),
                        com.sism.exception.BusinessConflictException.class)
                .getMessage();

        assertThat(message)
                .contains("自评未完成：还有 4 项指标的自评进度未填写")
                .contains("『教学质量』")
                .contains(" 等 4 项")
                .contains("…") // 超长指标名已截断
                .contains("未知指标") // 第 3 项名称缺失的兜底（在点名范围内才会出现在文案）
                .doesNotContain("督查考核评价机制建设」") // 完整长名不出现在文案
                .doesNotContain("7001")
                .doesNotContain("7002")
                .doesNotContain("7003")
                .doesNotContain("7004")
                .doesNotContain("资源冲突");
        verify(planReportRepository, never()).save(any(PlanReport.class));
    }

    @Test
    void submitReport_shouldLetFullySelfRatedReportThroughGate() {
        // 全部指标自评已填 → 提交门禁放行，走原有提交链路（异动互斥 → SUBMITTED → 提交事件）。
        PlanReport report = resubmittableDraft();
        stubSuccessfulSubmit(report);
        ReportApplicationService service = submitServiceWithUnlockOrg();

        assertThatCode(() -> service.submitReport(63L, 8001L)).doesNotThrowAnyException();

        assertThat(report.getStatus()).isEqualTo(PlanReport.STATUS_SUBMITTED);
        verify(planReportRepository).save(report);
    }

    @Test
    void submitReport_shouldStillRequireProgressDescriptionWhenSelfRatingFilled() {
        // 自评门禁放行后，进度说明缺口沿用 2026-10-24 的兜底校验（IllegalStateException）。
        PlanReport report = resubmittableDraft();
        when(planReportRepository.findById(63L)).thenReturn(Optional.of(report));
        when(planReportIndicatorRepository.findByReportId(63L)).thenReturn(List.of(
                new PlanReportIndicatorSnapshot(2001L, 50, null, "NORMAL", null, null, List.of())
        ));

        assertThatThrownBy(() -> reportApplicationService.submitReport(63L, 8001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 条指标未填写进度说明");
        verify(planReportRepository, never()).save(any(PlanReport.class));
    }

    @Test
    void updateReportBatch_shouldNotBeBlockedByOrgMutationLock() {
        // 2026-10-20 定案：异动期间不再锁死批量修改
        PlanReport report = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(61L);
        Indicator indicator = new Indicator();
        indicator.setId(2010L);
        indicator.setProgress(20);
        when(planReportRepository.findById(61L)).thenReturn(Optional.of(report));
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(inv -> inv.getArgument(0));
        when(indicatorRepository.findById(2010L)).thenReturn(Optional.of(indicator));
        ReportApplicationService service = newService(List.of(workflowBusinessContextPort));

        UpdatePlanReportIndicatorDetailRequest detail = new UpdatePlanReportIndicatorDetailRequest();
        detail.setIndicatorId(2010L);
        detail.setProgress(45);

        assertThatCode(() -> service.updateReportBatch(
                61L, "标题", "内容", "摘要", 45, "问题", "计划", 8002L, List.of(detail)))
                .doesNotThrowAnyException();
    }

    // ==================== 2026-10-31 修复（图05）：重报说明透传 ====================

    private ReportApplicationService submitServiceWithUnlockOrg() {
        // 互斥校验放行（无在审异动），明细完整即可提交
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate =
                org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Integer.class),
                org.mockito.ArgumentMatchers.eq(44L),
                org.mockito.ArgumentMatchers.eq(44L),
                org.mockito.ArgumentMatchers.eq(44L)))
                .thenReturn(0);
        return new ReportApplicationService(
                planReportRepository,
                planReportIndicatorRepository,
                indicatorRepository,
                eventPublisher,
                workflowApprovalMetadataQuery,
                workflowAuditSyncGateway,
                jdbcTemplate,
                java.util.List.of());
    }

    private PlanReport resubmittableDraft() {
        PlanReport report = PlanReport.createDraft("202610", 44L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(63L);
        return report;
    }

    private void stubSuccessfulSubmit(PlanReport report) {
        when(planReportRepository.findById(63L)).thenReturn(Optional.of(report));
        when(planReportIndicatorRepository.findByReportId(63L)).thenReturn(List.of(
                new PlanReportIndicatorSnapshot(2001L, 50, "内容", "NORMAL", "说明", null, List.of())
        ));
        when(planReportIndicatorRepository.findByReportIds(any())).thenReturn(Map.of());
        when(planReportRepository.save(any(PlanReport.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private com.sism.execution.domain.report.event.PlanReportSubmittedEvent captureSubmittedEvent() {
        org.mockito.ArgumentCaptor<List<com.sism.shared.domain.model.base.DomainEvent>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publishAll(captor.capture());
        return captor.getValue().stream()
                .filter(com.sism.execution.domain.report.event.PlanReportSubmittedEvent.class::isInstance)
                .map(com.sism.execution.domain.report.event.PlanReportSubmittedEvent.class::cast)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void submitReport_shouldCarryResubmitCommentIntoSubmittedEvent() {
        // 2026-10-31 修复（图05）：前端强制收集的「重报说明」必须随提交事件
        // 透传进工作流链路（监听器写入提交步备注），不得再被丢弃。
        PlanReport report = resubmittableDraft();
        stubSuccessfulSubmit(report);
        ReportApplicationService service = submitServiceWithUnlockOrg();

        service.submitReport(63L, 8001L, "已按驳回意见修正科研经费数据");

        var event = captureSubmittedEvent();
        assertThat(event.getSubmitComment()).isEqualTo("已按驳回意见修正科研经费数据");
        assertThat(event.getReportId()).isEqualTo(63L);
        assertThat(report.getStatus()).isEqualTo(PlanReport.STATUS_SUBMITTED);
    }

    @Test
    void submitReport_shouldKeepLegacyBehaviourWhenCommentAbsent() {
        // 缺省（不传 comment）行为与改前完全一致：事件 submitComment 为空，
        // 工作流侧保留「提交人重新提交（未填写说明）」兜底文案。
        PlanReport report = resubmittableDraft();
        stubSuccessfulSubmit(report);
        ReportApplicationService service = submitServiceWithUnlockOrg();

        service.submitReport(63L, 8001L);

        var event = captureSubmittedEvent();
        assertThat(event.getSubmitComment()).isNull();
        assertThat(report.getStatus()).isEqualTo(PlanReport.STATUS_SUBMITTED);
    }
}
