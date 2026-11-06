package com.sism.strategy.interfaces.rest;

import com.sism.shared.application.dto.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyControllerSecurityTest {

    @Test
    void planControllerMutatingEndpointsShouldRequireRoleChecks() throws Exception {
        assertPreAuthorize(PlanController.class, "updatePlan", Long.class, com.sism.strategy.interfaces.dto.UpdatePlanRequest.class);
        assertPreAuthorize(PlanController.class, "publishPlan", Long.class);
        assertPreAuthorize(PlanController.class, "archivePlan", Long.class);
        assertPreAuthorize(PlanController.class, "submitPlanForApproval", Long.class, com.sism.strategy.interfaces.dto.SubmitPlanApprovalRequest.class, CurrentUser.class);
        assertPreAuthorize(PlanController.class, "submitPlanForDispatchApproval", Long.class, com.sism.strategy.interfaces.dto.SubmitPlanApprovalRequest.class, CurrentUser.class);
        assertPreAuthorize(PlanController.class, "withdrawPlan", Long.class);
    }

    @Test
    void cycleControllerMutatingEndpointsShouldRequireRoleChecks() throws Exception {
        assertPreAuthorize(CycleController.class, "createCycle", com.sism.strategy.interfaces.dto.CreateCycleRequest.class);
        assertPreAuthorize(CycleController.class, "activateCycle", Long.class);
        assertPreAuthorize(CycleController.class, "deactivateCycle", Long.class);
        assertPreAuthorize(CycleController.class, "deleteCycle", Long.class);
    }

    @Test
    void indicatorControllerMutatingEndpointsShouldRequireRoleChecks() throws Exception {
        // 2026-11-05（R6-R9）：这些写入口新增了 @AuthenticationPrincipal CurrentUser 参数
        // （组织维度授权判定需要操作者所属组织），反射签名同步更新。
        assertPreAuthorize(IndicatorController.class, "updateIndicator", Long.class, IndicatorController.UpdateIndicatorRequest.class, com.sism.shared.application.dto.CurrentUser.class);
        assertPreAuthorize(IndicatorController.class, "submitForReview", Long.class);
        assertPreAuthorize(IndicatorController.class, "submitForReviewAlias", Long.class);
        assertPreAuthorize(IndicatorController.class, "approveIndicator", Long.class);
        assertPreAuthorize(IndicatorController.class, "approveIndicatorAlias", Long.class);
        assertPreAuthorize(IndicatorController.class, "rejectIndicator", Long.class, IndicatorController.RejectRequest.class);
        assertPreAuthorize(IndicatorController.class, "rejectIndicatorAlias", Long.class, IndicatorController.RejectRequest.class);
        assertPreAuthorize(IndicatorController.class, "withdrawIndicator", Long.class, IndicatorController.WithdrawRequest.class, com.sism.shared.application.dto.CurrentUser.class);
        assertPreAuthorize(IndicatorController.class, "batchWithdrawIndicators", IndicatorController.BatchWithdrawRequest.class, com.sism.shared.application.dto.CurrentUser.class);
        assertPreAuthorize(IndicatorController.class, "distributeIndicator", Long.class, Long.class, String.class, IndicatorController.DistributeIndicatorRequest.class, com.sism.shared.application.dto.CurrentUser.class);
        assertPreAuthorize(IndicatorController.class, "breakdownIndicator", Long.class);
        assertPreAuthorize(IndicatorController.class, "activateIndicator", Long.class);
        assertPreAuthorize(IndicatorController.class, "terminateIndicator", Long.class, IndicatorController.TerminateRequest.class);
    }

    private static void assertPreAuthorize(Class<?> controllerType, String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = controllerType.getDeclaredMethod(methodName, parameterTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertNotNull(annotation, controllerType.getSimpleName() + "." + methodName + " should be protected");
        assertTrue(annotation.value().contains("hasAnyRole"),
                controllerType.getSimpleName() + "." + methodName + " should use role-based access");
    }
}
