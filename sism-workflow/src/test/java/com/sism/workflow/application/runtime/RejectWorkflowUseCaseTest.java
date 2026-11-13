package com.sism.workflow.application.runtime;

import com.sism.workflow.application.WorkflowBusinessStatusSyncService;
import com.sism.workflow.application.definition.WorkflowDefinitionQueryService;
import com.sism.workflow.application.support.ApproverResolver;
import com.sism.workflow.application.support.WorkflowEventDispatcher;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditStepInstance;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RejectWorkflowUseCaseTest {

    @Mock
    private AuditInstanceRepository auditInstanceRepository;

    @Mock
    private WorkflowEventDispatcher workflowEventDispatcher;

    @Mock
    private WorkflowBusinessStatusSyncService workflowBusinessStatusSyncService;

    @Mock
    private WorkflowDefinitionQueryService workflowDefinitionQueryService;

    @Mock
    private ApproverResolver approverResolver;

    @InjectMocks
    private RejectWorkflowUseCase rejectWorkflowUseCase;

    @Test
    void reject_shouldKeepInstanceInReviewWhenReturnedToSubmitStep() {
        AuditFlowDef flowDef = new AuditFlowDef();

        AuditStepDef submit = new AuditStepDef();
        submit.setId(11L);
        submit.setStepName("填报人提交");
        submit.setStepOrder(1);
        submit.setStepType(AuditStepDef.STEP_TYPE_SUBMIT);

        AuditStepDef dept = new AuditStepDef();
        dept.setId(12L);
        dept.setStepName("二级学院审批人审批");
        dept.setStepOrder(2);
        dept.setStepType(AuditStepDef.STEP_TYPE_APPROVAL);
        dept.setRoleId(2L);
        dept.setIsTerminal(true);

        flowDef.setSteps(List.of(submit, dept));

        AuditInstance instance = new AuditInstance();
        instance.setId(1L);
        instance.setFlowDefId(4L);
        instance.setRequesterId(100L);
        instance.setRequesterOrgId(57L);
        instance.setStatus(AuditInstance.STATUS_PENDING);

        AuditStepInstance submitInstance = new AuditStepInstance();
        submitInstance.setStepNo(1);
        submitInstance.setStepDefId(11L);
        submitInstance.setStepName("填报人提交");
        submitInstance.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        submitInstance.setApproverId(100L);
        submitInstance.setApproverOrgId(57L);

        AuditStepInstance deptInstance = new AuditStepInstance();
        deptInstance.setStepNo(2);
        deptInstance.setStepDefId(12L);
        deptInstance.setStepName("二级学院审批人审批");
        deptInstance.setStatus(AuditInstance.STEP_STATUS_PENDING);
        deptInstance.setApproverId(300L);
        deptInstance.setApproverOrgId(57L);

        instance.addStepInstance(submitInstance);
        instance.addStepInstance(deptInstance);

        when(workflowDefinitionQueryService.getAuditFlowDefById(4L)).thenReturn(flowDef);
        when(approverResolver.resolveAssignedApproverId(submit, 100L, 57L, instance)).thenReturn(100L);
        when(auditInstanceRepository.save(any(AuditInstance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuditInstance saved = rejectWorkflowUseCase.reject(instance, 300L, "打回");

        assertEquals(AuditInstance.STATUS_WITHDRAWN, saved.getStatus());
        assertEquals(3, saved.getStepInstances().size());
        assertEquals(AuditInstance.STEP_STATUS_REJECTED, saved.getStepInstances().get(1).getStatus());
        assertEquals(AuditInstance.STEP_STATUS_WITHDRAWN, saved.getStepInstances().get(2).getStatus());
        assertEquals(11L, saved.getStepInstances().get(2).getStepDefId());
        assertEquals(3, saved.getStepInstances().get(2).getStepNo());
        assertEquals(100L, saved.getStepInstances().get(2).getApproverId());
        assertEquals("驳回后退回填报人重新提交", saved.getStepInstances().get(2).getComment());
    }

    @Test
    void reject_shouldMarkInstanceRejectedWhenRejectingTerminalFirstStep() {
        AuditFlowDef flowDef = new AuditFlowDef();

        AuditStepDef submit = new AuditStepDef();
        submit.setId(11L);
        submit.setStepName("填报人提交");
        submit.setStepOrder(1);
        submit.setStepType(AuditStepDef.STEP_TYPE_SUBMIT);
        submit.setIsTerminal(true);

        flowDef.setSteps(List.of(submit));

        AuditInstance instance = new AuditInstance();
        instance.setId(2L);
        instance.setFlowDefId(5L);
        instance.setRequesterId(100L);
        instance.setRequesterOrgId(57L);
        instance.setStatus(AuditInstance.STATUS_PENDING);

        AuditStepInstance submitInstance = new AuditStepInstance();
        submitInstance.setStepNo(1);
        submitInstance.setStepDefId(11L);
        submitInstance.setStepName("填报人提交");
        submitInstance.setStatus(AuditInstance.STEP_STATUS_PENDING);
        submitInstance.setApproverOrgId(57L);

        instance.addStepInstance(submitInstance);

        when(workflowDefinitionQueryService.getAuditFlowDefById(5L)).thenReturn(flowDef);
        when(auditInstanceRepository.save(any(AuditInstance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuditInstance saved = rejectWorkflowUseCase.reject(instance, 100L, "终止");

        assertEquals(AuditInstance.STATUS_REJECTED, saved.getStatus());
        assertEquals(1, saved.getStepInstances().size());
        assertEquals(AuditInstance.STEP_STATUS_REJECTED, saved.getStepInstances().get(0).getStatus());
    }

    @Test
    void reject_shouldAssignReturnedApprovalStepToDemoRequesterWhenEligible() {
        AuditFlowDef flowDef = new AuditFlowDef();

        AuditStepDef submit = new AuditStepDef();
        submit.setId(21L);
        submit.setStepName("填报人提交");
        submit.setStepOrder(1);
        submit.setStepType(AuditStepDef.STEP_TYPE_SUBMIT);

        AuditStepDef dept = new AuditStepDef();
        dept.setId(22L);
        dept.setStepName("职能部门审批人审批");
        dept.setStepOrder(2);
        dept.setStepType(AuditStepDef.STEP_TYPE_APPROVAL);
        dept.setRoleId(2L);

        AuditStepDef strategy = new AuditStepDef();
        strategy.setId(23L);
        strategy.setStepName("战略发展部终审人审批");
        strategy.setStepOrder(3);
        strategy.setStepType(AuditStepDef.STEP_TYPE_APPROVAL);
        strategy.setRoleId(3L);

        flowDef.setSteps(List.of(submit, dept, strategy));

        AuditInstance instance = new AuditInstance();
        instance.setId(3L);
        instance.setFlowDefId(9L);
        instance.setRequesterId(410L);
        instance.setRequesterOrgId(44L);
        instance.setStatus(AuditInstance.STATUS_PENDING);

        AuditStepInstance submitInstance = new AuditStepInstance();
        submitInstance.setStepNo(1);
        submitInstance.setStepDefId(21L);
        submitInstance.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        submitInstance.setApproverId(410L);
        submitInstance.setApproverOrgId(44L);

        AuditStepInstance deptInstance = new AuditStepInstance();
        deptInstance.setStepNo(2);
        deptInstance.setStepDefId(22L);
        deptInstance.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        deptInstance.setApproverId(410L);
        deptInstance.setApproverOrgId(44L);

        AuditStepInstance strategyInstance = new AuditStepInstance();
        strategyInstance.setStepNo(3);
        strategyInstance.setStepDefId(23L);
        strategyInstance.setStatus(AuditInstance.STEP_STATUS_PENDING);
        strategyInstance.setApproverId(189L);
        strategyInstance.setApproverOrgId(35L);

        instance.addStepInstance(submitInstance);
        instance.addStepInstance(deptInstance);
        instance.addStepInstance(strategyInstance);

        when(workflowDefinitionQueryService.getAuditFlowDefById(9L)).thenReturn(flowDef);
        when(approverResolver.resolveAssignedApproverId(dept, 410L, 44L, instance)).thenReturn(410L);
        when(approverResolver.resolveApproverOrgId(dept, 44L, instance)).thenReturn(44L);
        when(auditInstanceRepository.save(any(AuditInstance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuditInstance saved = rejectWorkflowUseCase.reject(instance, 189L, "退回重审");

        assertEquals(AuditInstance.STATUS_PENDING, saved.getStatus());
        assertEquals(4, saved.getStepInstances().size());
        assertEquals(22L, saved.getStepInstances().get(3).getStepDefId());
        assertEquals(410L, saved.getStepInstances().get(3).getApproverId());
        assertEquals(44L, saved.getStepInstances().get(3).getApproverOrgId());
    }

    /**
     * HIGH①（2026-11-12 实例 #5 实测复现）：学院上报链（PLAN_APPROVAL_COLLEGE，工学院式五节点
     * 填报人提交→学院审核人→归口负责人→归口校领导→战略部终审）从终审逐级驳回，
     * 回退生成的「学院审核人审批」步骤 approver_org 必须按席位规则重解为学院 org（57），
     * 不能错继承归口部门 org（44）——否则学院审核人收不到待办、链卡死。
     * 同时回归：归口节点（归口校领导/归口负责人）回退后 org 维持归口 44 不变。
     */
    @Test
    void reject_shouldReResolveCollegeReviewerOrgBySeatRuleWhenRolledBackFromFunctionalSteps() {
        AuditFlowDef flowDef = new AuditFlowDef();

        AuditStepDef submit = new AuditStepDef();
        submit.setId(101L);
        submit.setStepName("填报人提交");
        submit.setStepOrder(1);
        submit.setStepType(AuditStepDef.STEP_TYPE_SUBMIT);

        AuditStepDef collegeReviewer = new AuditStepDef();
        collegeReviewer.setId(102L);
        collegeReviewer.setStepName("学院审核人审批");
        collegeReviewer.setStepOrder(2);
        collegeReviewer.setStepType(AuditStepDef.STEP_TYPE_APPROVAL);
        collegeReviewer.setRoleId(2L);

        AuditStepDef functionalHead = new AuditStepDef();
        functionalHead.setId(103L);
        functionalHead.setStepName("职能部门审批人审批");
        functionalHead.setStepOrder(3);
        functionalHead.setStepType(AuditStepDef.STEP_TYPE_APPROVAL);
        functionalHead.setRoleId(2L);

        AuditStepDef functionalVicePresident = new AuditStepDef();
        functionalVicePresident.setId(104L);
        functionalVicePresident.setStepName("分管职能部门校领导审批");
        functionalVicePresident.setStepOrder(4);
        functionalVicePresident.setStepType(AuditStepDef.STEP_TYPE_APPROVAL);
        functionalVicePresident.setRoleId(4L);

        AuditStepDef strategyFinal = new AuditStepDef();
        strategyFinal.setId(105L);
        strategyFinal.setStepName("职能部门终审");
        strategyFinal.setStepOrder(5);
        strategyFinal.setStepType(AuditStepDef.STEP_TYPE_APPROVAL);
        strategyFinal.setRoleId(3L);
        strategyFinal.setIsTerminal(true);

        flowDef.setSteps(List.of(submit, collegeReviewer, functionalHead, functionalVicePresident, strategyFinal));

        AuditInstance instance = new AuditInstance();
        instance.setId(10L);
        instance.setFlowDefId(100L);
        instance.setRequesterId(200L);
        instance.setRequesterOrgId(57L);
        instance.setStatus(AuditInstance.STATUS_PENDING);

        AuditStepInstance submitInstance = new AuditStepInstance();
        submitInstance.setStepNo(1);
        submitInstance.setStepDefId(101L);
        submitInstance.setStepName("填报人提交");
        submitInstance.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        submitInstance.setApproverId(200L);
        submitInstance.setApproverOrgId(57L);

        AuditStepInstance collegeReviewerInstance = new AuditStepInstance();
        collegeReviewerInstance.setStepNo(2);
        collegeReviewerInstance.setStepDefId(102L);
        collegeReviewerInstance.setStepName("学院审核人审批");
        collegeReviewerInstance.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        collegeReviewerInstance.setApproverId(210L);
        collegeReviewerInstance.setApproverOrgId(57L);

        AuditStepInstance functionalHeadInstance = new AuditStepInstance();
        functionalHeadInstance.setStepNo(3);
        functionalHeadInstance.setStepDefId(103L);
        functionalHeadInstance.setStepName("职能部门审批人审批");
        functionalHeadInstance.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        functionalHeadInstance.setApproverId(310L);
        functionalHeadInstance.setApproverOrgId(44L);

        AuditStepInstance functionalVicePresidentInstance = new AuditStepInstance();
        functionalVicePresidentInstance.setStepNo(4);
        functionalVicePresidentInstance.setStepDefId(104L);
        functionalVicePresidentInstance.setStepName("分管职能部门校领导审批");
        functionalVicePresidentInstance.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        functionalVicePresidentInstance.setApproverId(320L);
        functionalVicePresidentInstance.setApproverOrgId(44L);

        AuditStepInstance strategyFinalInstance = new AuditStepInstance();
        strategyFinalInstance.setStepNo(5);
        strategyFinalInstance.setStepDefId(105L);
        strategyFinalInstance.setStepName("职能部门终审");
        strategyFinalInstance.setStatus(AuditInstance.STEP_STATUS_PENDING);
        strategyFinalInstance.setApproverId(189L);
        strategyFinalInstance.setApproverOrgId(35L);

        instance.addStepInstance(submitInstance);
        instance.addStepInstance(collegeReviewerInstance);
        instance.addStepInstance(functionalHeadInstance);
        instance.addStepInstance(functionalVicePresidentInstance);
        instance.addStepInstance(strategyFinalInstance);

        when(workflowDefinitionQueryService.getAuditFlowDefById(100L)).thenReturn(flowDef);
        // 回退节点席位重解一律以实例请求方组织（57 学院）为 context——
        // stub 按精确参数绑定，若实现继续沿用「最后已批步骤 org（44）」这些 stub 不会命中。
        when(approverResolver.resolveAssignedApproverId(functionalVicePresident, 200L, 57L, instance)).thenReturn(320L);
        when(approverResolver.resolveApproverOrgId(functionalVicePresident, 57L, instance)).thenReturn(44L);
        when(approverResolver.resolveAssignedApproverId(functionalHead, 200L, 57L, instance)).thenReturn(310L);
        when(approverResolver.resolveApproverOrgId(functionalHead, 57L, instance)).thenReturn(44L);
        when(approverResolver.resolveAssignedApproverId(collegeReviewer, 200L, 57L, instance)).thenReturn(210L);
        when(approverResolver.resolveApproverOrgId(collegeReviewer, 57L, instance)).thenReturn(57L);
        when(auditInstanceRepository.save(any(AuditInstance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 终审驳回 → 回退归口校领导
        rejectWorkflowUseCase.reject(instance, 189L, "终审驳回");
        // 归口校领导驳回 → 回退归口负责人
        rejectWorkflowUseCase.reject(instance, 320L, "校领导驳回");
        // 归口负责人驳回 → 回退学院审核人
        AuditInstance saved = rejectWorkflowUseCase.reject(instance, 310L, "归口负责人驳回");

        assertEquals(AuditInstance.STATUS_PENDING, saved.getStatus());
        assertEquals(8, saved.getStepInstances().size());

        // 回归：归口节点回退后 org 维持归口 44
        AuditStepInstance vicePresidentReturned = saved.getStepInstances().get(5);
        assertEquals(104L, vicePresidentReturned.getStepDefId());
        assertEquals(44L, vicePresidentReturned.getApproverOrgId());
        AuditStepInstance headReturned = saved.getStepInstances().get(6);
        assertEquals(103L, headReturned.getStepDefId());
        assertEquals(44L, headReturned.getApproverOrgId());

        // 修复点：学院审核人节点回退后按席位规则重解为学院 org 57
        AuditStepInstance collegeReviewerReturned = saved.getStepInstances().get(7);
        assertEquals(102L, collegeReviewerReturned.getStepDefId());
        assertEquals(AuditInstance.STEP_STATUS_PENDING, collegeReviewerReturned.getStatus());
        assertEquals(210L, collegeReviewerReturned.getApproverId());
        assertEquals(57L, collegeReviewerReturned.getApproverOrgId());
    }

    /**
     * 回归（实例 #6 口径）：同部门链内驳回回退，回退步骤 org 维持本部门（37）不变。
     */
    @Test
    void reject_shouldKeepSameDeptOrgWhenRolledBackWithinDeptChain() {
        AuditFlowDef flowDef = new AuditFlowDef();

        AuditStepDef submit = new AuditStepDef();
        submit.setId(201L);
        submit.setStepName("填报人提交");
        submit.setStepOrder(1);
        submit.setStepType(AuditStepDef.STEP_TYPE_SUBMIT);

        AuditStepDef deptApprover = new AuditStepDef();
        deptApprover.setId(202L);
        deptApprover.setStepName("部门负责人审批");
        deptApprover.setStepOrder(2);
        deptApprover.setStepType(AuditStepDef.STEP_TYPE_APPROVAL);
        deptApprover.setRoleId(2L);

        AuditStepDef strategyFinal = new AuditStepDef();
        strategyFinal.setId(203L);
        strategyFinal.setStepName("战略发展部终审人审批");
        strategyFinal.setStepOrder(3);
        strategyFinal.setStepType(AuditStepDef.STEP_TYPE_APPROVAL);
        strategyFinal.setRoleId(3L);

        flowDef.setSteps(List.of(submit, deptApprover, strategyFinal));

        AuditInstance instance = new AuditInstance();
        instance.setId(20L);
        instance.setFlowDefId(200L);
        instance.setRequesterId(400L);
        instance.setRequesterOrgId(37L);
        instance.setStatus(AuditInstance.STATUS_PENDING);

        AuditStepInstance submitInstance = new AuditStepInstance();
        submitInstance.setStepNo(1);
        submitInstance.setStepDefId(201L);
        submitInstance.setStepName("填报人提交");
        submitInstance.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        submitInstance.setApproverId(400L);
        submitInstance.setApproverOrgId(37L);

        AuditStepInstance deptApproverInstance = new AuditStepInstance();
        deptApproverInstance.setStepNo(2);
        deptApproverInstance.setStepDefId(202L);
        deptApproverInstance.setStepName("部门负责人审批");
        deptApproverInstance.setStatus(AuditInstance.STEP_STATUS_APPROVED);
        deptApproverInstance.setApproverId(410L);
        deptApproverInstance.setApproverOrgId(37L);

        AuditStepInstance strategyFinalInstance = new AuditStepInstance();
        strategyFinalInstance.setStepNo(3);
        strategyFinalInstance.setStepDefId(203L);
        strategyFinalInstance.setStepName("战略发展部终审人审批");
        strategyFinalInstance.setStatus(AuditInstance.STEP_STATUS_PENDING);
        strategyFinalInstance.setApproverId(189L);
        strategyFinalInstance.setApproverOrgId(35L);

        instance.addStepInstance(submitInstance);
        instance.addStepInstance(deptApproverInstance);
        instance.addStepInstance(strategyFinalInstance);

        when(workflowDefinitionQueryService.getAuditFlowDefById(200L)).thenReturn(flowDef);
        when(approverResolver.resolveAssignedApproverId(deptApprover, 400L, 37L, instance)).thenReturn(410L);
        when(approverResolver.resolveApproverOrgId(deptApprover, 37L, instance)).thenReturn(37L);
        when(auditInstanceRepository.save(any(AuditInstance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuditInstance saved = rejectWorkflowUseCase.reject(instance, 189L, "退回重审");

        assertEquals(AuditInstance.STATUS_PENDING, saved.getStatus());
        assertEquals(4, saved.getStepInstances().size());
        AuditStepInstance deptReturned = saved.getStepInstances().get(3);
        assertEquals(202L, deptReturned.getStepDefId());
        assertEquals(410L, deptReturned.getApproverId());
        assertEquals(37L, deptReturned.getApproverOrgId());
    }
}
