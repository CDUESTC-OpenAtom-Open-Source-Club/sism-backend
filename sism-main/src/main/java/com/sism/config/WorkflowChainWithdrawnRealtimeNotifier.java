package com.sism.config;

import com.sism.shared.domain.notification.WorkflowChainWithdrawnEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Objects;

/**
 * 2026-10-07 撤回实时通知：提交人撤回整条审批链后，把消息通过 WebSocket
 * 实时推给原待审批人（初级审批）。前端 websocket.ts 收到后按数据域
 * （plan/indicator）触发定向刷新——审批人界面立即收敛为「已撤回」，
 * 不再需要手动刷新页面；未连接 WebSocket 时由前端 30s 心跳兜底。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowChainWithdrawnRealtimeNotifier {

    private final WebSocketNotificationService webSocketNotificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWorkflowChainWithdrawn(WorkflowChainWithdrawnEvent event) {
        if (event == null
                || event.pendingApproverIds() == null
                || event.pendingApproverIds().isEmpty()) {
            return;
        }

        List<Long> recipients = event.pendingApproverIds().stream()
                .filter(Objects::nonNull)
                .filter(approverId -> !approverId.equals(event.requesterId()))
                .distinct()
                .toList();

        for (Long userId : recipients) {
            try {
                boolean delivered = webSocketNotificationService.sendToUser(
                        String.valueOf(userId),
                        "SYSTEM",
                        "审批链已撤回",
                        "提交人已撤回整条审批链，您当前的待审批节点已失效，页面状态已同步。",
                        event.entityType(),
                        event.entityId(),
                        event.workflowInstanceId(),
                        null
                );
                if (!delivered) {
                    log.info("[撤回实时通知] 用户 {} 未连接 WebSocket，由前端心跳兜底收敛", userId);
                }
            } catch (Exception ex) {
                log.warn("[撤回实时通知] 推送失败 userId={}", userId, ex);
            }
        }
    }
}
