package com.sism.workflow.application.query;

import com.sism.workflow.application.definition.WorkflowDefinitionQueryService;
import com.sism.shared.domain.user.UserIdentity;
import com.sism.shared.domain.user.UserProvider;
import com.sism.shared.domain.workflow.WorkflowBusinessContextPort;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.query.repository.WorkflowQueryRepository;
import com.sism.workflow.domain.definition.FlowDefinitionRepository;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditStepInstance;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import com.sism.workflow.interfaces.dto.PageResult;
import com.sism.workflow.interfaces.dto.WorkflowInstanceDetailResponse;
import com.sism.workflow.interfaces.dto.WorkflowInstanceResponse;
import com.sism.workflow.interfaces.dto.WorkflowHistoryCardResponse;
import com.sism.workflow.interfaces.dto.WorkflowTaskResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowReadModelServiceTest {

    @Mock
    private WorkflowDefinitionQueryService workflowDefinitionQueryService;

    @Mock
    private AuditInstanceRepository auditInstanceRepository;

    @Mock
    private WorkflowQueryRepository workflowQueryRepository;

    @Mock
    private UserProvider userProvider;

    @Mock
    private WorkflowBusinessContextPort workflowBusinessContextPort;

    private WorkflowReadModelService newService() {
        FlowDefinitionRepository flowDefinitionRepository = mock(FlowDefinitionRepository.class);
        WorkflowReadModelMapper mapper = new WorkflowReadModelMapper(flowDefinitionRepository, java.util.List.of());
        return new WorkflowReadModelService(
                workflowDefinitionQueryService,
                auditInstanceRepository,
                workflowQueryRepository,
                mapper,
                userProvider,
                java.util.List.of(workflowBusinessContextPort)
        );
    }

    @Test
    void listInstances_shouldMapInstancePage() {
        AuditInstance instance = new AuditInstance();
        instance.setId(7L);
        instance.setFlowDefId(3L);
        instance.setEntityId(99L);
        instance.setStatus(AuditInstance.STATUS_PENDING);

        when(auditInstanceRepository.findByFlowDefId(3L, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(instance), PageRequest.of(0, 10), 1));

        PageResult<?> result = newService().listInstances("3", 1, 10);

        assertEquals(1, result.getTotal());
    }

    @Test
    void listDefinitions_shouldClampInvalidPaginationValues() {
        when(workflowDefinitionQueryService.getAllAuditFlowDefs(PageRequest.of(0, 1)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));

        PageResult<?> result = newService().listDefinitions(0, 0);

        assertEquals(1, result.getPageSize());
        assertEquals(0, result.getTotalPages());
    }

    @Test
    void listInstances_shouldRejectNonNumericDefinitionId() {
        assertThrows(IllegalArgumentException.class, () -> newService().listInstances("abc", 1, 10));
    }

    @Test
    void getMyPendingTasks_shouldReturnStepLevelTaskView() {
        AuditInstance instance = new AuditInstance();
        instance.setId(8L);
        instance.setFlowDefId(2L);
        instance.setEntityType("PLAN_REPORT");
        instance.setEntityId(88L);
        instance.setRequesterOrgId(44L);
        instance.setStartedAt(LocalDateTime.now().minusHours(1));

        AuditStepInstance pending = new AuditStepInstance();
        pending.setId(88L);
        pending.setStepDefId(5L);
        pending.setStepNo(2);
        pending.setStepName("部门负责人审批");
        pending.setStatus(AuditInstance.STEP_STATUS_PENDING);
        pending.setApproverId(101L);
        pending.setApproverOrgId(44L);
        pending.setCreatedAt(LocalDateTime.now());

        instance.addStepInstance(pending);

        AuditFlowDef flowDef = new AuditFlowDef();
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setId(5L);
        stepDef.setRoleId(2L);
        flowDef.setSteps(List.of(stepDef));

        UserIdentity approver = new UserIdentity(101L, "approver101", "审批人A", 44L, true);
        when(userProvider.findIdentity(101L)).thenReturn(Optional.of(approver));
        when(workflowQueryRepository.findPendingAuditInstancesByUserId(101L, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(instance), PageRequest.of(0, 10), 1));
        when(workflowDefinitionQueryService.getAuditFlowDefById(2L)).thenReturn(flowDef);

        PageResult<WorkflowTaskResponse> result = newService().getMyPendingTasks(101L, 1);

        assertEquals(1, result.getTotal());
        assertEquals("88", result.getItems().get(0).getTaskId());
        assertEquals("step_5", result.getItems().get(0).getTaskKey());
        assertEquals("审批人A", result.getItems().get(0).getAssigneeName());
        assertEquals("PLAN_REPORT", result.getItems().get(0).getEntityType());
    }

    @Test
    void getMyPendingTasks_shouldFallbackTaskNameToEntityLabelWhenStepNameMissing() {
        AuditInstance instance = new AuditInstance();
        instance.setId(18L);
        instance.setFlowDefId(1L);
        instance.setEntityType("PLAN_REPORT");
        instance.setEntityId(123L);
        instance.setRequesterOrgId(35L);
        instance.setStartedAt(LocalDateTime.now().minusHours(2));

        AuditStepInstance pending = new AuditStepInstance();
        pending.setId(180L);
        pending.setStepDefId(2L);
        pending.setStepNo(1);
        pending.setStatus(AuditInstance.STEP_STATUS_PENDING);
        pending.setApproverId(201L);
        pending.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        instance.addStepInstance(pending);

        AuditFlowDef flowDef = new AuditFlowDef();
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setId(2L);
        stepDef.setRoleId(3L);
        flowDef.setSteps(List.of(stepDef));

        UserIdentity approver = new UserIdentity(201L, "approver201", null, 35L, true);
        when(userProvider.findIdentity(201L)).thenReturn(Optional.of(approver));
        when(workflowQueryRepository.findPendingAuditInstancesByUserId(201L, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(instance), PageRequest.of(0, 10), 1));
        when(workflowDefinitionQueryService.getAuditFlowDefById(1L)).thenReturn(flowDef);

        PageResult<WorkflowTaskResponse> result = newService().getMyPendingTasks(201L, 1);

        assertEquals(1, result.getTotal());
        assertEquals("PLAN_REPORT", result.getItems().get(0).getEntityType());
        assertEquals("approver201", result.getItems().get(0).getAssigneeName());
    }

    @Test
    void findMyPendingTaskById_shouldReturnDirectTaskMatch() {
        AuditInstance instance = new AuditInstance();
        instance.setId(33L);
        instance.setFlowDefId(2L);
        instance.setEntityType("PLAN");
        instance.setEntityId(901L);
        instance.setRequesterOrgId(44L);

        AuditStepInstance pending = new AuditStepInstance();
        pending.setId(330L);
        pending.setStepDefId(8L);
        pending.setStepNo(2);
        pending.setStepName("部门审批");
        pending.setStatus(AuditInstance.STEP_STATUS_PENDING);
        pending.setApproverId(120L);
        pending.setApproverOrgId(44L);
        pending.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        instance.addStepInstance(pending);

        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setFlowCode("PLAN_APPROVAL");
        flowDef.setFlowName("计划审批");
        UserIdentity approver = new UserIdentity(120L, "approver120", "审批人120", 44L, true);

        when(workflowQueryRepository.findPendingAuditInstanceByStepIdAndUserId(330L, 120L))
                .thenReturn(Optional.of(instance));
        when(workflowDefinitionQueryService.getAuditFlowDefById(2L)).thenReturn(flowDef);
        when(workflowBusinessContextPort.getBusinessSummary("PLAN", 901L))
                .thenReturn(Optional.of(new WorkflowBusinessContextPort.BusinessSummary(901L, "Plan 901", 44L, "教务处", 55L, "计算机学院", "Plan 901")));
        when(userProvider.findIdentity(120L)).thenReturn(Optional.of(approver));

        Optional<WorkflowTaskResponse> result = newService().findMyPendingTaskById(120L, "330");

        assertTrue(result.isPresent());
        assertEquals("330", result.get().getTaskId());
        assertEquals("部门审批", result.get().getCurrentStepName());
        assertEquals("审批人120", result.get().getAssigneeName());
    }

    @Test
    void findMyPendingTaskById_shouldReturnEmptyWhenTaskNotOwnedByUser() {
        when(workflowQueryRepository.findPendingAuditInstanceByStepIdAndUserId(330L, 120L))
                .thenReturn(Optional.empty());

        Optional<WorkflowTaskResponse> result = newService().findMyPendingTaskById(120L, "330");

        assertTrue(result.isEmpty());
    }

    @Test
    void getInstanceDetail_shouldBuildHistoryAndTasks() {
        AuditInstance instance = new AuditInstance();
        instance.setId(9L);
        instance.setFlowDefId(2L);
        instance.setEntityType("PLAN_REPORT");
        instance.setEntityId(100L);
        instance.setRequesterId(1L);
        instance.setStartedAt(LocalDateTime.now().minusDays(1));
        instance.setCompletedAt(LocalDateTime.now());
        instance.setStatus(AuditInstance.STATUS_APPROVED);

        AuditStepInstance approved = new AuditStepInstance();
        approved.setId(91L);
        approved.setStepDefId(6L);
        approved.setStepNo(1);
        approved.setStepName("一级审批");
        approved.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        approved.setApproverId(11L);
        approved.setCreatedAt(LocalDateTime.now().minusHours(2));
        approved.setApprovedAt(LocalDateTime.now().minusHours(1));
        instance.addStepInstance(approved);

        UserIdentity approver = new UserIdentity(11L, "approver11", "审批人", null, true);
        when(userProvider.findIdentity(11L)).thenReturn(Optional.of(approver));
        when(userProvider.findIdentity(1L)).thenReturn(Optional.of(namedUser(1L, "发起人")));
        when(auditInstanceRepository.findById(9L)).thenReturn(Optional.of(instance));

        var detail = newService().getInstanceDetail("9");

        assertEquals(1, detail.getTasks().size());
        assertEquals(1, detail.getHistory().size());
        assertEquals(null, detail.getCurrentTaskId());
        assertEquals("审批人", detail.getTasks().get(0).getAssigneeName());
        assertEquals("一级审批", detail.getHistory().get(0).getTaskName());
        assertEquals(null, detail.getHistory().get(0).getComment());
    }

    @Test
    void getInstanceDetail_shouldExposeCurrentApproverForPendingRoleStep() {
        AuditInstance instance = new AuditInstance();
        instance.setId(19L);
        instance.setFlowDefId(2L);
        instance.setEntityType("PLAN");
        instance.setEntityId(701L);
        instance.setRequesterId(1L);
        instance.setRequesterOrgId(35L);
        instance.setStartedAt(LocalDateTime.now().minusHours(3));
        instance.setStatus(AuditInstance.STATUS_PENDING);

        AuditStepInstance pending = new AuditStepInstance();
        pending.setId(191L);
        pending.setStepDefId(16L);
        pending.setStepNo(2);
        pending.setStepName("战略发展部负责人审批");
        pending.setStatus(AuditInstance.STEP_STATUS_PENDING);
        pending.setApproverId(189L);
        pending.setApproverOrgId(35L);
        pending.setCreatedAt(LocalDateTime.now().minusHours(1));
        instance.addStepInstance(pending);

        when(auditInstanceRepository.findById(19L)).thenReturn(Optional.of(instance));
        when(workflowBusinessContextPort.getBusinessSummary("PLAN", 701L))
                .thenReturn(Optional.of(new WorkflowBusinessContextPort.BusinessSummary(701L, "Plan 701", 35L, "战略发展部", 44L, "教务处", "Plan 701")));
        when(userProvider.findIdentity(1L)).thenReturn(Optional.of(namedUser(1L, "发起人")));
        when(userProvider.findIdentity(189L)).thenReturn(Optional.of(namedUser(189L, "战略发展部负责人1")));

        WorkflowInstanceDetailResponse detail = newService().getInstanceDetail("19");

        assertEquals("战略发展部负责人审批", detail.getCurrentStepName());
        assertEquals(189L, detail.getCurrentApproverId());
        assertEquals("战略发展部负责人1", detail.getCurrentApproverName());
        assertEquals(189L, detail.getTasks().get(0).getAssigneeId());
        assertEquals("战略发展部负责人1", detail.getTasks().get(0).getAssigneeName());
        assertEquals("战略发展部", detail.getTasks().get(0).getApproverOrgName());
    }

    @Test
    void getInstanceDetailByBusiness_shouldPreferLatestInReviewInstance() {
        AuditInstance approved = new AuditInstance();
        approved.setId(41L);
        approved.setFlowDefId(1L);
        approved.setEntityType("PLAN");
        approved.setEntityId(7071L);
        approved.setRequesterId(188L);
        approved.setRequesterOrgId(35L);
        approved.setStatus(AuditInstance.STATUS_APPROVED);
        approved.setStartedAt(LocalDateTime.of(2026, 3, 22, 8, 8));
        approved.setCompletedAt(LocalDateTime.of(2026, 3, 22, 8, 9));

        AuditInstance inReview = new AuditInstance();
        inReview.setId(42L);
        inReview.setFlowDefId(1L);
        inReview.setEntityType("PLAN");
        inReview.setEntityId(7071L);
        inReview.setRequesterId(188L);
        inReview.setRequesterOrgId(35L);
        inReview.setStatus(AuditInstance.STATUS_PENDING);
        inReview.setStartedAt(LocalDateTime.of(2026, 3, 22, 8, 10));

        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setId(1L);
        flowDef.setFlowCode("PLAN_DISPATCH_STRATEGY");
        flowDef.setFlowName("Plan下发审批（战略发展部）");
        flowDef.setSteps(List.of());

        when(auditInstanceRepository.findByBusinessTypeAndBusinessId("PLAN", 7071L))
                .thenReturn(List.of(approved, inReview));
        when(workflowBusinessContextPort.getBusinessSummary("PLAN", 7071L))
                .thenReturn(Optional.of(new WorkflowBusinessContextPort.BusinessSummary(7071L, "Plan 7071", 35L, "战略发展部", 44L, "教务处", "Plan 7071")));
        when(userProvider.findIdentity(188L)).thenReturn(Optional.of(namedUser(188L, "战略部管理员")));
        when(workflowDefinitionQueryService.getAuditFlowDefById(1L)).thenReturn(flowDef);

        WorkflowInstanceDetailResponse detail = newService().getInstanceDetailByBusiness("PLAN", 7071L);

        assertNotNull(detail);
        assertEquals("42", detail.getInstanceId());
        assertEquals("PLAN_DISPATCH_STRATEGY", detail.getFlowCode());
        assertEquals("战略发展部", detail.getSourceOrgName());
        assertEquals("教务处", detail.getTargetOrgName());
    }

    @Test
    void getMyApprovedInstances_shouldReturnEnrichedSummaries() {
        AuditInstance instance = new AuditInstance();
        instance.setId(13L);
        instance.setFlowDefId(3L);
        instance.setEntityType("PLAN");
        instance.setEntityId(7073L);
        instance.setRequesterId(223L);
        instance.setRequesterOrgId(44L);
        instance.setStatus(AuditInstance.STATUS_APPROVED);
        instance.setStartedAt(LocalDateTime.of(2026, 3, 22, 8, 12));
        instance.setCompletedAt(LocalDateTime.of(2026, 3, 22, 8, 14));

        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setId(3L);
        flowDef.setFlowCode("PLAN_APPROVAL_FUNCDEPT");
        flowDef.setFlowName("Plan审批流程（职能部门）");
        flowDef.setSteps(List.of());

        when(workflowQueryRepository.findApprovedAuditInstancesByUserId(124L, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(instance), PageRequest.of(0, 10), 1));
        when(workflowBusinessContextPort.getBusinessSummary("PLAN", 7073L))
                .thenReturn(Optional.of(new WorkflowBusinessContextPort.BusinessSummary(7073L, "Plan 7073", 44L, "教务处", 44L, "教务处", "Plan 7073")));
        when(userProvider.findIdentity(223L)).thenReturn(Optional.of(namedUser(223L, "教务处填报人")));
        when(workflowDefinitionQueryService.getAuditFlowDefById(3L)).thenReturn(flowDef);

        PageResult<WorkflowInstanceResponse> page = newService().getMyApprovedInstances(124L, 1, 10);

        assertEquals(1, page.getTotal());
        assertEquals("13", page.getItems().get(0).getInstanceId());
        assertEquals("PLAN_APPROVAL_FUNCDEPT", page.getItems().get(0).getFlowCode());
        assertEquals("教务处", page.getItems().get(0).getSourceOrgName());
        assertEquals("教务处", page.getItems().get(0).getTargetOrgName());
        assertEquals(7073L, page.getItems().get(0).getEntityId());
    }

    @Test
    void listInstanceHistoryByBusiness_shouldOnlyIncludeTerminalApprovedInstances() {
        AuditFlowDef flowDef = new AuditFlowDef();
        AuditStepDef terminalStep = new AuditStepDef();
        terminalStep.setId(10L);
        terminalStep.setIsTerminal(true);
        flowDef.setSteps(List.of(terminalStep));

        AuditInstance approved = new AuditInstance();
        approved.setId(300L);
        approved.setFlowDefId(3L);
        approved.setEntityType("PLAN");
        approved.setEntityId(77L);
        approved.setStatus(AuditInstance.STATUS_APPROVED);
        approved.setStartedAt(LocalDateTime.of(2026, 3, 20, 10, 0));
        approved.setCompletedAt(LocalDateTime.of(2026, 3, 20, 12, 0));
        AuditStepInstance approvedTerminal = new AuditStepInstance();
        approvedTerminal.setId(301L);
        approvedTerminal.setStepDefId(10L);
        approvedTerminal.setStepNo(4);
        approvedTerminal.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        approved.addStepInstance(approvedTerminal);

        AuditInstance rejected = new AuditInstance();
        rejected.setId(302L);
        rejected.setFlowDefId(3L);
        rejected.setEntityType("PLAN");
        rejected.setEntityId(77L);
        rejected.setStatus(AuditInstance.STATUS_REJECTED);
        rejected.setStartedAt(LocalDateTime.of(2026, 3, 21, 10, 0));
        AuditStepInstance rejectedTerminal = new AuditStepInstance();
        rejectedTerminal.setId(303L);
        rejectedTerminal.setStepDefId(10L);
        rejectedTerminal.setStepNo(4);
        rejectedTerminal.setStatus(AuditInstance.STEP_STATUS_REJECTED);
        rejected.addStepInstance(rejectedTerminal);

        when(auditInstanceRepository.findByBusinessTypeAndBusinessId("PLAN", 77L))
                .thenReturn(List.of(approved, rejected));
        when(workflowDefinitionQueryService.getAuditFlowDefById(3L)).thenReturn(flowDef);
        when(workflowBusinessContextPort.getBusinessSummary("PLAN", 77L))
                .thenReturn(Optional.of(new WorkflowBusinessContextPort.BusinessSummary(77L, "Plan 77", 35L, "战略发展部", 44L, "教务处", "Plan 77")));

        List<WorkflowHistoryCardResponse> result = newService().listInstanceHistoryByBusiness("PLAN", 77L);

        assertEquals(1, result.size());
        assertEquals("300", result.get(0).getInstanceId());
        assertEquals(1, result.get(0).getRoundNo());
    }

    @Test
    void getInstanceDetailByBusiness_shouldReturnLatestInReviewInstance() {
        AuditInstance rejected = new AuditInstance();
        rejected.setId(401L);
        rejected.setFlowDefId(3L);
        rejected.setEntityType("PLAN");
        rejected.setEntityId(88L);
        rejected.setStatus(AuditInstance.STATUS_REJECTED);
        rejected.setStartedAt(LocalDateTime.of(2026, 3, 20, 10, 0));

        AuditInstance inReview = new AuditInstance();
        inReview.setId(402L);
        inReview.setFlowDefId(3L);
        inReview.setEntityType("PLAN");
        inReview.setEntityId(88L);
        inReview.setStatus(AuditInstance.STATUS_PENDING);
        inReview.setStartedAt(LocalDateTime.of(2026, 3, 21, 10, 0));

        when(auditInstanceRepository.findByBusinessTypeAndBusinessId("PLAN", 88L))
                .thenReturn(List.of(rejected, inReview));
        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setId(3L);
        flowDef.setFlowCode("PLAN_APPROVAL_FUNCDEPT");
        flowDef.setFlowName("Plan审批流程（职能部门）");
        when(workflowDefinitionQueryService.getAuditFlowDefById(3L)).thenReturn(flowDef);

        when(workflowBusinessContextPort.getBusinessSummary("PLAN", 88L))
                .thenReturn(Optional.of(new WorkflowBusinessContextPort.BusinessSummary(88L, "Plan 88", 35L, "战略发展部", 44L, "教务处", "Plan 88")));

        var detail = newService().getInstanceDetailByBusiness("PLAN", 88L);

        assertNotNull(detail);
        assertEquals("402", detail.getInstanceId());
        assertEquals("IN_REVIEW", detail.getStatus());
        assertEquals("PLAN_APPROVAL_FUNCDEPT", detail.getFlowCode());
    }

    private UserIdentity namedUser(Long id, String realName) {
        return new UserIdentity(id, realName, realName, null, true);
    }

}
