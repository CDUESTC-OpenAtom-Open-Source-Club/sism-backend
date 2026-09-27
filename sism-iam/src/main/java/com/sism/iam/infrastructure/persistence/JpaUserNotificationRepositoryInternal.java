package com.sism.iam.infrastructure.persistence;

import com.sism.iam.domain.notification.UserNotification;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface JpaUserNotificationRepositoryInternal extends JpaRepository<UserNotification, Long> {

    String APPROVAL_LIKE_PREDICATE = """
            (
                upper(n.notificationType) like '%APPROVAL%'
                or n.title like '%审批%'
                or n.title like '%审核%'
                or n.content like '%审批%'
                or n.content like '%审核%'
            )
            """;

    String KEYWORD_PREDICATE = """
            (
                lower(n.title) like lower(concat('%', :keyword, '%'))
                or lower(n.content) like lower(concat('%', :keyword, '%'))
            )
            """;

    Page<UserNotification> findByRecipientUserIdOrderByCreatedAtDesc(Long recipientUserId, Pageable pageable);

    Page<UserNotification> findByRecipientUserIdAndStatusOrderByCreatedAtDesc(
            Long recipientUserId,
            String status,
            Pageable pageable
    );

    Page<UserNotification> findByRecipientUserIdAndNotificationTypeOrderByCreatedAtDesc(
            Long recipientUserId,
            String notificationType,
            Pageable pageable
    );

    long countByRecipientUserId(Long recipientUserId);

    long countByRecipientUserIdAndStatus(Long recipientUserId, String status);

    long countByRecipientUserIdAndNotificationType(Long recipientUserId, String notificationType);

    long countByRecipientUserIdAndNotificationTypeAndStatus(Long recipientUserId, String notificationType, String status);

    @Query("""
            select n
              from UserNotification n
             where n.recipientUserId = :recipientUserId
               and """ + KEYWORD_PREDICATE + """
             order by n.createdAt desc
            """)
    Page<UserNotification> findByRecipientUserIdAndKeyword(
            @Param("recipientUserId") Long recipientUserId,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query("""
            select count(n)
              from UserNotification n
             where n.recipientUserId = :recipientUserId
               and """ + KEYWORD_PREDICATE)
    long countByRecipientUserIdAndKeyword(
            @Param("recipientUserId") Long recipientUserId,
            @Param("keyword") String keyword
    );

    @Query("""
            select n
              from UserNotification n
             where n.recipientUserId = :recipientUserId
               and """ + APPROVAL_LIKE_PREDICATE + """
             order by n.createdAt desc
            """)
    Page<UserNotification> findApprovalLikeByRecipientUserId(
            @Param("recipientUserId") Long recipientUserId,
            Pageable pageable
    );

    @Query("""
            select count(n)
              from UserNotification n
             where n.recipientUserId = :recipientUserId
               and """ + APPROVAL_LIKE_PREDICATE)
    long countApprovalLikeByRecipientUserId(@Param("recipientUserId") Long recipientUserId);

    @Query("""
            select n
              from UserNotification n
             where n.recipientUserId = :recipientUserId
               and """ + APPROVAL_LIKE_PREDICATE + """
               and """ + KEYWORD_PREDICATE + """
             order by n.createdAt desc
            """)
    Page<UserNotification> findApprovalLikeByRecipientUserIdAndKeyword(
            @Param("recipientUserId") Long recipientUserId,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query("""
            select count(n)
              from UserNotification n
             where n.recipientUserId = :recipientUserId
               and """ + APPROVAL_LIKE_PREDICATE + """
               and """ + KEYWORD_PREDICATE)
    long countApprovalLikeByRecipientUserIdAndKeyword(
            @Param("recipientUserId") Long recipientUserId,
            @Param("keyword") String keyword
    );

    @Query("""
            select count(n)
              from UserNotification n
             where n.recipientUserId = :recipientUserId
               and n.status = 'UNREAD'
               and """ + APPROVAL_LIKE_PREDICATE)
    long countApprovalLikeUnreadByRecipientUserId(@Param("recipientUserId") Long recipientUserId);

    @Query("""
            select n
              from UserNotification n
             where n.recipientUserId = :recipientUserId
               and n.notificationType = 'REMINDER'
               and """ + KEYWORD_PREDICATE + """
             order by n.createdAt desc
            """)
    Page<UserNotification> findReminderByRecipientUserIdAndKeyword(
            @Param("recipientUserId") Long recipientUserId,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query("""
            select count(n)
              from UserNotification n
             where n.recipientUserId = :recipientUserId
               and n.notificationType = 'REMINDER'
               and """ + KEYWORD_PREDICATE)
    long countReminderByRecipientUserIdAndKeyword(
            @Param("recipientUserId") Long recipientUserId,
            @Param("keyword") String keyword
    );

    Optional<UserNotification> findByIdAndRecipientUserId(Long id, Long recipientUserId);

    Optional<UserNotification> findTopByNotificationTypeAndRelatedEntityTypeAndRelatedEntityIdAndSenderUserIdOrderByCreatedAtDesc(
            String notificationType,
            String relatedEntityType,
            Long relatedEntityId,
            Long senderUserId
    );

    List<UserNotification> findByNotificationTypeAndRelatedEntityTypeAndRelatedEntityIdInAndSenderUserIdOrderByCreatedAtDesc(
            String notificationType,
            String relatedEntityType,
            Collection<Long> relatedEntityIds,
            Long senderUserId
    );

    @Query("""
            select count(distinct n.batchKey)
              from UserNotification n
             where n.notificationType = :notificationType
               and n.relatedEntityType = :relatedEntityType
               and n.relatedEntityId = :relatedEntityId
               and n.senderUserId = :senderUserId
            """)
    long countDistinctBatchKeyByReminder(
            @Param("notificationType") String notificationType,
            @Param("relatedEntityType") String relatedEntityType,
            @Param("relatedEntityId") Long relatedEntityId,
            @Param("senderUserId") Long senderUserId
    );

    @Modifying
    @Transactional
    @Query("""
            delete from UserNotification n
             where n.notificationType = :notificationType
               and n.relatedEntityType = :relatedEntityType
               and n.relatedEntityId = :relatedEntityId
            """)
    int deleteByNotificationTypeAndRelatedEntityTypeAndRelatedEntityId(
            @Param("notificationType") String notificationType,
            @Param("relatedEntityType") String relatedEntityType,
            @Param("relatedEntityId") Long relatedEntityId
    );

    @Modifying
    @Transactional
    @Query("""
            update UserNotification n
               set n.status = 'READ',
                   n.readAt = :readAt,
                   n.updatedAt = :readAt
             where n.id = :id
               and n.recipientUserId = :recipientUserId
               and n.status <> 'READ'
            """)
    int markAsRead(
            @Param("id") Long id,
            @Param("recipientUserId") Long recipientUserId,
            @Param("readAt") LocalDateTime readAt
    );

    @Modifying
    @Transactional
    @Query("""
            update UserNotification n
               set n.status = 'READ',
                   n.readAt = :readAt,
                   n.updatedAt = :readAt
             where n.recipientUserId = :recipientUserId
               and n.status <> 'READ'
            """)
    int markAllAsRead(@Param("recipientUserId") Long recipientUserId, @Param("readAt") LocalDateTime readAt);

    @Modifying
    @Transactional
    @Query("""
            update UserNotification n
               set n.status = 'READ',
                   n.readAt = :readAt,
                   n.updatedAt = :readAt
             where n.recipientUserId = :recipientUserId
               and n.notificationType = 'APPROVAL_SUBMITTED'
               and n.status <> 'READ'
               and n.actionUrl like :actionUrlPattern
            """)
    int markApprovalSubmittedNotificationsRead(
            @Param("recipientUserId") Long recipientUserId,
            @Param("actionUrlPattern") String actionUrlPattern,
            @Param("readAt") LocalDateTime readAt
    );
}
