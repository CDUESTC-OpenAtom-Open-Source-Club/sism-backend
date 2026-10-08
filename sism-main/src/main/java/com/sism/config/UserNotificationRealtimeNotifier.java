package com.sism.config;

import com.sism.shared.domain.notification.UserNotificationRealtimePushEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 2026-10-07 消息通知实时推送：审批提交/通过/驳回在消息中心落库后，
 * 把同一条消息通过 WebSocket 实时推给接收人。前端 websocket.ts 收到后
 * 弹出提示、刷新铃铛，并按数据域（plan/indicator）触发定向刷新——
 * 「处理审批/查看审批」按钮与审批状态标签秒级收敛，无需手动刷新页面。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserNotificationRealtimeNotifier {

    private final WebSocketNotificationService webSocketNotificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserNotificationCreated(UserNotificationRealtimePushEvent event) {
        if (event == null || event.recipientUserId() == null) {
            return;
        }

        // 操作者本人已通过本地刷新看到结果，不重复打扰
        if (event.senderUserId() != null && event.senderUserId().equals(event.recipientUserId())) {
            return;
        }

        try {
            boolean delivered = webSocketNotificationService.sendToUser(
                    String.valueOf(event.recipientUserId()),
                    event.type(),
                    event.title(),
                    event.content(),
                    event.entityType(),
                    event.entityId(),
                    event.approvalInstanceId(),
                    event.stepName()
            );
            if (!delivered) {
                log.info("[通知实时推送] 用户 {} 未连接 WebSocket，由前端心跳兜底收敛", event.recipientUserId());
            }
        } catch (Exception ex) {
            log.warn("[通知实时推送] 推送失败 recipientUserId={}", event.recipientUserId(), ex);
        }
    }
}
