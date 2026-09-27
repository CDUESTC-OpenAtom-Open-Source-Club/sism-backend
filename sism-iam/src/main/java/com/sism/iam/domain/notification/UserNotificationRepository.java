package com.sism.iam.domain.notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserNotificationRepository {

    Page<UserNotification> findByRecipientUserId(Long recipientUserId, Pageable pageable);

    Page<UserNotification> findByRecipientUserIdAndStatus(Long recipientUserId, String status, Pageable pageable);

    Page<UserNotification> findByRecipientUserIdAndNotificationType(
            Long recipientUserId,
            String notificationType,
            Pageable pageable
    );

    Page<UserNotification> findApprovalLikeByRecipientUserId(Long recipientUserId, Pageable pageable);

    Page<UserNotification> findReminderByRecipientUserId(Long recipientUserId, Pageable pageable);

    Page<UserNotification> findByRecipientUserIdAndKeyword(Long recipientUserId, String keyword, Pageable pageable);

    Page<UserNotification> findApprovalLikeByRecipientUserIdAndKeyword(Long recipientUserId, String keyword, Pageable pageable);

    Page<UserNotification> findReminderByRecipientUserIdAndKeyword(Long recipientUserId, String keyword, Pageable pageable);

    long countByRecipientUserId(Long recipientUserId);

    long countByRecipientUserIdAndStatus(Long recipientUserId, String status);

    long countByRecipientUserIdAndKeyword(Long recipientUserId, String keyword);

    long countApprovalLikeByRecipientUserId(Long recipientUserId);

    long countApprovalLikeUnreadByRecipientUserId(Long recipientUserId);

    long countApprovalLikeByRecipientUserIdAndKeyword(Long recipientUserId, String keyword);

    long countReminderByRecipientUserId(Long recipientUserId);

    long countReminderByRecipientUserIdAndStatus(Long recipientUserId, String status);

    long countReminderByRecipientUserIdAndKeyword(Long recipientUserId, String keyword);

    Optional<UserNotification> findByIdAndRecipientUserId(Long id, Long recipientUserId);

    List<UserNotification> saveAll(List<UserNotification> notifications);

    UserNotification save(UserNotification notification);

    long deleteByNotificationTypeAndRelatedEntityTypeAndRelatedEntityId(
            String notificationType,
            String relatedEntityType,
            Long relatedEntityId
    );

    int markAsRead(Long id, Long recipientUserId, LocalDateTime readAt);

    long markAllAsRead(Long recipientUserId);

    /**
     * 2026-09-27 消息留痕：审批人处理完成后，把其在该审批实例上仍未读的
     * 「待审批」（APPROVAL_SUBMITTED）通知标记为已读。通知保留不删除。
     *
     * @return 标记的通知条数
     */
    int markApprovalSubmittedNotificationsRead(Long recipientUserId, String actionUrlPattern, LocalDateTime readAt);

    Optional<UserNotification> findLatestReminder(Long relatedEntityId, Long senderUserId);

    List<UserNotification> findLatestReminders(Collection<Long> relatedEntityIds, Long senderUserId);

    long countReminderBatches(Long relatedEntityId, Long senderUserId);
}
