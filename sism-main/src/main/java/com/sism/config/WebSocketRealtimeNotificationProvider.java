package com.sism.config;

import com.sism.shared.domain.notification.RealtimeNotificationProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Bridges persisted IAM notifications to the per-user WebSocket channel. */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketRealtimeNotificationProvider implements RealtimeNotificationProvider {

    private final WebSocketNotificationService webSocketNotificationService;

    @Override
    public void publish(
            Long recipientUserId,
            String type,
            String title,
            String content,
            String entityType,
            Long entityId,
            Long approvalInstanceId,
            String stepName
    ) {
        if (recipientUserId == null) {
            return;
        }
        try {
            webSocketNotificationService.sendToUser(
                    String.valueOf(recipientUserId),
                    type,
                    title,
                    content,
                    entityType,
                    entityId,
                    approvalInstanceId,
                    stepName
            );
        } catch (Exception ex) {
            // Realtime delivery is best effort and must never roll back a business write.
            log.warn("实时通知推送失败: userId={}, type={}, entityType={}, entityId={}, reason={}",
                    recipientUserId, type, entityType, entityId, ex.getMessage());
        }
    }
}
