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

    private ReportApplicationService reportApplicationService;

    @BeforeEach
    void setUp() {
        reportApplicationService = new ReportApplicationService(
                planReportRepository,
                planReportIndicatorRepository,
                indicatorRepository,
                eventPublisher,
                workflowApprovalMetadataQuery,
                workflowAuditSyncGateway
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
                new PlanReportIndicatorSnapshot(1001L, 80, "content", null, null, List.of()),
                new PlanReportIndicatorSnapshot(1002L, null, "content", null, null, List.of())
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

    @Test
    void findReportById_shouldHydrateIndicatorDetailsFromDraftTable() {
        PlanReport report = PlanReport.createDraft("202603", 39L, ReportOrgType.FUNC_DEPT, 111L);
        report.setId(16L);

        when(planReportRepository.findById(16L)).thenReturn(Optional.of(report));
        when(planReportIndicatorRepository.findByReportIds(List.of(16L)))
                .thenReturn(Map.of(16L, List.of(new PlanReportIndicatorSnapshot(2003L, 15, "已填报草稿", null, null, List.of()))));

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
                .thenReturn(List.of(new PlanReportIndicatorSnapshot(2001L, 67, "审批通过备注", null, null, List.of())));
        when(planReportIndicatorRepository.findByReportIds(List.of(13L)))
                .thenReturn(Map.of(13L, List.of(new PlanReportIndicatorSnapshot(2001L, 67, "审批通过备注", null, null, List.of()))));
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
                .thenReturn(List.of(new PlanReportIndicatorSnapshot(2002L, 20, "审批完成", null, null, List.of())));
        when(planReportIndicatorRepository.findByReportIds(List.of(15L)))
                .thenReturn(Map.of(15L, List.of(new PlanReportIndicatorSnapshot(2002L, 20, "审批完成", null, null, List.of()))));
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
                        51L, List.of(new PlanReportIndicatorSnapshot(3001L, 11, "A", null, null, List.of())),
                        52L, List.of(new PlanReportIndicatorSnapshot(3002L, 22, "B", null, null, List.of()))
                ));

        var page = reportApplicationService.findReportsByStatus("SUBMITTED", 1, 10);

        assertThat(page.getContent()).extracting(PlanReport::getId).containsExactly(51L, 52L);
        assertThat(page.getContent().get(0).getIndicatorDetails()).hasSize(1);
        assertThat(page.getContent().get(1).getIndicatorDetails()).hasSize(1);
        verify(planReportIndicatorRepository).findByReportIds(List.of(51L, 52L));
    }
}
