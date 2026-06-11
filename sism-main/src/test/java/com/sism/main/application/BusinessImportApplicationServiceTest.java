package com.sism.main.application;

import com.sism.iam.domain.user.UserRepository;
import com.sism.main.interfaces.dto.BusinessImportDtos.ConflictMode;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportCommitRequest;
import com.sism.organization.domain.OrgType;
import com.sism.organization.domain.OrganizationRepository;
import com.sism.organization.domain.SysOrg;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.strategy.application.BasicTaskWeightValidationService;
import com.sism.strategy.application.MilestoneApplicationService;
import com.sism.strategy.application.StrategyApplicationService;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import com.sism.strategy.domain.plan.PlanStatus;
import com.sism.strategy.domain.repository.IndicatorRepository;
import com.sism.strategy.domain.repository.PlanRepository;
import com.sism.task.domain.repository.TaskRepository;
import com.sism.task.domain.task.StrategicTask;
import com.sism.task.domain.task.TaskType;
import com.sism.workflow.application.WorkflowApplicationService;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Business Import Application Service Tests")
class BusinessImportApplicationServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private IndicatorRepository indicatorRepository;
    @Mock
    private StrategyApplicationService strategyApplicationService;
    @Mock
    private MilestoneApplicationService milestoneApplicationService;
    @Mock
    private BasicTaskWeightValidationService basicTaskWeightValidationService;
    @Mock
    private WorkflowApplicationService workflowApplicationService;
    @Mock
    private AuditInstanceRepository auditInstanceRepository;
    @Mock
    private TransactionTemplate transactionTemplate;

    private BusinessImportApplicationService service;

    @BeforeEach
    void setUp() {
        service = new BusinessImportApplicationService(
                new ExcelBusinessImportParser(),
                userRepository,
                organizationRepository,
                planRepository,
                taskRepository,
                indicatorRepository,
                strategyApplicationService,
                milestoneApplicationService,
                basicTaskWeightValidationService,
                workflowApplicationService,
                auditInstanceRepository,
                transactionTemplate);
    }

    @Test
    @DisplayName("Should fail auto approval on commit when persisted basic weights do not equal 100")
    void shouldFailAutoApprovalOnCommitWhenPersistedBasicWeightsDoNotEqual100() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);
        stubCommitDependencies(fixture);
        doThrow(new IllegalStateException("学院指标权重合计必须为100，当前为70"))
                .when(basicTaskWeightValidationService)
                .validatePlanBasicWeight(900L, fixture.college().getId());

        var preview = service.previewDistribution(
                distributionWorkbook("70-percent-basic-weight.xlsx"),
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                null,
                fixture.currentUser());

        var result = service.commit(
                preview.batchId(),
                new ImportCommitRequest(
                        preview.confirmToken(),
                        ConflictMode.APPEND,
                        true,
                        "导入后自动下发审批"),
                fixture.currentUser());

        assertEquals("COMMIT_BLOCKED", result.status());
        assertEquals(0, result.createdCount());
        assertEquals(0, result.updatedCount());
        assertNotNull(result.workflow());
        assertEquals("BLOCKED", result.workflow().status());
        assertEquals("学院指标权重合计必须为100，当前为70", result.workflow().message());
        verify(workflowApplicationService, never()).getAuditFlowDefByCode(anyString());
    }

    @Test
    @DisplayName("Should allow normal import when persisted basic weights do not equal 100")
    void shouldAllowNormalImportWhenPersistedBasicWeightsDoNotEqual100() {
        Fixture fixture = fixture();
        stubPreviewDependencies(fixture);
        stubCommitDependencies(fixture);

        var preview = service.previewDistribution(
                distributionWorkbook("70-percent-basic-weight-normal-import.xlsx"),
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                null,
                fixture.currentUser());

        var result = service.commit(
                preview.batchId(),
                new ImportCommitRequest(
                        preview.confirmToken(),
                        ConflictMode.APPEND,
                        false,
                        "确认导入"),
                fixture.currentUser());

        assertEquals("COMMITTED", result.status());
        assertEquals(2, result.createdCount());
        assertNotNull(result.workflow());
        assertEquals("导入成功，未自动发起审批", result.workflow().message());
        verify(basicTaskWeightValidationService, never()).validatePlanBasicWeight(anyLong(), anyLong());
        verify(workflowApplicationService, never()).getAuditFlowDefByCode(anyString());
    }

    @Test
    @DisplayName("Should block auto approval when imported plan weight is valid but already distributed")
    void shouldBlockAutoApprovalWhenImportedPlanWeightIsValidButAlreadyDistributed() {
        Fixture fixture = fixture();
        Plan distributedPlan = Plan.create(
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                PlanLevel.FUNC_TO_COLLEGE);
        distributedPlan.setId(900L);
        distributedPlan.setStatus(PlanStatus.DISTRIBUTED.value());
        distributedPlan.setAuditInstanceId(7001L);

        stubPreviewDependencies(fixture);
        when(planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L,
                PlanLevel.FUNC_TO_COLLEGE,
                fixture.functionalOrg().getId(),
                fixture.college().getId()))
                .thenReturn(Optional.empty(), Optional.of(distributedPlan));

        var preview = service.previewDistribution(
                distributionWorkbook("already-distributed.xlsx"),
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                null,
                fixture.currentUser());

        var result = service.commit(
                preview.batchId(),
                new ImportCommitRequest(
                        preview.confirmToken(),
                        ConflictMode.UPDATE,
                        true,
                        "导入后自动下发审批"),
                fixture.currentUser());

        assertEquals("COMMIT_BLOCKED", result.status());
        assertEquals(0, result.createdCount());
        assertEquals(0, result.updatedCount());
        assertNotNull(result.workflow());
        assertEquals("BLOCKED", result.workflow().status());
        assertEquals(7001L, result.workflow().instanceId());
        assertEquals("当前任务已下发，不能重复导入或下发", result.workflow().message());
        verify(transactionTemplate, never()).execute(any());
        verify(basicTaskWeightValidationService, never()).validatePlanBasicWeight(anyLong(), anyLong());
        verify(workflowApplicationService, never()).getAuditFlowDefByCode(anyString());
    }

    @Test
    @DisplayName("Should report already distributed before weight validation")
    void shouldReportAlreadyDistributedBeforeWeightValidation() {
        Fixture fixture = fixture();
        Plan distributedPlan = Plan.create(
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                PlanLevel.FUNC_TO_COLLEGE);
        distributedPlan.setId(900L);
        distributedPlan.setStatus(PlanStatus.DISTRIBUTED.value());
        distributedPlan.setAuditInstanceId(7001L);

        stubPreviewDependencies(fixture);
        when(planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L,
                PlanLevel.FUNC_TO_COLLEGE,
                fixture.functionalOrg().getId(),
                fixture.college().getId()))
                .thenReturn(Optional.empty(), Optional.of(distributedPlan));

        var preview = service.previewDistribution(
                distributionWorkbook("already-distributed-invalid-weight.xlsx"),
                2026L,
                fixture.college().getId(),
                fixture.functionalOrg().getId(),
                null,
                fixture.currentUser());

        var result = service.commit(
                preview.batchId(),
                new ImportCommitRequest(
                        preview.confirmToken(),
                        ConflictMode.UPDATE,
                        true,
                        "导入后自动下发审批"),
                fixture.currentUser());

        assertEquals("COMMIT_BLOCKED", result.status());
        assertNotNull(result.workflow());
        assertEquals("BLOCKED", result.workflow().status());
        assertEquals("当前任务已下发，不能重复导入或下发", result.workflow().message());
        verify(transactionTemplate, never()).execute(any());
        verify(basicTaskWeightValidationService, never()).validatePlanBasicWeight(anyLong(), anyLong());
        verify(workflowApplicationService, never()).getAuditFlowDefByCode(anyString());
    }

    private void stubPreviewDependencies(Fixture fixture) {
        when(organizationRepository.findById(fixture.functionalOrg().getId()))
                .thenReturn(Optional.of(fixture.functionalOrg()));
        when(organizationRepository.findById(fixture.college().getId()))
                .thenReturn(Optional.of(fixture.college()));
        when(indicatorRepository.findByTargetOrgId(fixture.functionalOrg().getId()))
                .thenReturn(List.of(fixture.parentIndicator()));
        when(planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                2026L,
                PlanLevel.FUNC_TO_COLLEGE,
                fixture.functionalOrg().getId(),
                fixture.college().getId()))
                .thenReturn(Optional.empty());
    }

    private void stubCommitDependencies(Fixture fixture) {
        stubCommitDependencies(fixture, true);
    }

    private void stubCommitDependencies(Fixture fixture, boolean savesNewPlan) {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });
        if (savesNewPlan) {
            when(planRepository.save(any(Plan.class))).thenAnswer(invocation -> {
                Plan plan = invocation.getArgument(0);
                plan.setId(900L);
                return plan;
            });
        }
        when(indicatorRepository.findById(fixture.parentIndicator().getId()))
                .thenReturn(Optional.of(fixture.parentIndicator()));
        when(taskRepository.findById(fixture.parentTask().getId()))
                .thenReturn(Optional.of(fixture.parentTask()));
        when(taskRepository.findByPlanIdAndCycleId(900L, 2026L))
                .thenReturn(List.of(fixture.childTask()));
        when(indicatorRepository.findByTaskId(fixture.childTask().getId()))
                .thenReturn(List.of());

        AtomicLong indicatorIds = new AtomicLong(3000L);
        when(strategyApplicationService.createIndicator(
                anyString(),
                any(SysOrg.class),
                any(SysOrg.class),
                anyLong(),
                anyLong(),
                anyString(),
                any(BigDecimal.class),
                any(),
                any(),
                any()))
                .thenAnswer(invocation -> {
                    Indicator indicator = Indicator.create(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(5));
                    indicator.setId(indicatorIds.incrementAndGet());
                    indicator.setTaskId(invocation.getArgument(3));
                    indicator.setParentIndicatorId(invocation.getArgument(4));
                    indicator.setWeightPercent(invocation.getArgument(6));
                    return indicator;
                });
    }

    private Fixture fixture() {
        SysOrg functionalOrg = SysOrg.create("人力资源部", OrgType.functional);
        functionalOrg.setId(4L);
        SysOrg college = SysOrg.create("马克思主义学院", OrgType.academic);
        college.setId(36L);

        StrategicTask parentTask = StrategicTask.create(
                "基础任务",
                TaskType.BASIC,
                1001L,
                2026L,
                functionalOrg,
                functionalOrg);
        parentTask.setId(1001L);

        Indicator parentIndicator = Indicator.create("父级核心指标A", functionalOrg, functionalOrg, "定量");
        parentIndicator.setId(2001L);
        parentIndicator.setTaskId(parentTask.getId());

        StrategicTask childTask = StrategicTask.create(
                "基础任务",
                TaskType.BASIC,
                900L,
                2026L,
                college,
                functionalOrg);
        childTask.setId(901L);

        CurrentUser currentUser = new CurrentUser(
                8L,
                "hr_report",
                "HR Reporter",
                "hr@example.com",
                functionalOrg.getId(),
                List.of(new SimpleGrantedAuthority("ROLE_REPORTER")));

        return new Fixture(functionalOrg, college, parentTask, parentIndicator, childTask, currentUser);
    }

    private MockMultipartFile distributionWorkbook(String fileName) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("指标下发");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("学院");
            header.createCell(1).setCellValue("父级核心指标");
            header.createCell(2).setCellValue("子指标名称");
            header.createCell(3).setCellValue("指标类型");
            header.createCell(4).setCellValue("权重");

            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue("马克思主义学院");
            first.createCell(1).setCellValue("父级核心指标A");
            first.createCell(2).setCellValue("子指标A");
            first.createCell(3).setCellValue("定量");
            first.createCell(4).setCellValue(30);

            Row second = sheet.createRow(2);
            second.createCell(0).setCellValue("马克思主义学院");
            second.createCell(1).setCellValue("父级核心指标A");
            second.createCell(2).setCellValue("子指标B");
            second.createCell(3).setCellValue("定量");
            second.createCell(4).setCellValue(40);

            workbook.write(outputStream);
            return new MockMultipartFile(
                    "file",
                    fileName,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    outputStream.toByteArray());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private record Fixture(
            SysOrg functionalOrg,
            SysOrg college,
            StrategicTask parentTask,
            Indicator parentIndicator,
            StrategicTask childTask,
            CurrentUser currentUser
    ) {
    }
}
