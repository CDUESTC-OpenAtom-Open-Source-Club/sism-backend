package com.sism.workflow.application.support;

import com.sism.shared.domain.user.UserIdentity;
import com.sism.shared.domain.user.UserProvider;
import com.sism.shared.domain.workflow.WorkflowBusinessContextPort;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.runtime.AuditInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApproverResolverTest {

    @Mock
    private UserProvider userProvider;

    @Mock
    private WorkflowBusinessContextPort workflowBusinessContextPort;

    @Mock
    private com.sism.workflow.domain.definition.FlowDefinitionRepository flowDefinitionRepository;

    @Test
    void resolveApproverId_shouldRejectWhenRoleMissing() {
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setStepName("战略发展部负责人审批");

        ApproverResolver resolver = new ApproverResolver(
                userProvider,
                List.of(workflowBusinessContextPort),
                workflowApproverProperties(),
                flowDefinitionRepository
        );

        assertThrows(IllegalStateException.class, () -> resolver.resolveApproverId(stepDef, 1L, 2L));
    }

    @Test
    void resolveApproverId_shouldPreferSameOrgRoleCandidate() {
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setRoleId(2L);
        stepDef.setStepName("职能部门审批人审批");

        UserIdentity user = new UserIdentity(202L, "user202", "审批人202", 30L, true);

        when(userProvider.findActiveIdentitiesByRole(2L)).thenReturn(List.of(user));

        ApproverResolver resolver = new ApproverResolver(
                userProvider,
                List.of(workflowBusinessContextPort),
                workflowApproverProperties(),
                flowDefinitionRepository
        );

        assertEquals(202L, resolver.resolveApproverId(stepDef, 1L, 30L));
    }

    @Test
    void resolveApproverId_shouldPreferSameOrgCollegeLeaderByRoleScope() {
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setRoleId(4L);
        stepDef.setStepName("学院院长审批人审批");

        UserIdentity otherCollegeLeader = new UserIdentity(372L, "u372", "Leader372", 57L, true);
        UserIdentity sameCollegeLeader = new UserIdentity(369L, "u369", "Leader369", 56L, true);

        when(userProvider.findActiveIdentitiesByRole(4L)).thenReturn(List.of(otherCollegeLeader, sameCollegeLeader));

        ApproverResolver resolver = new ApproverResolver(
                userProvider,
                List.of(workflowBusinessContextPort),
                workflowApproverProperties(),
                flowDefinitionRepository
        );

        assertEquals(369L, resolver.resolveApproverId(stepDef, 188L, 56L));
    }

    @Test
    void resolveApproverId_shouldRejectWhenRoleHasNoCandidates() {
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setRoleId(4L);
        stepDef.setStepName("分管校领导审批");

        ApproverResolver resolver = new ApproverResolver(
                userProvider,
                List.of(workflowBusinessContextPort),
                workflowApproverProperties(),
                flowDefinitionRepository
        );

        when(userProvider.findActiveIdentitiesByRole(4L)).thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> resolver.resolveApproverId(stepDef, 88L, 30L));
    }

    @Test
    void resolveApproverId_shouldResolveFunctionalVicePresidentByRequesterOrgMapping() {
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setRoleId(4L);
        stepDef.setStepName("分管校领导审批");

        UserIdentity sameOrgLeader = new UserIdentity(300L, "u300", "Leader300", 44L, true);
        UserIdentity strategyLeader = new UserIdentity(124L, "u124", "Leader124", 35L, true);

        when(userProvider.findActiveIdentitiesByRole(4L)).thenReturn(List.of(sameOrgLeader, strategyLeader));

        ApproverResolver resolver = new ApproverResolver(
                userProvider,
                List.of(workflowBusinessContextPort),
                workflowApproverProperties(),
                flowDefinitionRepository
        );

        assertEquals(300L, resolver.resolveApproverId(stepDef, 223L, 44L));
    }

    @Test
    void resolveApproverId_shouldKeepStrategyVicePresidentOnStrategyOrg() {
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setRoleId(4L);
        stepDef.setStepName("分管校领导审批");

        UserIdentity strategyLeader = new UserIdentity(124L, "u124", "Leader124", 35L, true);
        UserIdentity functionalLeader = new UserIdentity(326L, "u326", "Leader326", 44L, true);

        when(userProvider.findActiveIdentitiesByRole(4L)).thenReturn(List.of(functionalLeader, strategyLeader));

        ApproverResolver resolver = new ApproverResolver(
                userProvider,
                List.of(workflowBusinessContextPort),
                workflowApproverProperties(),
                flowDefinitionRepository
        );

        assertEquals(124L, resolver.resolveApproverId(stepDef, 188L, 35L));
    }

    @Test
    void resolveApproverName_shouldReturnRealNameWhenAvailable() {
        UserIdentity user = new UserIdentity(300L, "u300", "审批人", 35L, true);
        when(userProvider.findIdentity(300L)).thenReturn(Optional.of(user));

        ApproverResolver resolver = new ApproverResolver(
                userProvider,
                List.of(workflowBusinessContextPort),
                workflowApproverProperties(),
                flowDefinitionRepository
        );

        assertEquals("审批人", resolver.resolveApproverName(300L));
    }

    @Test
    void resolveApproverId_shouldUsePlanCreatorOrgForCollegeFinalApprovalStep() {
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setRoleId(2L);
        stepDef.setStepName("职能部门终审人审批");
        stepDef.setIsTerminal(true);

        AuditInstance instance = new AuditInstance();
        instance.setEntityType("PLAN");
        instance.setEntityId(7057L);
        instance.setFlowDefId(4L);

        com.sism.workflow.domain.definition.AuditFlowDef collegeFlow = new com.sism.workflow.domain.definition.AuditFlowDef();
        collegeFlow.setFlowCode("PLAN_APPROVAL_COLLEGE");
        when(flowDefinitionRepository.findById(4L)).thenReturn(java.util.Optional.of(collegeFlow));

        UserIdentity collegeApprover = new UserIdentity(370L, "u370", "College370", 57L, true);
        UserIdentity functionalApprover = new UserIdentity(267L, "u267", "Func267", 44L, true);

        when(workflowBusinessContextPort.getBusinessSummary("PLAN", 7057L))
                .thenReturn(Optional.of(new WorkflowBusinessContextPort.BusinessSummary(7057L, "Plan 7057", 44L, "教务处", 57L, "学院", "Plan 7057")));
        when(userProvider.findActiveIdentitiesByRole(2L)).thenReturn(List.of(collegeApprover, functionalApprover));

        ApproverResolver resolver = new ApproverResolver(
                userProvider,
                List.of(workflowBusinessContextPort),
                workflowApproverProperties(),
                flowDefinitionRepository
        );

        assertEquals(267L, resolver.resolveApproverId(stepDef, 188L, 57L, instance));
    }

    @Test
    void resolveApproverId_shouldUseTerminalMetadataBeforeLegacyStepNameForCollegeFinalApproval() {
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setRoleId(2L);
        stepDef.setStepName("任意终审节点");
        stepDef.setIsTerminal(true);

        AuditInstance instance = new AuditInstance();
        instance.setEntityType("PLAN");
        instance.setEntityId(8088L);
        instance.setFlowDefId(4L);

        com.sism.workflow.domain.definition.AuditFlowDef collegeFlow = new com.sism.workflow.domain.definition.AuditFlowDef();
        collegeFlow.setFlowCode("PLAN_APPROVAL_COLLEGE");
        when(flowDefinitionRepository.findById(4L)).thenReturn(java.util.Optional.of(collegeFlow));

        UserIdentity creatorOrgApprover = new UserIdentity(267L, "u267", "Func267", 44L, true);
        UserIdentity requesterOrgApprover = new UserIdentity(370L, "u370", "Req370", 57L, true);

        when(workflowBusinessContextPort.getBusinessSummary("PLAN", 8088L))
                .thenReturn(Optional.of(new WorkflowBusinessContextPort.BusinessSummary(8088L, "Plan 8088", 44L, "教务处", 57L, "学院", "Plan 8088")));
        when(userProvider.findActiveIdentitiesByRole(2L)).thenReturn(List.of(requesterOrgApprover, creatorOrgApprover));

        ApproverResolver resolver = new ApproverResolver(
                userProvider,
                List.of(workflowBusinessContextPort),
                workflowApproverProperties(),
                flowDefinitionRepository
        );

        assertEquals(267L, resolver.resolveApproverId(stepDef, 188L, 57L, instance));
    }

    // ---- Demo account scope filtering tests ----

    @Test
    void canUserApprove_demoUser_shouldStillRequireScopeMatch() {
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setRoleId(3L); // strategy dept head role (requires org 35)
        stepDef.setStepName("战略发展部负责人审批");

        // Demo user in org 44 (教务处), but needs to act as strategy dept head (org 35)
        UserIdentity demoUser = new UserIdentity(410L, "jiaowu_demo", "教务处[演示]", 44L, true, true);

        when(userProvider.findIdentity(410L)).thenReturn(Optional.of(demoUser));
        when(userProvider.getUserRoleIds(410L)).thenReturn(List.of(1L, 2L, 3L, 4L));

        ApproverResolver resolver = new ApproverResolver(
                userProvider,
                List.of(workflowBusinessContextPort),
                workflowApproverProperties(),
                flowDefinitionRepository
        );

        assertEquals(false, resolver.canUserApprove(stepDef, 410L, 44L));
    }

    @Test
    void canUserApprove_normalUser_shouldStillRequireScopeMatch() {
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setRoleId(3L); // strategy dept head role (requires org 35)
        stepDef.setStepName("战略发展部负责人审批");

        // Normal user in org 44 — should NOT match strategy dept head scope
        UserIdentity normalUser = new UserIdentity(223L, "jiaowu_report", "教务处填报人", 44L, true, false);

        when(userProvider.findIdentity(223L)).thenReturn(Optional.of(normalUser));
        when(userProvider.getUserRoleIds(223L)).thenReturn(List.of(1L, 3L));

        ApproverResolver resolver = new ApproverResolver(
                userProvider,
                List.of(workflowBusinessContextPort),
                workflowApproverProperties(),
                flowDefinitionRepository
        );

        assertEquals(false, resolver.canUserApprove(stepDef, 223L, 44L));
    }

    @Test
    void resolveCandidates_shouldExcludeOutOfScopeDemoUsers() {
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setRoleId(3L); // strategy dept head role
        stepDef.setStepName("战略发展部终审人审批");

        // Demo user in org 44, real strategy user in org 35
        UserIdentity demoUser = new UserIdentity(410L, "jiaowu_demo", "教务处[演示]", 44L, true, true);
        UserIdentity realStrategyUser = new UserIdentity(189L, "zlb_final1", "战略发展部负责人1", 35L, true, false);

        when(userProvider.findActiveIdentitiesByRole(3L)).thenReturn(List.of(demoUser, realStrategyUser));

        ApproverResolver resolver = new ApproverResolver(
                userProvider,
                List.of(workflowBusinessContextPort),
                workflowApproverProperties(),
                flowDefinitionRepository
        );

        assertEquals(
                List.of(189L),
                resolver.resolveCandidates(stepDef, 35L).stream().map(candidate -> candidate.getUserId()).toList()
        );
    }

    @Test
    void resolveAssignedApproverId_demoRequesterShouldNotApproveAcrossOrgScope() {
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setRoleId(3L);
        stepDef.setStepName("战略发展部终审人审批");

        UserIdentity demoUser = new UserIdentity(410L, "jiaowu_demo", "教务处[演示]", 44L, true, true);

        when(userProvider.findIdentity(410L)).thenReturn(Optional.of(demoUser));
        when(userProvider.getUserRoleIds(410L)).thenReturn(List.of(1L, 2L, 3L, 4L));

        ApproverResolver resolver = new ApproverResolver(
                userProvider,
                List.of(workflowBusinessContextPort),
                workflowApproverProperties(),
                flowDefinitionRepository
        );

        assertThrows(IllegalStateException.class, () -> resolver.resolveAssignedApproverId(stepDef, 410L, 44L, null));
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
