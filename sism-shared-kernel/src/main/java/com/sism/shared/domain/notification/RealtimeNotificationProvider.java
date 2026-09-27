package com.sism.shared.domain.notification;

/**
 * Cross-context port for publishing a persisted notification to online clients.
 * Implementations must be fail-safe: realtime delivery cannot fail the business transaction.
 */
public interface RealtimeNotificationProvider {

    void publish(
            Long recipientUserId,
            String type,
            String title,
            String content,
            String entityType,
            Long entityId,
            Long approvalInstanceId,
            String stepName
    );
}
