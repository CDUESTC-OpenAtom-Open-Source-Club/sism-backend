package com.sism.workflow.application.support;

import com.sism.shared.domain.user.UserIdentity;
import com.sism.shared.domain.user.UserProvider;
import com.sism.shared.domain.workflow.WorkflowBusinessContextPort;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.definition.FlowDefinitionRepository;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.runtime.AuditInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StepInstanceFactoryTest {

    @Mock
    private UserProvider userProvider;

    @Mock
    private WorkflowBusinessContextPort workflowBusinessContextPort;

    @Mock
    private FlowDefinitionRepository flowDefinitionRepository;

    @Test
    void initialize_shouldInferSubmitStepFromLegacyName() {
        AuditFlowDef flowDef = new AuditFlowDef();
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setId(1L);
        stepDef.setStepName("填报人提交");
        stepDef.setStepOrder(1);
        flowDef.setSteps(List.of(stepDef));

        StepInstanceFactory factory = new StepInstanceFactory(
                new ApproverResolver(userProvider, List.of(workflowBusinessContextPort), workflowApproverProperties(), flowDefinitionRepository),
                new SubmissionStepAutoCompletePolicy()
        );

        AuditInstance instance = new AuditInstance();
        instance.setEntityType("PlanReport");
        instance.setEntityId(1L);

        assertDoesNotThrow(() -> factory.initialize(instance, flowDef, 1L, 1L));
        assertEquals(1, instance.getStepInstances().size());
        assertEquals(AuditInstance.STEP_STATUS_APPROVED, instance.getStepInstances().get(0).getStatus());
    }

    @Test
    void initialize_shouldRejectApprovalStepWhenRoleIdMissing() {
        AuditFlowDef flowDef = new AuditFlowDef();
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setId(1L);
        stepDef.setStepName("审批");
        stepDef.setStepOrder(1);
        stepDef.setStepType(AuditStepDef.STEP_TYPE_APPROVAL);
        flowDef.setSteps(List.of(stepDef));

        StepInstanceFactory factory = new StepInstanceFactory(
                new ApproverResolver(userProvider, List.of(workflowBusinessContextPort), workflowApproverProperties(), flowDefinitionRepository),
                new SubmissionStepAutoCompletePolicy()
        );

        AuditInstance instance = new AuditInstance();
        instance.setEntityType("PlanReport");
        instance.setEntityId(1L);

        assertThrows(IllegalStateException.class, () -> factory.initialize(instance, flowDef, 1L, 1L));
    }

    @Test
    void initialize_shouldAllowSubmitStepWithoutRoleId() {
        AuditFlowDef flowDef = new AuditFlowDef();
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setId(1L);
        stepDef.setStepName("提交");
        stepDef.setStepOrder(1);
        stepDef.setStepType(AuditStepDef.STEP_TYPE_SUBMIT);
        flowDef.setSteps(List.of(stepDef));

        StepInstanceFactory factory = new StepInstanceFactory(
                new ApproverResolver(userProvider, List.of(workflowBusinessContextPort), workflowApproverProperties(), flowDefinitionRepository),
                new SubmissionStepAutoCompletePolicy()
        );

        AuditInstance instance = new AuditInstance();
        instance.setEntityType("PlanReport");
        instance.setEntityId(1L);

        assertDoesNotThrow(() -> factory.initialize(instance, flowDef, 1L, 1L));
    }

    @Test
    void initialize_shouldPersistApproverOrgIdForApprovalStep() {
        AuditFlowDef flowDef = new AuditFlowDef();

        AuditStepDef submitStep = new AuditStepDef();
        submitStep.setId(1L);
        submitStep.setStepName("提交");
        submitStep.setStepOrder(1);
        submitStep.setStepType(AuditStepDef.STEP_TYPE_SUBMIT);

        AuditStepDef approvalStep = new AuditStepDef();
        approvalStep.setId(2L);
        approvalStep.setStepName("战略发展部负责人审批");
        approvalStep.setStepOrder(2);
        approvalStep.setStepType(AuditStepDef.STEP_TYPE_APPROVAL);
        approvalStep.setRoleId(3L);
        flowDef.setSteps(List.of(submitStep, approvalStep));

        UserIdentity approver = new UserIdentity(9L, "u9", "审批人9", 35L, true);

        when(userProvider.findActiveIdentitiesByRole(3L)).thenReturn(List.of(approver));

        StepInstanceFactory factory = new StepInstanceFactory(
                new ApproverResolver(userProvider, List.of(workflowBusinessContextPort), workflowApproverProperties(), flowDefinitionRepository),
                new SubmissionStepAutoCompletePolicy()
        );

        AuditInstance instance = new AuditInstance();
        instance.setEntityType("PlanReport");
        instance.setEntityId(1L);

        factory.initialize(instance, flowDef, 100L, 35L);

        assertEquals(35L, instance.getStepInstances().get(0).getApproverOrgId());
        assertEquals(9L, instance.getStepInstances().get(1).getApproverId());
        assertEquals(35L, instance.getStepInstances().get(1).getApproverOrgId());
    }

    @Test
    void initialize_shouldAssignSecondApprovalStepToDemoRequester() {
        AuditFlowDef flowDef = new AuditFlowDef();

        AuditStepDef submitStep = new AuditStepDef();
        submitStep.setId(1L);
        submitStep.setStepName("提交");
        submitStep.setStepOrder(1);
        submitStep.setStepType(AuditStepDef.STEP_TYPE_SUBMIT);

        AuditStepDef approvalStep = new AuditStepDef();
        approvalStep.setId(2L);
        approvalStep.setStepName("战略发展部负责人审批");
        approvalStep.setStepOrder(2);
        approvalStep.setStepType(AuditStepDef.STEP_TYPE_APPROVAL);
        approvalStep.setRoleId(3L);
        flowDef.setSteps(List.of(submitStep, approvalStep));

        UserIdentity demoRequester = new UserIdentity(410L, "zlb_demo", "战略发展部[演示]", 35L, true, true);

        when(userProvider.findIdentity(410L)).thenReturn(java.util.Optional.of(demoRequester));
        when(userProvider.getUserRoleIds(410L)).thenReturn(List.of(1L, 2L, 3L, 4L));

        StepInstanceFactory factory = new StepInstanceFactory(
                new ApproverResolver(userProvider, List.of(workflowBusinessContextPort), workflowApproverProperties(), flowDefinitionRepository),
                new SubmissionStepAutoCompletePolicy()
        );

        AuditInstance instance = new AuditInstance();
        instance.setEntityType("Plan");
        instance.setEntityId(4044L);

        factory.initialize(instance, flowDef, 410L, 35L);

        assertEquals(410L, instance.getStepInstances().get(1).getApproverId());
        assertEquals(35L, instance.getStepInstances().get(1).getApproverOrgId());
    }
    private WorkflowApproverProperties workflowApproverProperties() {
        WorkflowApproverProperties properties = new WorkflowApproverProperties();
        properties.setApproverRoleId(2L);
        properties.setStrategyDeptHeadRoleId(3L);
        properties.setVicePresidentRoleId(4L);
        properties.setStrategyOrgId(35L);
        properties.setFunctionalVicePresidentScopeByOrg(java.util.Map.of());
        return properties;
    }
}
