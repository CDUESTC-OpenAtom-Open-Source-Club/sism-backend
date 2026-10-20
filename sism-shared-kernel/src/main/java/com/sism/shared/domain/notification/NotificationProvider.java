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

    /**
     * 2026-10-20 用户定案（复盘 P1）：导入自动下发时系统管理员代批了本该由
     * 战略部负责人 / 分管校领导处理的节点。给该节点候选人写一条**留痕通知**——
     * 必须落库到消息中心（可查证是谁审核的），但不发右上角实时弹窗打断用户。
     */
    ApprovalResultNotification createSystemAutoApprovedNotification(
            Long recipientUserId,
            Long senderUserId,
            Long senderOrgId,
            Long approvalInstanceId,
            String entityType,
            Long entityId,
            String businessName,
            String stepName,
            String operatorName
    );

    /**
     * 2026-09-27 用户拍板：审批处理完成后消息留痕——
     * 将该审批实例上指定审批人仍未读的「待审批」通知标记为已读。
     * 通知保留在消息中心（不删除），仅从未读转为已读留痕。
     *
     * @return 标记的通知条数
     */
    int markApprovalSubmittedNotificationsRead(Long approvalInstanceId, Long recipientUserId);

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
