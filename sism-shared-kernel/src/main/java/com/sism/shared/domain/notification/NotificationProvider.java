package com.sism.shared.domain.notification;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Shared notification capability contract for cross-context use cases.
 */
public interface NotificationProvider {

    record ApprovalResultNotification(
            Long notificationId,
            Long recipientUserId,
            Long approvalInstanceId,
            String entityType,
            Long entityId,
            String notificationType,
            LocalDateTime createdAt
    ) {}

    ApprovalResultNotification createApprovalResultNotification(
            Long recipientUserId,
            Long senderUserId,
            Long senderOrgId,
            Long approvalInstanceId,
            String entityType,
            Long entityId,
            String businessName,
            String stepName,
            boolean approved,
            String comment
    );

    /**
     * 2026-10-10 跨窗口实时修复：中间节点审批通过后，通知下一环节全部候选审批人
     * （站内信 + WebSocket 实时推送）。此前中间节点推进不通知，下游窗口只能等心跳兜底。
     */
    ApprovalResultNotification createFlowAdvancedNotification(
            Long recipientUserId,
            Long senderUserId,
            Long senderOrgId,
            Long approvalInstanceId,
            String entityType,
            Long entityId,
            String businessName,
            String nextStepName,
            String approverName
    );

    /**
     * 2026-10-10 跨窗口实时修复：中间节点审批通过后，通知提交人流程已推进
     * （type=APPROVAL_NEXT_STEP），让提交人窗口秒级看到"待下一环节审批"。
     */
    ApprovalResultNotification createStepAdvancedNotification(
            Long recipientUserId,
            Long senderUserId,
            Long senderOrgId,
            Long approvalInstanceId,
            String entityType,
            Long entityId,
            String businessName,
            String nextStepName,
            String approverName
    );

    record AlertNotification(
            Long notificationId,
            Long recipientUserId,
            Long alertId,
            Long indicatorId,
            String notificationType,
            LocalDateTime createdAt
    ) {}

    /**
     * H2（2026-09-19）：告警进入已解决状态时，关闭该告警历史推送的未读「待审批」通知，
     * 避免催办指向已审完/已过时的状态。
     */
    void deleteAlertNotifications(Long alertId);

    AlertNotification createAlertNotification(
            Long recipientUserId,
            Long senderUserId,
            Long senderOrgId,
            Long alertId,
            Long indicatorId,
            String indicatorName,
            String severity,
            BigDecimal actualPercent,
            BigDecimal expectedPercent,
            BigDecimal gapPercent
    );
}
