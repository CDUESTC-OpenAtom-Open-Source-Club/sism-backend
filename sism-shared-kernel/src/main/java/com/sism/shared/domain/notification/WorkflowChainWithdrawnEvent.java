package com.sism.shared.domain.notification;

import com.sism.shared.domain.model.base.DomainEvent;

import java.util.List;

/**
 * 审批链被提交人撤回/取消后的实时通知事件（2026-10-07）。
 *
 * <p>背景：撤回动作原先只翻转审批实例状态，不产生任何领域事件，
 * 后端也没有向 WebSocket 推送业务消息——导致原待审批人（初级审批）
 * 的界面停留在旧状态，必须手动刷新才能看到「已撤回」。</p>
 *
 * <p>发布方：sism-workflow（上报链取消 CancelWorkflowUseCase）、
 * sism-strategy（计划撤回 PlanWorkflowRuntimeService）。
 * 监听方：sism-main 在事务提交后通过 WebSocketNotificationService
 * 把消息推给原待审批人，前端按数据域（plan/indicator）定向刷新。</p>
 */
public record WorkflowChainWithdrawnEvent(
        Long workflowInstanceId,
        String entityType,
        Long entityId,
        Long requesterId,
        List<Long> pendingApproverIds
) implements DomainEvent {

    @Override
    public String getEventType() {
        return "WORKFLOW_CHAIN_WITHDRAWN";
    }
}
