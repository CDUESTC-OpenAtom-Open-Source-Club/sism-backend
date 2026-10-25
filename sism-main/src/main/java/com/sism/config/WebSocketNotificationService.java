package com.sism.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Minimal service used to push real-time notification payloads to frontend clients.
 */
@Service
@RequiredArgsConstructor
public class WebSocketNotificationService {

    private final SismWebSocketHandler webSocketHandler;

    public boolean sendToUser(String userId,
                              String type,
                              String title,
                              String content,
                              String entityType,
                              Long entityId,
                              Long approvalInstanceId,
                              String stepName) {
        return webSocketHandler.sendToUser(userId, new SismWebSocketHandler.NotificationMessagePayload(
                type,
                title,
                content,
                entityType,
                entityId,
                approvalInstanceId,
                stepName,
                Instant.now().toString()
        ));
    }

    /**
     * 2026-10-24 跨窗口数据同步：广播数据变更事件（type=DATA_CHANGED）。
     * 仅携带实体类型与 ID，前端据此做定向局部刷新；fail-safe，绝不抛出。
     */
    public boolean broadcastDataChanged(String entityType, Long entityId) {
        if (entityType == null || entityType.isBlank() || entityId == null) {
            return false;
        }
        try {
            return webSocketHandler.sendToAll(new SismWebSocketHandler.NotificationMessagePayload(
                    "DATA_CHANGED",
                    "",
                    "",
                    entityType,
                    entityId,
                    null,
                    null,
                    Instant.now().toString()
            )) > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
