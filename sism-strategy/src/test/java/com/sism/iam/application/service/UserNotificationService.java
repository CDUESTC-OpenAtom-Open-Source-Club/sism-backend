package com.sism.iam.application.service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;

/**
 * Test-scope shim for the strategy module.
 * The current workspace only needs the type to exist while strategy tests run.
 */
public class UserNotificationService {

    public record ReminderResult(
            Long reminderId,
            Long indicatorId,
            int sentCount,
            LocalDateTime lastRemindedAt,
            int remindCount,
            LocalDateTime cooldownUntil
    ) {}

    public record ReminderStatus(
            boolean canRemind,
            LocalDateTime lastRemindedAt,
            int remindCount,
            LocalDateTime cooldownUntil
    ) {}

    public ReminderResult createReminderNotification(
            Long indicatorId,
            String indicatorDesc,
            Long targetOrgId,
            String targetOrgName,
            Long currentUserId,
            String currentUserName,
            Long currentUserOrgId,
            String source,
            String reason
    ) {
        throw new UnsupportedOperationException("Test shim only");
    }

    public Map<Long, ReminderStatus> getReminderStatuses(Collection<Long> indicatorIds, Long currentUserId) {
        throw new UnsupportedOperationException("Test shim only");
    }

    /**
     * 2026-10-31 缺陷修复（原样重发后审批人无待办通知）：shim 补齐与真实
     * sism-iam 服务同签名的「待审批」通知方法——重发续链后下一环节候选审批人
     * 的通知走该方法（与首发起 PlanWorkflowEventListener 同一通知口径），
     * 测试里由 Mockito mock 承接。真实类签名见
     * sism-iam UserNotificationService#createSubmissionNotification。
     */
    public record SubmissionNotificationResult(
            Long notificationId,
            Long recipientUserId,
            Long approvalInstanceId,
            String entityType,
            Long entityId,
            LocalDateTime createdAt
    ) {}

    public SubmissionNotificationResult createSubmissionNotification(
            Long recipientUserId,
            Long senderUserId,
            Long senderOrgId,
            Long approvalInstanceId,
            String entityType,
            Long entityId,
            String businessName,
            String stepName,
            String submitterName
    ) {
        throw new UnsupportedOperationException("Test shim only");
    }
}
