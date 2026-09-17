package com.sism.workflow.domain;

import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.definition.AuditStepDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalWorkflowTemplateMetadataTest {

    @Test
    void planDispatchStrategyTemplateShouldKeepExplicitMetadata() {
        AuditFlowDef flowDef = flow(
                "PLAN_DISPATCH_STRATEGY",
                step(1, "填报人提交", AuditStepDef.STEP_TYPE_SUBMIT, null, false),
                step(2, "战略发展部负责人审批", AuditStepDef.STEP_TYPE_APPROVAL, 3L, false),
                step(3, "分管校领导审批", AuditStepDef.STEP_TYPE_APPROVAL, 4L, true)
        );

        assertDoesNotThrow(flowDef::validate);
        assertExplicitMetadata(flowDef, 3);
        assertTerminalApprovalCount(flowDef, 1);
    }

    @Test
    void planDispatchFuncDeptTemplateShouldKeepExplicitMetadata() {
        AuditFlowDef flowDef = flow(
                "PLAN_DISPATCH_FUNCDEPT",
                step(1, "填报人提交", AuditStepDef.STEP_TYPE_SUBMIT, null, false),
                step(2, "职能部门审批人审批", AuditStepDef.STEP_TYPE_APPROVAL, 2L, false),
                step(3, "分管校领导审批", AuditStepDef.STEP_TYPE_APPROVAL, 4L, true)
        );

        assertDoesNotThrow(flowDef::validate);
        assertExplicitMetadata(flowDef, 3);
        assertTerminalApprovalCount(flowDef, 1);
    }

    @Test
    void planApprovalFuncDeptTemplateShouldKeepExplicitMetadata() {
        AuditFlowDef flowDef = flow(
                "PLAN_APPROVAL_FUNCDEPT",
                step(1, "填报人提交", AuditStepDef.STEP_TYPE_SUBMIT, null, false),
                step(2, "职能部门审批人审批", AuditStepDef.STEP_TYPE_APPROVAL, 2L, false),
                step(3, "分管校领导审批", AuditStepDef.STEP_TYPE_APPROVAL, 4L, false),
                step(4, "战略发展部终审人审批", AuditStepDef.STEP_TYPE_APPROVAL, 3L, true)
        );

        assertDoesNotThrow(flowDef::validate);
        assertExplicitMetadata(flowDef, 4);
        assertTerminalApprovalCount(flowDef, 1);
    }

    @Test
    void planApprovalCollegeTemplateShouldKeepExplicitMetadata() {
        // 2026-09-17 定案：学院链 5 节点（学院多一级），终审为战略发展部
        AuditFlowDef flowDef = flow(
                "PLAN_APPROVAL_COLLEGE",
                step(1, "填报人提交", AuditStepDef.STEP_TYPE_SUBMIT, null, false),
                step(2, "二级学院审批人审批", AuditStepDef.STEP_TYPE_APPROVAL, 2L, false),
                step(3, "学院院长审批人审批", AuditStepDef.STEP_TYPE_APPROVAL, 4L, false),
                step(4, "职能部门审批人审批", AuditStepDef.STEP_TYPE_APPROVAL, 2L, false),
                step(5, "战略发展部终审人审批", AuditStepDef.STEP_TYPE_APPROVAL, 3L, true)
        );

        assertDoesNotThrow(flowDef::validate);
        assertExplicitMetadata(flowDef, 5);
        assertTerminalApprovalCount(flowDef, 1);
    }

    private AuditFlowDef flow(String code, AuditStepDef... steps) {
        AuditFlowDef flowDef = new AuditFlowDef();
        flowDef.setFlowCode(code);
        flowDef.setFlowName(code);
        flowDef.setEntityType("PLAN");
        List.of(steps).forEach(flowDef::addStep);
        return flowDef;
    }

    private AuditStepDef step(int order, String name, String type, Long roleId, boolean isTerminal) {
        AuditStepDef step = new AuditStepDef();
        step.setStepOrder(order);
        step.setStepName(name);
        step.setStepType(type);
        step.setRoleId(roleId);
        step.setIsTerminal(isTerminal);
        return step;
    }

    private void assertExplicitMetadata(AuditFlowDef flowDef, int expectedSteps) {
        assertEquals(expectedSteps, flowDef.getSteps().size());
        flowDef.getSteps().forEach(step -> {
            assertTrue(step.hasExplicitStepType(), "step_type must be explicit for " + step.getStepName());
            if (step.isSubmitStep()) {
                assertFalse(Boolean.TRUE.equals(step.getIsTerminal()), "submit step must not be terminal: " + step.getStepName());
                assertEquals(null, step.getRoleId(), "submit step must not keep role assignment: " + step.getStepName());
            }
        });
    }

    private void assertTerminalApprovalCount(AuditFlowDef flowDef, int expectedCount) {
        long count = flowDef.getSteps().stream()
                .filter(AuditStepDef::isApprovalStep)
                .filter(step -> Boolean.TRUE.equals(step.getIsTerminal()))
                .count();
        assertEquals(expectedCount, count);
    }
}
