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

    // ---- N-03：学院上报链「职能部门审批人审批」的审批范围组织统一解析 ----

    /**
     * N-03（2026-10-30）：二级学院上报链「职能部门审批人审批」的席位归
     * 「该学院计划所属的职能部门」（业务摘要 sourceOrgId = plan.createdByOrgId），
     * 而不是上报人所在学院（requesterOrgId）。
     *
     * <p>本用例锁定「校领导型职能部门席位」（role 4）：旧口径只对 role 2 的
     * 「职能部门…审批」步骤做职能部门归一，role 4 的同名步骤会退回学院口径，
     * 于是职能部门账号被判「无权审批」→ 403 死锁。
     *
     * <p>断言：职能部门账号 canUserApprove = true，学院账号 canUserApprove = false，
     * 且派单（resolveApproverId）与候选预览（resolveCandidates）口径一致。
     */
    @Test
    void collegeChainFunctionalDeptApprovalStep_shouldScopeToFunctionalDeptNotCollege() {
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setId(14L);
        stepDef.setRoleId(4L);
        stepDef.setStepName("职能部门审批人审批");

        AuditInstance instance = new AuditInstance();
        instance.setEntityType("PLAN");
        instance.setEntityId(7057L);
        instance.setFlowDefId(4L);
        instance.setRequesterId(188L);
        instance.setRequesterOrgId(57L); // 上报人所在学院（计算机学院）

        com.sism.workflow.domain.definition.AuditFlowDef collegeFlow = new com.sism.workflow.domain.definition.AuditFlowDef();
        collegeFlow.setFlowCode("PLAN_APPROVAL_COLLEGE");
        when(flowDefinitionRepository.findById(4L)).thenReturn(java.util.Optional.of(collegeFlow));

        // 业务摘要：sourceOrgId = 计划创建组织（职能部门 44 教务处），targetOrgId = 学院 57
        when(workflowBusinessContextPort.getBusinessSummary("PLAN", 7057L))
                .thenReturn(Optional.of(new WorkflowBusinessContextPort.BusinessSummary(
                        7057L, "Plan 7057", 44L, "教务处", 57L, "计算机学院", "Plan 7057")));

        UserIdentity functionalLeader = new UserIdentity(326L, "jiaowu_leader", "教务处领导", 44L, true);
        UserIdentity collegeLeader = new UserIdentity(372L, "jisuanji_leader", "计算机学院领导", 57L, true);

        when(userProvider.findIdentity(326L)).thenReturn(Optional.of(functionalLeader));
        when(userProvider.getUserRoleIds(326L)).thenReturn(List.of(4L));
        when(userProvider.findIdentity(372L)).thenReturn(Optional.of(collegeLeader));
        when(userProvider.getUserRoleIds(372L)).thenReturn(List.of(4L));
        when(userProvider.findActiveIdentitiesByRole(4L))
                .thenReturn(List.of(collegeLeader, functionalLeader));

        ApproverResolver resolver = new ApproverResolver(
                userProvider,
                List.of(workflowBusinessContextPort),
                workflowApproverProperties(),
                flowDefinitionRepository
        );

        // 职能部门账号可审，学院账号不可审（否则就是「学院的人替职能部门点头」）
        assertEquals(true, resolver.canUserApprove(stepDef, 326L, instance));
        assertEquals(false, resolver.canUserApprove(stepDef, 372L, instance));

        // 统一入口：范围组织 = 职能部门本级（不是上报人所在学院）
        assertEquals(44L, resolver.resolveStepScopeOrgId(stepDef, instance));

        // 派单与候选预览同口径：都落在职能部门席位上
        assertEquals(326L, resolver.resolveApproverId(stepDef, 188L, instance.getRequesterOrgId(), instance));
        assertEquals(
                List.of(326L),
                resolver.resolveCandidates(stepDef, instance.getRequesterOrgId(), instance).stream()
                        .map(candidate -> candidate.getUserId())
                        .toList()
        );
    }

    /**
     * N-03：学院上报链第 3 环「分管职能部门负责人审批」（role 2，现网定义）
     * 同样必须按职能部门本级判定，且不能被学院账号顶替。
     */
    @Test
    void collegeChainFunctionalDeptHeadStep_shouldScopeToFunctionalDeptNotCollege() {
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setId(13L);
        stepDef.setRoleId(2L);
        stepDef.setStepName("分管职能部门负责人审批");

        AuditInstance instance = new AuditInstance();
        instance.setEntityType("PLAN_REPORT");
        instance.setEntityId(2L);
        instance.setFlowDefId(4L);
        instance.setRequesterId(370L);
        instance.setRequesterOrgId(57L);

        com.sism.workflow.domain.definition.AuditFlowDef collegeFlow = new com.sism.workflow.domain.definition.AuditFlowDef();
        collegeFlow.setFlowCode("PLAN_APPROVAL_COLLEGE");
        when(flowDefinitionRepository.findById(4L)).thenReturn(java.util.Optional.of(collegeFlow));

        when(workflowBusinessContextPort.getBusinessSummary("PLAN_REPORT", 2L))
                .thenReturn(Optional.of(new WorkflowBusinessContextPort.BusinessSummary(
                        403757L, "月报", 37L, "纪委办公室", 57L, "计算机学院", "月报")));

        UserIdentity functionalApprover = new UserIdentity(302L, "jiwei_audit1", "纪委办公室审核人1", 37L, true);
        UserIdentity collegeApprover = new UserIdentity(371L, "jisuanji_audit1", "计算机学院审核人1", 57L, true);

        when(userProvider.findIdentity(302L)).thenReturn(Optional.of(functionalApprover));
        when(userProvider.getUserRoleIds(302L)).thenReturn(List.of(2L));
        when(userProvider.findIdentity(371L)).thenReturn(Optional.of(collegeApprover));
        when(userProvider.getUserRoleIds(371L)).thenReturn(List.of(2L));

        ApproverResolver resolver = new ApproverResolver(
                userProvider,
                List.of(workflowBusinessContextPort),
                workflowApproverProperties(),
                flowDefinitionRepository
        );

        assertEquals(37L, resolver.resolveStepScopeOrgId(stepDef, instance));
        assertEquals(true, resolver.canUserApprove(stepDef, 302L, instance));
        assertEquals(false, resolver.canUserApprove(stepDef, 371L, instance));
    }

    /**
     * N-03：学院本级席位（「学院审核人审批」）不受职能部门口径影响，仍归学院自己。
     */
    @Test
    void collegeChainCollegeReviewerStep_shouldKeepCollegeScope() {
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setId(12L);
        stepDef.setRoleId(2L);
        stepDef.setStepName("学院审核人审批");

        AuditInstance instance = new AuditInstance();
        instance.setEntityType("PLAN");
        instance.setEntityId(7057L);
        instance.setFlowDefId(4L);
        instance.setRequesterId(188L);
        instance.setRequesterOrgId(57L);

        com.sism.workflow.domain.definition.AuditFlowDef collegeFlow = new com.sism.workflow.domain.definition.AuditFlowDef();
        collegeFlow.setFlowCode("PLAN_APPROVAL_COLLEGE");
        when(flowDefinitionRepository.findById(4L)).thenReturn(java.util.Optional.of(collegeFlow));

        UserIdentity collegeApprover = new UserIdentity(370L, "jsjxy_audit1", "计算机学院审核人", 57L, true);
        when(userProvider.findIdentity(370L)).thenReturn(Optional.of(collegeApprover));
        when(userProvider.getUserRoleIds(370L)).thenReturn(List.of(2L));

        ApproverResolver resolver = new ApproverResolver(
                userProvider,
                List.of(workflowBusinessContextPort),
                workflowApproverProperties(),
                flowDefinitionRepository
        );

        assertEquals(57L, resolver.resolveStepScopeOrgId(stepDef, instance));
        assertEquals(true, resolver.canUserApprove(stepDef, 370L, instance));
    }

    /**
     * N-03：职能部门上报链（PLAN_APPROVAL_FUNCDEPT）不受影响——
     * 该链的 requesterOrgId 本就是职能部门，范围组织仍按请求方组织解析。
     */
    @Test
    void functionalDeptChain_shouldKeepRequesterOrgScope() {
        AuditStepDef stepDef = new AuditStepDef();
        stepDef.setId(8L);
        stepDef.setRoleId(2L);
        stepDef.setStepName("职能部门审批人审批");

        AuditInstance instance = new AuditInstance();
        instance.setEntityType("PLAN");
        instance.setEntityId(4044L);
        instance.setFlowDefId(3L);
        instance.setRequesterId(188L);
        instance.setRequesterOrgId(44L); // 教务处自己发起

        com.sism.workflow.domain.definition.AuditFlowDef funcDeptFlow = new com.sism.workflow.domain.definition.AuditFlowDef();
        funcDeptFlow.setFlowCode("PLAN_APPROVAL_FUNCDEPT");
        when(flowDefinitionRepository.findById(3L)).thenReturn(java.util.Optional.of(funcDeptFlow));

        UserIdentity funcApprover = new UserIdentity(267L, "jiaowu_audit1", "教务处审核人", 44L, true);
        when(userProvider.findIdentity(267L)).thenReturn(Optional.of(funcApprover));
        when(userProvider.getUserRoleIds(267L)).thenReturn(List.of(2L));

        ApproverResolver resolver = new ApproverResolver(
                userProvider,
                List.of(workflowBusinessContextPort),
                workflowApproverProperties(),
                flowDefinitionRepository
        );

        assertEquals(44L, resolver.resolveStepScopeOrgId(stepDef, instance));
        assertEquals(true, resolver.canUserApprove(stepDef, 267L, instance));
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
