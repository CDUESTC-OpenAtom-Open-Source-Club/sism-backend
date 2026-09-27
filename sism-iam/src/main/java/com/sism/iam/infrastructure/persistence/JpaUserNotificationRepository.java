package com.sism.iam.infrastructure.persistence;

import com.sism.iam.domain.notification.UserNotification;
import com.sism.iam.domain.notification.UserNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaUserNotificationRepository implements UserNotificationRepository {

    private final JpaUserNotificationRepositoryInternal jpaRepository;

    @Override
    public Page<UserNotification> findByRecipientUserId(Long recipientUserId, Pageable pageable) {
        return jpaRepository.findByRecipientUserIdOrderByCreatedAtDesc(recipientUserId, pageable);
    }

    @Override
    public Page<UserNotification> findByRecipientUserIdAndStatus(Long recipientUserId, String status, Pageable pageable) {
        return jpaRepository.findByRecipientUserIdAndStatusOrderByCreatedAtDesc(recipientUserId, status, pageable);
    }

    @Override
    public Page<UserNotification> findByRecipientUserIdAndNotificationType(
            Long recipientUserId,
            String notificationType,
            Pageable pageable
    ) {
        return jpaRepository.findByRecipientUserIdAndNotificationTypeOrderByCreatedAtDesc(
                recipientUserId,
                notificationType,
                pageable
        );
    }

    @Override
    public Page<UserNotification> findApprovalLikeByRecipientUserId(Long recipientUserId, Pageable pageable) {
        return jpaRepository.findApprovalLikeByRecipientUserId(recipientUserId, pageable);
    }

    @Override
    public Page<UserNotification> findReminderByRecipientUserId(Long recipientUserId, Pageable pageable) {
        return jpaRepository.findByRecipientUserIdAndNotificationTypeOrderByCreatedAtDesc(recipientUserId, "REMINDER", pageable);
    }

    @Override
    public Page<UserNotification> findByRecipientUserIdAndKeyword(Long recipientUserId, String keyword, Pageable pageable) {
        return jpaRepository.findByRecipientUserIdAndKeyword(recipientUserId, keyword, pageable);
    }

    @Override
    public Page<UserNotification> findApprovalLikeByRecipientUserIdAndKeyword(Long recipientUserId, String keyword, Pageable pageable) {
        return jpaRepository.findApprovalLikeByRecipientUserIdAndKeyword(recipientUserId, keyword, pageable);
    }

    @Override
    public Page<UserNotification> findReminderByRecipientUserIdAndKeyword(Long recipientUserId, String keyword, Pageable pageable) {
        return jpaRepository.findReminderByRecipientUserIdAndKeyword(recipientUserId, keyword, pageable);
    }

    @Override
    public long countByRecipientUserId(Long recipientUserId) {
        return jpaRepository.countByRecipientUserId(recipientUserId);
    }

    @Override
    public long countByRecipientUserIdAndStatus(Long recipientUserId, String status) {
        return jpaRepository.countByRecipientUserIdAndStatus(recipientUserId, status);
    }

    @Override
    public long countByRecipientUserIdAndKeyword(Long recipientUserId, String keyword) {
        return jpaRepository.countByRecipientUserIdAndKeyword(recipientUserId, keyword);
    }

    @Override
    public long countApprovalLikeByRecipientUserId(Long recipientUserId) {
        return jpaRepository.countApprovalLikeByRecipientUserId(recipientUserId);
    }

    @Override
    public long countApprovalLikeUnreadByRecipientUserId(Long recipientUserId) {
        return jpaRepository.countApprovalLikeUnreadByRecipientUserId(recipientUserId);
    }

    @Override
    public long countApprovalLikeByRecipientUserIdAndKeyword(Long recipientUserId, String keyword) {
        return jpaRepository.countApprovalLikeByRecipientUserIdAndKeyword(recipientUserId, keyword);
    }

    @Override
    public long countReminderByRecipientUserId(Long recipientUserId) {
        return jpaRepository.countByRecipientUserIdAndNotificationType(recipientUserId, "REMINDER");
    }

    @Override
    public long countReminderByRecipientUserIdAndStatus(Long recipientUserId, String status) {
        return jpaRepository.countByRecipientUserIdAndNotificationTypeAndStatus(recipientUserId, "REMINDER", status);
    }

    @Override
    public long countReminderByRecipientUserIdAndKeyword(Long recipientUserId, String keyword) {
        return jpaRepository.countReminderByRecipientUserIdAndKeyword(recipientUserId, keyword);
    }

    @Override
    public Optional<UserNotification> findByIdAndRecipientUserId(Long id, Long recipientUserId) {
        return jpaRepository.findByIdAndRecipientUserId(id, recipientUserId);
    }

    @Override
    public List<UserNotification> saveAll(List<UserNotification> notifications) {
        return jpaRepository.saveAll(notifications);
    }

    @Override
    public UserNotification save(UserNotification notification) {
        return jpaRepository.save(notification);
    }

    @Override
    public long deleteByNotificationTypeAndRelatedEntityTypeAndRelatedEntityId(
            String notificationType,
            String relatedEntityType,
            Long relatedEntityId
    ) {
        return jpaRepository.deleteByNotificationTypeAndRelatedEntityTypeAndRelatedEntityId(
                notificationType,
                relatedEntityType,
                relatedEntityId
        );
    }

    public int markAsRead(Long id, Long recipientUserId, LocalDateTime readAt) {
        return jpaRepository.markAsRead(id, recipientUserId, readAt);
    }

    @Override
    public long markAllAsRead(Long recipientUserId) {
        return jpaRepository.markAllAsRead(recipientUserId, LocalDateTime.now());
    }

    @Override
    public int markApprovalSubmittedNotificationsRead(Long recipientUserId, String actionUrlPattern, LocalDateTime readAt) {
        return jpaRepository.markApprovalSubmittedNotificationsRead(recipientUserId, actionUrlPattern, readAt);
    }

    @Override
    public Optional<UserNotification> findLatestReminder(Long relatedEntityId, Long senderUserId) {
        return jpaRepository
                .findTopByNotificationTypeAndRelatedEntityTypeAndRelatedEntityIdAndSenderUserIdOrderByCreatedAtDesc(
                        "REMINDER",
                        "INDICATOR",
                        relatedEntityId,
                        senderUserId
                );
    }

    @Override
    public List<UserNotification> findLatestReminders(Collection<Long> relatedEntityIds, Long senderUserId) {
        List<UserNotification> notifications = jpaRepository
                .findByNotificationTypeAndRelatedEntityTypeAndRelatedEntityIdInAndSenderUserIdOrderByCreatedAtDesc(
                        "REMINDER",
                        "INDICATOR",
                        relatedEntityIds,
                        senderUserId
                );
        Map<Long, UserNotification> latestByEntityId = new LinkedHashMap<>();
        for (UserNotification notification : notifications) {
            latestByEntityId.putIfAbsent(notification.getRelatedEntityId(), notification);
        }
        return List.copyOf(latestByEntityId.values());
    }

    @Override
    public long countReminderBatches(Long relatedEntityId, Long senderUserId) {
        return jpaRepository.countDistinctBatchKeyByReminder("REMINDER", "INDICATOR", relatedEntityId, senderUserId);
    }
}
