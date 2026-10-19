package com.sism.main.application;

import com.sism.iam.application.service.UserNotificationService;
import com.sism.iam.domain.announcement.AnnouncementStatus;
import com.sism.iam.domain.announcement.SystemAnnouncementRepository;
import com.sism.iam.domain.notification.UserNotification;
import com.sism.iam.domain.notification.UserNotificationRepository;
import com.sism.main.interfaces.dto.MessageCenterModels;
import com.sism.workflow.application.query.WorkflowReadModelService;
import com.sism.workflow.domain.query.repository.WorkflowQueryRepository;
import com.sism.workflow.interfaces.dto.PageResult;
import com.sism.workflow.interfaces.dto.WorkflowTaskResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MessageCenterApplicationService {

    private static final int NOTIFICATION_BATCH_SIZE = 200;
    private static final int MERGE_BATCH_MULTIPLIER = 2;
    private static final String CATEGORY_ALL = "ALL";
    private static final String CATEGORY_TODO = "TODO";
    private static final String CATEGORY_APPROVAL = "APPROVAL";
    private static final String CATEGORY_REMINDER = "REMINDER";
    private static final String CATEGORY_SYSTEM = "SYSTEM";
    private static final String CATEGORY_RISK = "RISK";

    private static final String BIZ_APPROVAL_TODO = "APPROVAL_TODO";
    private static final String BIZ_APPROVAL_RESULT = "APPROVAL_RESULT";
    private static final String BIZ_REMINDER_NOTICE = "REMINDER_NOTICE";
    private static final String BIZ_SYSTEM_NOTICE = "SYSTEM_NOTICE";
    private static final String BIZ_BUSINESS_NOTICE = "BUSINESS_NOTICE";
    private static final String TYPE_SYSTEM_ANNOUNCEMENT = "SYSTEM_ANNOUNCEMENT";
    private static final String ENTITY_ANNOUNCEMENT = "ANNOUNCEMENT";

    private static final String READ_UNREAD = "UNREAD";
    private static final String READ_READ = "READ";
    private static final String ACTION_REQUIRED = "ACTION_REQUIRED";

    private final UserNotificationRepository userNotificationRepository;
    private final SystemAnnouncementRepository systemAnnouncementRepository;
    private final UserNotificationService userNotificationService;
    private final WorkflowReadModelService workflowReadModelService;

    public MessageCenterModels.Summary getSummary(Long userId) {
        cleanupStaleAnnouncementNotifications(userId);
        List<String> unavailableSources = new ArrayList<>();
        boolean partialSuccess = false;
        long todoCount = 0;
        long approvalResultUnread = 0;
        long reminderUnread = 0;
        long systemUnread = 0;
        long riskUnread = 0;

        try {
            todoCount = workflowReadModelService.countMyPendingTasks(userId);
        } catch (RuntimeException ex) {
            partialSuccess = true;
            unavailableSources.add("workflow");
        }

        try {
            long unreadTotal = userNotificationRepository.countByRecipientUserIdAndStatus(userId, READ_UNREAD);
            approvalResultUnread = userNotificationRepository.countApprovalLikeUnreadByRecipientUserId(userId);
            reminderUnread = userNotificationRepository.countReminderByRecipientUserIdAndStatus(userId, READ_UNREAD);
            systemUnread = Math.max(unreadTotal - approvalResultUnread - reminderUnread, 0);
        } catch (RuntimeException ex) {
            partialSuccess = true;
            unavailableSources.add("notifications");
        }

        long approvalCount = todoCount + approvalResultUnread;
        long totalCount = todoCount + approvalResultUnread + reminderUnread + systemUnread + riskUnread;

        return new MessageCenterModels.Summary(
                totalCount,
                todoCount,
                approvalCount,
                reminderUnread,
                systemUnread,
                riskUnread,
                defaultCapabilities(),
                LocalDateTime.now(),
                partialSuccess,
                List.copyOf(unavailableSources)
        );
    }

    public MessageCenterModels.ListResponse getMessages(
            Long userId,
            String category,
            int pageNum,
            int pageSize,
            String keyword,
            Boolean includeRisk) {
        cleanupStaleAnnouncementNotifications(userId);
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.max(pageSize, 1);
        String normalizedCategory = normalize(category);
        if (requiresKeywordOptimizedPath(keyword, includeRisk)) {
            return buildKeywordListResponse(userId, normalizedCategory, safePageNum, safePageSize, keyword);
        }
        if (requiresLegacyAggregation(normalizedCategory, includeRisk)) {
            return buildLegacyListResponse(userId, normalizedCategory, safePageNum, safePageSize, keyword, includeRisk);
        }

        return switch (normalizedCategory) {
            case CATEGORY_TODO -> buildTodoListResponse(userId, safePageNum, safePageSize);
            case CATEGORY_APPROVAL -> buildApprovalListResponse(userId, safePageNum, safePageSize);
            case CATEGORY_REMINDER -> buildReminderListResponse(userId, safePageNum, safePageSize);
            case CATEGORY_SYSTEM -> buildSystemListResponse(userId, safePageNum, safePageSize);
            case CATEGORY_ALL, "" -> buildAllListResponse(userId, safePageNum, safePageSize);
            default -> buildLegacyListResponse(userId, normalizedCategory, safePageNum, safePageSize, keyword, includeRisk);
        };
    }

    private MessageCenterModels.ListResponse buildKeywordListResponse(
            Long userId,
            String normalizedCategory,
            int safePageNum,
            int safePageSize,
            String keyword) {
        return switch (normalizedCategory) {
            case CATEGORY_TODO -> buildKeywordTodoListResponse(userId, safePageNum, safePageSize, keyword);
            case CATEGORY_APPROVAL -> buildKeywordApprovalListResponse(userId, safePageNum, safePageSize, keyword);
            case CATEGORY_REMINDER -> buildKeywordReminderListResponse(userId, safePageNum, safePageSize, keyword);
            case CATEGORY_SYSTEM -> buildKeywordSystemListResponse(userId, safePageNum, safePageSize, keyword);
            case CATEGORY_ALL, "" -> buildKeywordAllListResponse(userId, safePageNum, safePageSize, keyword);
            default -> buildLegacyListResponse(userId, normalizedCategory, safePageNum, safePageSize, keyword, false);
        };
    }

    private MessageCenterModels.ListResponse buildAllListResponse(Long userId, int safePageNum, int safePageSize) {
        int endIndex = safePageNum * safePageSize;
        List<String> unavailableSources = new ArrayList<>();
        boolean partialSuccess = false;
        List<MessageCenterModels.Item> workflowItems = List.of();
        NotificationCandidateResult notificationResult = NotificationCandidateResult.empty();
        WorkflowIdentityLookup workflowLookup = WorkflowIdentityLookup.empty();
        long workflowTotal = 0;
        long notificationTotal = 0;
        long duplicateApprovalCount = 0;

        try {
            workflowTotal = workflowReadModelService.countMyPendingTasks(userId);
            workflowItems = loadWorkflowCandidates(userId, endIndex);
            workflowLookup = WorkflowIdentityLookup.fromItems(workflowItems);
            if (workflowLookup.isEmpty()) {
                workflowLookup = WorkflowIdentityLookup.from(workflowReadModelService.listPendingTaskIdentities(userId));
            }
        } catch (RuntimeException ex) {
            partialSuccess = true;
            unavailableSources.add("workflow");
        }

        try {
            notificationTotal = userNotificationRepository.countByRecipientUserId(userId);
            if (!workflowLookup.isEmpty()) {
                duplicateApprovalCount = countDuplicateApprovalNotifications(userId, workflowLookup);
            }
            notificationResult = loadNotificationCandidates(userId, endIndex, workflowLookup);
        } catch (RuntimeException ex) {
            partialSuccess = true;
            unavailableSources.add("notifications");
        }

        List<MessageCenterModels.Item> mergedItems = mergeAndSortItems(workflowItems, notificationResult.items());
        int startIndex = Math.min((safePageNum - 1) * safePageSize, mergedItems.size());
        int pageEndIndex = Math.min(startIndex + safePageSize, mergedItems.size());
        long total = Math.max(workflowTotal + notificationTotal - duplicateApprovalCount, mergedItems.size());
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / safePageSize);

        return new MessageCenterModels.ListResponse(
                mergedItems.subList(startIndex, pageEndIndex),
                total,
                safePageNum,
                safePageSize,
                totalPages,
                partialSuccess,
                List.copyOf(unavailableSources),
                defaultCapabilities()
        );
    }

    private MessageCenterModels.ListResponse buildTodoListResponse(Long userId, int safePageNum, int safePageSize) {
        try {
            long total = workflowReadModelService.countMyPendingTasks(userId);
            int endIndex = safePageNum * safePageSize;
            List<MessageCenterModels.Item> items = loadWorkflowCandidates(userId, endIndex);
            return pageItems(items, total, safePageNum, safePageSize, false, List.of());
        } catch (RuntimeException ex) {
            return emptyListResponse(safePageNum, safePageSize, true, List.of("workflow"));
        }
    }

    private MessageCenterModels.ListResponse buildApprovalListResponse(Long userId, int safePageNum, int safePageSize) {
        int endIndex = safePageNum * safePageSize;
        List<String> unavailableSources = new ArrayList<>();
        boolean partialSuccess = false;
        List<MessageCenterModels.Item> workflowItems = List.of();
        WorkflowIdentityLookup workflowLookup = WorkflowIdentityLookup.empty();
        long workflowTotal = 0;
        long approvalNotificationTotal = 0;
        long duplicateApprovalCount = 0;
        NotificationCandidateResult notificationResult = NotificationCandidateResult.empty();

        try {
            workflowTotal = workflowReadModelService.countMyPendingTasks(userId);
            workflowItems = loadWorkflowCandidates(userId, endIndex);
            workflowLookup = WorkflowIdentityLookup.fromItems(workflowItems);
            if (workflowLookup.isEmpty()) {
                workflowLookup = WorkflowIdentityLookup.from(workflowReadModelService.listPendingTaskIdentities(userId));
            }
        } catch (RuntimeException ex) {
            partialSuccess = true;
            unavailableSources.add("workflow");
        }

        try {
            approvalNotificationTotal = userNotificationRepository.countApprovalLikeByRecipientUserId(userId);
            if (!workflowLookup.isEmpty()) {
                duplicateApprovalCount = countDuplicateApprovalNotifications(userId, workflowLookup);
            }
            notificationResult = loadApprovalNotificationCandidates(userId, endIndex, workflowLookup);
        } catch (RuntimeException ex) {
            partialSuccess = true;
            unavailableSources.add("notifications");
        }

        List<MessageCenterModels.Item> mergedItems = mergeAndSortItems(workflowItems, notificationResult.items());
        long total = Math.max(workflowTotal + approvalNotificationTotal - duplicateApprovalCount, mergedItems.size());
        return pageItems(mergedItems, total, safePageNum, safePageSize, partialSuccess, unavailableSources);
    }

    private MessageCenterModels.ListResponse buildKeywordAllListResponse(Long userId, int safePageNum, int safePageSize, String keyword) {
        int endIndex = safePageNum * safePageSize;
        List<String> unavailableSources = new ArrayList<>();
        boolean partialSuccess = false;
        WorkflowKeywordResult workflowResult = WorkflowKeywordResult.empty();
        WorkflowIdentityLookup workflowLookup = WorkflowIdentityLookup.empty();
        NotificationCandidateResult notificationResult = NotificationCandidateResult.empty();
        long notificationTotal = 0;
        long duplicateApprovalCount = 0;

        try {
            workflowResult = loadWorkflowKeywordMatches(userId, keyword, endIndex);
            workflowLookup = WorkflowIdentityLookup.fromItems(workflowResult.items());
            if (workflowLookup.isEmpty()) {
                workflowLookup = WorkflowIdentityLookup.from(workflowReadModelService.listPendingTaskIdentities(userId));
            }
        } catch (RuntimeException ex) {
            partialSuccess = true;
            unavailableSources.add("workflow");
        }

        try {
            notificationTotal = userNotificationRepository.countByRecipientUserIdAndKeyword(userId, keyword);
            if (!workflowLookup.isEmpty()) {
                duplicateApprovalCount = countDuplicateApprovalNotificationsByKeyword(userId, keyword, workflowLookup);
            }
            notificationResult = loadNotificationKeywordCandidates(userId, keyword, endIndex, workflowLookup);
        } catch (RuntimeException ex) {
            partialSuccess = true;
            unavailableSources.add("notifications");
        }

        List<MessageCenterModels.Item> mergedItems = mergeAndSortItems(workflowResult.items(), notificationResult.items());
        long total = Math.max(workflowResult.total() + notificationTotal - duplicateApprovalCount, mergedItems.size());
        return pageItems(mergedItems, total, safePageNum, safePageSize, partialSuccess, unavailableSources);
    }

    private MessageCenterModels.ListResponse buildKeywordTodoListResponse(Long userId, int safePageNum, int safePageSize, String keyword) {
        try {
            WorkflowKeywordResult workflowResult = loadWorkflowKeywordMatches(userId, keyword, safePageNum * safePageSize);
            return pageItems(workflowResult.items(), workflowResult.total(), safePageNum, safePageSize, false, List.of());
        } catch (RuntimeException ex) {
            return emptyListResponse(safePageNum, safePageSize, true, List.of("workflow"));
        }
    }

    private MessageCenterModels.ListResponse buildKeywordApprovalListResponse(Long userId, int safePageNum, int safePageSize, String keyword) {
        int endIndex = safePageNum * safePageSize;
        List<String> unavailableSources = new ArrayList<>();
        boolean partialSuccess = false;
        WorkflowKeywordResult workflowResult = WorkflowKeywordResult.empty();
        WorkflowIdentityLookup workflowLookup = WorkflowIdentityLookup.empty();
        NotificationCandidateResult notificationResult = NotificationCandidateResult.empty();
        long approvalNotificationTotal = 0;
        long duplicateApprovalCount = 0;

        try {
            workflowResult = loadWorkflowKeywordMatches(userId, keyword, endIndex);
            workflowLookup = WorkflowIdentityLookup.fromItems(workflowResult.items());
            if (workflowLookup.isEmpty()) {
                workflowLookup = WorkflowIdentityLookup.from(workflowReadModelService.listPendingTaskIdentities(userId));
            }
        } catch (RuntimeException ex) {
            partialSuccess = true;
            unavailableSources.add("workflow");
        }

        try {
            approvalNotificationTotal = userNotificationRepository.countApprovalLikeByRecipientUserIdAndKeyword(userId, keyword);
            if (!workflowLookup.isEmpty()) {
                duplicateApprovalCount = countDuplicateApprovalNotificationsByKeyword(userId, keyword, workflowLookup);
            }
            notificationResult = loadApprovalNotificationKeywordCandidates(userId, keyword, endIndex, workflowLookup);
        } catch (RuntimeException ex) {
            partialSuccess = true;
            unavailableSources.add("notifications");
        }

        List<MessageCenterModels.Item> mergedItems = mergeAndSortItems(workflowResult.items(), notificationResult.items());
        long total = Math.max(workflowResult.total() + approvalNotificationTotal - duplicateApprovalCount, mergedItems.size());
        return pageItems(mergedItems, total, safePageNum, safePageSize, partialSuccess, unavailableSources);
    }

    private MessageCenterModels.ListResponse buildKeywordReminderListResponse(Long userId, int safePageNum, int safePageSize, String keyword) {
        try {
            long total = userNotificationRepository.countReminderByRecipientUserIdAndKeyword(userId, keyword);
            NotificationCandidateResult notificationResult = loadReminderNotificationKeywordCandidates(userId, keyword, safePageNum * safePageSize);
            return pageItems(notificationResult.items(), Math.max(total, notificationResult.items().size()), safePageNum, safePageSize, false, List.of());
        } catch (RuntimeException ex) {
            return emptyListResponse(safePageNum, safePageSize, true, List.of("notifications"));
        }
    }

    private MessageCenterModels.ListResponse buildKeywordSystemListResponse(Long userId, int safePageNum, int safePageSize, String keyword) {
        try {
            long totalNotifications = userNotificationRepository.countByRecipientUserIdAndKeyword(userId, keyword);
            long reminderTotal = userNotificationRepository.countReminderByRecipientUserIdAndKeyword(userId, keyword);
            long approvalTotal = userNotificationRepository.countApprovalLikeByRecipientUserIdAndKeyword(userId, keyword);
            long systemTotal = Math.max(totalNotifications - reminderTotal - approvalTotal, 0);
            NotificationCandidateResult notificationResult = loadSystemNotificationKeywordCandidates(userId, keyword, safePageNum * safePageSize);
            return pageItems(notificationResult.items(), Math.max(systemTotal, notificationResult.items().size()), safePageNum, safePageSize, false, List.of());
        } catch (RuntimeException ex) {
            return emptyListResponse(safePageNum, safePageSize, true, List.of("notifications"));
        }
    }

    private MessageCenterModels.ListResponse buildReminderListResponse(Long userId, int safePageNum, int safePageSize) {
        try {
            long total = userNotificationRepository.countReminderByRecipientUserId(userId);
            int endIndex = safePageNum * safePageSize;
            NotificationCandidateResult notificationResult = loadReminderNotificationCandidates(userId, endIndex);
            return pageItems(notificationResult.items(), Math.max(total, notificationResult.items().size()), safePageNum, safePageSize, false, List.of());
        } catch (RuntimeException ex) {
            return emptyListResponse(safePageNum, safePageSize, true, List.of("notifications"));
        }
    }

    private MessageCenterModels.ListResponse buildSystemListResponse(Long userId, int safePageNum, int safePageSize) {
        try {
            long totalNotifications = userNotificationRepository.countByRecipientUserId(userId);
            long reminderTotal = userNotificationRepository.countReminderByRecipientUserId(userId);
            long approvalTotal = userNotificationRepository.countApprovalLikeByRecipientUserId(userId);
            long systemTotal = Math.max(totalNotifications - reminderTotal - approvalTotal, 0);
            int endIndex = safePageNum * safePageSize;
            NotificationCandidateResult notificationResult = loadSystemNotificationCandidates(userId, endIndex);
            return pageItems(notificationResult.items(), Math.max(systemTotal, notificationResult.items().size()), safePageNum, safePageSize, false, List.of());
        } catch (RuntimeException ex) {
            return emptyListResponse(safePageNum, safePageSize, true, List.of("notifications"));
        }
    }

    public MessageCenterModels.Item getMessageDetail(Long userId, String messageId) {
        cleanupStaleAnnouncementNotifications(userId);
        ParsedMessageId parsedMessageId = parseMessageId(messageId);
        return switch (parsedMessageId.sourceType()) {
            case "notification" -> userNotificationRepository.findByIdAndRecipientUserId(parsedMessageId.notificationId(), userId)
                    .map(this::toNotificationItem)
                    .orElseThrow(() -> new IllegalArgumentException("消息不存在或无权限访问"));
            case "workflow" -> workflowReadModelService.findMyPendingTaskById(userId, parsedMessageId.sourceId())
                    .map(this::toWorkflowItem)
                    .orElseThrow(() -> new IllegalArgumentException("消息不存在或已完成处理"));
            default -> throw new IllegalArgumentException("暂不支持的消息类型: " + parsedMessageId.sourceType());
        };
    }

    public MessageCenterModels.ReadResult markMessageAsRead(Long userId, String messageId) {
        ParsedMessageId parsedMessageId = parseMessageId(messageId);
        if (!"notification".equals(parsedMessageId.sourceType())) {
            throw new IllegalArgumentException("仅普通通知支持标记已读");
        }

        Map<String, Object> result = userNotificationService.markNotificationAsRead(parsedMessageId.notificationId(), userId);
        LocalDateTime timestamp = toLocalDateTime(result.get("readAt"));
        return new MessageCenterModels.ReadResult(messageId, true, READ_READ, timestamp, 1);
    }

    public MessageCenterModels.ReadResult markAllAsRead(Long userId) {
        Map<String, Object> result = userNotificationService.markAllNotificationsAsRead(userId);
        LocalDateTime timestamp = toLocalDateTime(result.get("timestamp"));
        long affectedCount = toLong(result.get("readCount"));
        return new MessageCenterModels.ReadResult("notification:*", true, READ_READ, timestamp, affectedCount);
    }

    private void cleanupStaleAnnouncementNotifications(Long userId) {
        if (userId == null) {
            return;
        }

        Set<Long> staleAnnouncementIds = new LinkedHashSet<>();
        int pageNum = 0;
        Page<UserNotification> page;
        do {
            page = userNotificationRepository.findByRecipientUserIdAndNotificationType(
                    userId,
                    TYPE_SYSTEM_ANNOUNCEMENT,
                    PageRequest.of(pageNum, NOTIFICATION_BATCH_SIZE)
            );
            if (page == null) {
                return;
            }
            page.getContent().stream()
                    .map(UserNotification::getRelatedEntityId)
                    .filter(Objects::nonNull)
                    .filter(this::isStaleAnnouncement)
                    .forEach(staleAnnouncementIds::add);
            pageNum++;
        } while (page.hasNext());

        staleAnnouncementIds.forEach(announcementId -> userNotificationRepository
                .deleteByNotificationTypeAndRelatedEntityTypeAndRelatedEntityId(
                        TYPE_SYSTEM_ANNOUNCEMENT,
                        ENTITY_ANNOUNCEMENT,
                        announcementId
                ));
    }

    private boolean isStaleAnnouncement(Long announcementId) {
        return systemAnnouncementRepository.findById(announcementId)
                .map(announcement -> announcement.getStatus() != AnnouncementStatus.PUBLISHED)
                .orElse(true);
    }

    private Aggregation aggregate(Long userId) {
        List<MessageCenterModels.Item> items = new ArrayList<>();
        List<String> unavailableSources = new ArrayList<>();
        boolean partialSuccess = false;

        List<WorkflowTaskResponse> workflowTasks = List.of();
        try {
            workflowTasks = loadAllPendingWorkflowTasks(userId);
            items.addAll(workflowTasks.stream().map(this::toWorkflowItem).toList());
        } catch (RuntimeException ex) {
            partialSuccess = true;
            unavailableSources.add("workflow");
        }

        Set<String> workflowIdentityKeys = workflowTasks.stream()
                .map(this::buildWorkflowIdentityKeys)
                .flatMap(Collection::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        try {
            List<UserNotification> notifications = loadAllNotifications(userId);
            items.addAll(notifications.stream()
                    .filter(notification -> !isDuplicateApprovalNotification(notification, workflowIdentityKeys))
                    .map(this::toNotificationItem)
                    .toList());
        } catch (RuntimeException ex) {
            partialSuccess = true;
            unavailableSources.add("notifications");
        }

        items.sort(Comparator
                .comparing(MessageCenterModels.Item::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(MessageCenterModels.Item::priority, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(MessageCenterModels.Item::messageId));

        return new Aggregation(
                List.copyOf(items),
                defaultCapabilities(),
                partialSuccess,
                List.copyOf(unavailableSources),
                LocalDateTime.now()
        );
    }

    private MessageCenterModels.ListResponse buildLegacyListResponse(
            Long userId,
            String category,
            int pageNum,
            int pageSize,
            String keyword,
            Boolean includeRisk) {
        Aggregation aggregation = aggregate(userId);
        List<MessageCenterModels.Item> filteredItems = aggregation.items().stream()
                .filter(item -> matchesCategory(item, category))
                .filter(item -> matchesKeyword(item, keyword))
                .filter(item -> shouldInclude(item, includeRisk))
                .toList();
        int startIndex = Math.min((pageNum - 1) * pageSize, filteredItems.size());
        int endIndex = Math.min(startIndex + pageSize, filteredItems.size());
        int totalPages = filteredItems.isEmpty() ? 0 : (int) Math.ceil((double) filteredItems.size() / pageSize);
        return new MessageCenterModels.ListResponse(
                filteredItems.subList(startIndex, endIndex),
                filteredItems.size(),
                pageNum,
                pageSize,
                totalPages,
                aggregation.partialSuccess(),
                aggregation.unavailableSources(),
                aggregation.capabilities()
        );
    }

    private boolean requiresKeywordOptimizedPath(String keyword, Boolean includeRisk) {
        return StringUtils.hasText(keyword) && !Boolean.TRUE.equals(includeRisk);
    }

    private boolean requiresLegacyAggregation(String normalizedCategory, Boolean includeRisk) {
        return CATEGORY_RISK.equals(normalizedCategory)
                || (Boolean.TRUE.equals(includeRisk) && CATEGORY_RISK.equals(normalizedCategory));
    }

    private List<UserNotification> loadAllNotifications(Long userId) {
        List<UserNotification> notifications = new ArrayList<>();
        int pageNum = 0;
        Page<UserNotification> page;
        do {
            page = userNotificationRepository.findByRecipientUserId(userId, PageRequest.of(pageNum, NOTIFICATION_BATCH_SIZE));
            notifications.addAll(page.getContent());
            pageNum++;
        } while (page.hasNext());
        return notifications;
    }

    private List<WorkflowTaskResponse> loadAllPendingWorkflowTasks(Long userId) {
        List<WorkflowTaskResponse> tasks = new ArrayList<>();
        int pageNum = 1;
        PageResult<WorkflowTaskResponse> currentPage;
        do {
            currentPage = workflowReadModelService.getMyPendingTasks(userId, pageNum);
            if (currentPage.getItems() != null) {
                tasks.addAll(currentPage.getItems());
            }
            pageNum++;
        } while (currentPage.getPageNum() < currentPage.getTotalPages());
        return tasks;
    }

    private List<MessageCenterModels.Item> loadWorkflowCandidates(Long userId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int batchSize = Math.max(limit, 1);
        PageResult<WorkflowTaskResponse> page = workflowReadModelService.getMyPendingTasks(userId, 1, batchSize);
        if (page.getItems() == null || page.getItems().isEmpty()) {
            return List.of();
        }
        return page.getItems().stream()
                .map(this::toWorkflowItem)
                .toList();
    }

    private NotificationCandidateResult loadNotificationCandidates(Long userId, int limit, WorkflowIdentityLookup workflowLookup) {
        if (limit <= 0) {
            return NotificationCandidateResult.empty();
        }
        List<MessageCenterModels.Item> items = new ArrayList<>();
        int pageNum = 0;
        int batchSize = Math.max(limit * MERGE_BATCH_MULTIPLIER, NOTIFICATION_BATCH_SIZE);
        Page<UserNotification> page;
        do {
            page = userNotificationRepository.findByRecipientUserId(userId, PageRequest.of(pageNum, batchSize));
            page.getContent().stream()
                    .filter(notification -> !isDuplicateApprovalNotification(notification, workflowLookup))
                    .map(this::toNotificationItem)
                    .forEach(items::add);
            pageNum++;
        } while (page.hasNext() && items.size() < limit);
        return new NotificationCandidateResult(List.copyOf(items), !page.hasNext());
    }

    private NotificationCandidateResult loadNotificationKeywordCandidates(Long userId, String keyword, int limit, WorkflowIdentityLookup workflowLookup) {
        if (limit <= 0) {
            return NotificationCandidateResult.empty();
        }
        List<MessageCenterModels.Item> items = new ArrayList<>();
        int pageNum = 0;
        int batchSize = Math.max(limit * MERGE_BATCH_MULTIPLIER, NOTIFICATION_BATCH_SIZE);
        Page<UserNotification> page;
        do {
            page = userNotificationRepository.findByRecipientUserIdAndKeyword(userId, keyword, PageRequest.of(pageNum, batchSize));
            page.getContent().stream()
                    .filter(notification -> !isDuplicateApprovalNotification(notification, workflowLookup))
                    .map(this::toNotificationItem)
                    .forEach(items::add);
            pageNum++;
        } while (page.hasNext() && items.size() < limit);
        return new NotificationCandidateResult(List.copyOf(items), !page.hasNext());
    }

    private NotificationCandidateResult loadApprovalNotificationCandidates(Long userId, int limit, WorkflowIdentityLookup workflowLookup) {
        if (limit <= 0) {
            return NotificationCandidateResult.empty();
        }
        List<MessageCenterModels.Item> items = new ArrayList<>();
        int pageNum = 0;
        int batchSize = Math.max(limit * MERGE_BATCH_MULTIPLIER, NOTIFICATION_BATCH_SIZE);
        Page<UserNotification> page;
        do {
            page = userNotificationRepository.findApprovalLikeByRecipientUserId(userId, PageRequest.of(pageNum, batchSize));
            page.getContent().stream()
                    .filter(notification -> !isDuplicateApprovalNotification(notification, workflowLookup))
                    .map(this::toNotificationItem)
                    .forEach(items::add);
            pageNum++;
        } while (page.hasNext() && items.size() < limit);
        return new NotificationCandidateResult(List.copyOf(items), !page.hasNext());
    }

    private NotificationCandidateResult loadApprovalNotificationKeywordCandidates(Long userId, String keyword, int limit, WorkflowIdentityLookup workflowLookup) {
        if (limit <= 0) {
            return NotificationCandidateResult.empty();
        }
        List<MessageCenterModels.Item> items = new ArrayList<>();
        int pageNum = 0;
        int batchSize = Math.max(limit * MERGE_BATCH_MULTIPLIER, NOTIFICATION_BATCH_SIZE);
        Page<UserNotification> page;
        do {
            page = userNotificationRepository.findApprovalLikeByRecipientUserIdAndKeyword(userId, keyword, PageRequest.of(pageNum, batchSize));
            page.getContent().stream()
                    .filter(notification -> !isDuplicateApprovalNotification(notification, workflowLookup))
                    .map(this::toNotificationItem)
                    .forEach(items::add);
            pageNum++;
        } while (page.hasNext() && items.size() < limit);
        return new NotificationCandidateResult(List.copyOf(items), !page.hasNext());
    }

    private NotificationCandidateResult loadReminderNotificationCandidates(Long userId, int limit) {
        if (limit <= 0) {
            return NotificationCandidateResult.empty();
        }
        Page<UserNotification> page = userNotificationRepository.findReminderByRecipientUserId(
                userId,
                PageRequest.of(0, Math.max(limit, NOTIFICATION_BATCH_SIZE))
        );
        List<MessageCenterModels.Item> items = page.getContent().stream()
                .map(this::toNotificationItem)
                .toList();
        return new NotificationCandidateResult(items, !page.hasNext());
    }

    private NotificationCandidateResult loadReminderNotificationKeywordCandidates(Long userId, String keyword, int limit) {
        if (limit <= 0) {
            return NotificationCandidateResult.empty();
        }
        Page<UserNotification> page = userNotificationRepository.findReminderByRecipientUserIdAndKeyword(
                userId,
                keyword,
                PageRequest.of(0, Math.max(limit, NOTIFICATION_BATCH_SIZE))
        );
        return new NotificationCandidateResult(page.getContent().stream().map(this::toNotificationItem).toList(), !page.hasNext());
    }

    private NotificationCandidateResult loadSystemNotificationCandidates(Long userId, int limit) {
        if (limit <= 0) {
            return NotificationCandidateResult.empty();
        }
        List<MessageCenterModels.Item> items = new ArrayList<>();
        int pageNum = 0;
        int batchSize = Math.max(limit * MERGE_BATCH_MULTIPLIER, NOTIFICATION_BATCH_SIZE);
        Page<UserNotification> page;
        do {
            page = userNotificationRepository.findByRecipientUserId(userId, PageRequest.of(pageNum, batchSize));
            page.getContent().stream()
                    .filter(notification -> !isApprovalLikeNotification(notification))
                    .filter(notification -> !BIZ_REMINDER_NOTICE.equals(resolveNotificationBizType(notification)))
                    .map(this::toNotificationItem)
                    .forEach(items::add);
            pageNum++;
        } while (page.hasNext() && items.size() < limit);
        return new NotificationCandidateResult(List.copyOf(items), !page.hasNext());
    }

    private NotificationCandidateResult loadSystemNotificationKeywordCandidates(Long userId, String keyword, int limit) {
        if (limit <= 0) {
            return NotificationCandidateResult.empty();
        }
        List<MessageCenterModels.Item> items = new ArrayList<>();
        int pageNum = 0;
        int batchSize = Math.max(limit * MERGE_BATCH_MULTIPLIER, NOTIFICATION_BATCH_SIZE);
        Page<UserNotification> page;
        do {
            page = userNotificationRepository.findByRecipientUserIdAndKeyword(userId, keyword, PageRequest.of(pageNum, batchSize));
            page.getContent().stream()
                    .filter(notification -> !isApprovalLikeNotification(notification))
                    .filter(notification -> !BIZ_REMINDER_NOTICE.equals(resolveNotificationBizType(notification)))
                    .map(this::toNotificationItem)
                    .forEach(items::add);
            pageNum++;
        } while (page.hasNext() && items.size() < limit);
        return new NotificationCandidateResult(List.copyOf(items), !page.hasNext());
    }

    private long countDuplicateApprovalNotifications(Long userId, WorkflowIdentityLookup workflowLookup) {
        long duplicates = 0;
        int pageNum = 0;
        Page<UserNotification> page;
        do {
            page = userNotificationRepository.findApprovalLikeByRecipientUserId(userId, PageRequest.of(pageNum, NOTIFICATION_BATCH_SIZE));
            duplicates += page.getContent().stream()
                    .filter(notification -> isDuplicateApprovalNotification(notification, workflowLookup))
                    .count();
            pageNum++;
        } while (page.hasNext());
        return duplicates;
    }

    private long countDuplicateApprovalNotificationsByKeyword(Long userId, String keyword, WorkflowIdentityLookup workflowLookup) {
        long duplicates = 0;
        int pageNum = 0;
        Page<UserNotification> page;
        do {
            page = userNotificationRepository.findApprovalLikeByRecipientUserIdAndKeyword(userId, keyword, PageRequest.of(pageNum, NOTIFICATION_BATCH_SIZE));
            duplicates += page.getContent().stream()
                    .filter(notification -> isDuplicateApprovalNotification(notification, workflowLookup))
                    .count();
            pageNum++;
        } while (page.hasNext());
        return duplicates;
    }

    private WorkflowKeywordResult loadWorkflowKeywordMatches(Long userId, String keyword, int limit) {
        if (limit <= 0) {
            return WorkflowKeywordResult.empty();
        }
        List<MessageCenterModels.Item> matches = new ArrayList<>();
        long totalMatches = 0;
        int pageNum = 1;
        PageResult<WorkflowTaskResponse> currentPage;
        do {
            currentPage = workflowReadModelService.getMyPendingTasks(userId, pageNum);
            List<WorkflowTaskResponse> pageItems = currentPage.getItems() == null ? List.of() : currentPage.getItems();
            for (WorkflowTaskResponse task : pageItems) {
                MessageCenterModels.Item item = toWorkflowItem(task);
                if (matchesKeyword(item, keyword)) {
                    totalMatches++;
                    if (matches.size() < limit) {
                        matches.add(item);
                    }
                }
            }
            pageNum++;
        } while (currentPage.getPageNum() < currentPage.getTotalPages());
        return new WorkflowKeywordResult(mergeAndSortItems(matches, List.of()), totalMatches);
    }

    private MessageCenterModels.ListResponse pageItems(
            List<MessageCenterModels.Item> items,
            long total,
            int safePageNum,
            int safePageSize,
            boolean partialSuccess,
            List<String> unavailableSources) {
        int startIndex = Math.min((safePageNum - 1) * safePageSize, items.size());
        int endIndex = Math.min(startIndex + safePageSize, items.size());
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / safePageSize);
        return new MessageCenterModels.ListResponse(
                items.subList(startIndex, endIndex),
                total,
                safePageNum,
                safePageSize,
                totalPages,
                partialSuccess,
                List.copyOf(unavailableSources),
                defaultCapabilities()
        );
    }

    private MessageCenterModels.ListResponse emptyListResponse(
            int safePageNum,
            int safePageSize,
            boolean partialSuccess,
            List<String> unavailableSources) {
        return new MessageCenterModels.ListResponse(
                List.of(),
                0,
                safePageNum,
                safePageSize,
                0,
                partialSuccess,
                List.copyOf(unavailableSources),
                defaultCapabilities()
        );
    }

    private List<MessageCenterModels.Item> mergeAndSortItems(
            List<MessageCenterModels.Item> workflowItems,
            List<MessageCenterModels.Item> notificationItems) {
        List<MessageCenterModels.Item> items = new ArrayList<>(workflowItems.size() + notificationItems.size());
        items.addAll(workflowItems);
        items.addAll(notificationItems);
        items.sort(Comparator
                .comparing(MessageCenterModels.Item::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(MessageCenterModels.Item::priority, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(MessageCenterModels.Item::messageId));
        return List.copyOf(items);
    }

    private MessageCenterModels.Item toWorkflowItem(WorkflowTaskResponse task) {
        Long approvalInstanceId = toLong(task.getInstanceId());
        Long entityId = task.getEntityId();
        String entityType = normalize(task.getEntityType());
        // 2026-10-18 用户反馈：消息标题要先讲清是哪类审批（上报审批/计划审批等），
        // 再讲是哪个部门到哪个部门；工作流侧的占位计划名（Plan 4039/业务对象#4039）对用户无意义。
        String sourceOrg = firstNonBlank(task.getSourceOrgName(), task.getAssigneeName(), "相关部门");
        String targetOrg = firstNonBlank(task.getTargetOrgName(), sourceOrg);
        String flowLabel = sourceOrg.equals(targetOrg) ? sourceOrg : sourceOrg + "→" + targetOrg;
        String title = switch (entityType) {
            case "PLAN" -> "计划审批 · " + flowLabel;
            case "PLAN_REPORT" -> "上报审批 · " + flowLabel;
            case "INDICATOR" -> "异动审批 · " + flowLabel;
            case "INDICATOR_DISTRIBUTION" -> "下发审批 · " + flowLabel;
            default -> "审批事项 · " + flowLabel;
        };
        String applicant = firstNonBlank(task.getSourceOrgName(), task.getTargetOrgName(), task.getAssigneeName(), "相关部门");
        String stepName = firstNonBlank(task.getCurrentStepName(), task.getTaskName(), "当前审批环节");
        String businessName = firstNonBlank(task.getPlanName(), entityId == null ? null : "业务对象#" + entityId);
        if (businessName == null || businessName.matches("Plan\\s+\\d+") || businessName.matches("业务对象#\\d+")) {
            businessName = targetOrg + "计划";
        }
        String summary = flowLabel + "：" + applicant + "提交了“" + businessName + "”，当前环节为“" + stepName + "”，请及时处理。";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("flowCode", task.getFlowCode());
        metadata.put("flowName", task.getFlowName());
        metadata.put("sourceOrgName", task.getSourceOrgName());
        metadata.put("targetOrgName", task.getTargetOrgName());
        metadata.put("planName", task.getPlanName());

        return new MessageCenterModels.Item(
                buildWorkflowMessageId(task),
                "workflow",
                String.valueOf(task.getTaskId()),
                CATEGORY_TODO,
                BIZ_APPROVAL_TODO,
                title,
                summary,
                summary,
                "P2",
                null,
                null,
                ACTION_REQUIRED,
                firstNonNull(task.getCreatedTime(), task.getStartedAt(), LocalDateTime.now()),
                firstNonNull(task.getCreatedTime(), task.getStartedAt(), LocalDateTime.now()),
                resolveWorkflowActionUrl(task, approvalInstanceId),
                entityType,
                entityId,
                approvalInstanceId,
                stepName,
                task.getAssigneeName(),
                applicant,
                false,
                true,
                true,
                metadata
        );
    }

    private MessageCenterModels.Item toNotificationItem(UserNotification notification) {
        String bizType = resolveNotificationBizType(notification);
        String category = resolveCategoryByBizType(bizType);
        boolean isRead = READ_READ.equalsIgnoreCase(notification.getStatus());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("notificationType", notification.getNotificationType());
        metadata.put("batchKey", notification.getBatchKey());
        metadata.put("metadataJson", notification.getMetadataJson());

        return new MessageCenterModels.Item(
                "notification:" + notification.getId(),
                "notification",
                String.valueOf(notification.getId()),
                category,
                bizType,
                firstNonBlank(notification.getTitle(), "系统通知"),
                summarize(notification.getContent()),
                firstNonBlank(notification.getContent(), ""),
                BIZ_REMINDER_NOTICE.equals(bizType) ? "P2" : "P3",
                null,
                isRead ? READ_READ : READ_UNREAD,
                null,
                firstNonNull(notification.getCreatedAt(), LocalDateTime.now()),
                firstNonNull(notification.getCreatedAt(), LocalDateTime.now()),
                normalizeInternalUrl(notification.getActionUrl()),
                normalize(notification.getRelatedEntityType()),
                notification.getRelatedEntityId(),
                extractApprovalInstanceId(notification.getActionUrl()),
                null,
                null,
                buildSenderDisplay(notification),
                true,
                true,
                false,
                metadata
        );
    }

    private boolean isDuplicateApprovalNotification(UserNotification notification, WorkflowIdentityLookup workflowLookup) {
        if (!isApprovalLikeNotification(notification) || workflowLookup.isEmpty()) {
            return false;
        }
        Long approvalInstanceId = extractApprovalInstanceId(notification.getActionUrl());
        if (approvalInstanceId != null && workflowLookup.instanceIds().contains(approvalInstanceId)) {
            return true;
        }
        String entityKey = buildEntityKey(notification.getRelatedEntityType(), notification.getRelatedEntityId());
        return entityKey != null && workflowLookup.entityKeys().contains(entityKey);
    }

    private boolean isDuplicateApprovalNotification(UserNotification notification, Set<String> workflowIdentityKeys) {
        if (!isApprovalLikeNotification(notification) || workflowIdentityKeys.isEmpty()) {
            return false;
        }
        Long approvalInstanceId = extractApprovalInstanceId(notification.getActionUrl());
        if (approvalInstanceId != null && workflowIdentityKeys.contains("instance:" + approvalInstanceId)) {
            return true;
        }
        String entityType = normalize(notification.getRelatedEntityType());
        return StringUtils.hasText(entityType)
                && notification.getRelatedEntityId() != null
                && workflowIdentityKeys.contains("entity:" + entityType + ":" + notification.getRelatedEntityId());
    }

    private Set<String> buildWorkflowIdentityKeys(WorkflowTaskResponse task) {
        Set<String> keys = new LinkedHashSet<>();
        Long instanceId = toLong(task.getInstanceId());
        if (instanceId != null) {
            keys.add("instance:" + instanceId);
        }
        String entityType = normalize(task.getEntityType());
        if (StringUtils.hasText(entityType) && task.getEntityId() != null) {
            keys.add("entity:" + entityType + ":" + task.getEntityId());
        }
        return keys;
    }

    private String resolveNotificationBizType(UserNotification notification) {
        String notificationType = normalize(notification.getNotificationType());
        if ("REMINDER".equals(notificationType)) {
            return BIZ_REMINDER_NOTICE;
        }
        if ("OVERDUE".equals(notificationType)) {
            return BIZ_REMINDER_NOTICE;
        }
        if ("ALERT".equals(notificationType)) {
            return BIZ_REMINDER_NOTICE;
        }
        if ("SYSTEM_ANNOUNCEMENT".equals(notificationType)) {
            return BIZ_SYSTEM_NOTICE;
        }
        if (isApprovalLikeNotification(notification)) {
            return BIZ_APPROVAL_RESULT;
        }
        if (notificationType.contains("BUSINESS")) {
            return BIZ_BUSINESS_NOTICE;
        }
        return BIZ_SYSTEM_NOTICE;
    }

    private boolean isApprovalLikeNotification(UserNotification notification) {
        String notificationType = normalize(notification.getNotificationType());
        String title = normalize(notification.getTitle());
        String content = normalize(notification.getContent());
        return notificationType.contains("APPROVAL")
                || title.contains("审批")
                || title.contains("审核")
                || content.contains("审批")
                || content.contains("审核");
    }

    private String resolveCategoryByBizType(String bizType) {
        return switch (bizType) {
            case BIZ_APPROVAL_TODO -> CATEGORY_TODO;
            case BIZ_APPROVAL_RESULT -> CATEGORY_APPROVAL;
            case BIZ_REMINDER_NOTICE -> CATEGORY_REMINDER;
            default -> CATEGORY_SYSTEM;
        };
    }

    private boolean matchesCategory(MessageCenterModels.Item item, String category) {
        String normalizedCategory = normalize(category);
        if (!StringUtils.hasText(normalizedCategory) || CATEGORY_ALL.equals(normalizedCategory)) {
            return true;
        }
        return switch (normalizedCategory) {
            case CATEGORY_TODO -> BIZ_APPROVAL_TODO.equals(item.bizType());
            case CATEGORY_APPROVAL -> BIZ_APPROVAL_TODO.equals(item.bizType()) || BIZ_APPROVAL_RESULT.equals(item.bizType());
            case CATEGORY_REMINDER -> BIZ_REMINDER_NOTICE.equals(item.bizType());
            case CATEGORY_SYSTEM -> BIZ_SYSTEM_NOTICE.equals(item.bizType()) || BIZ_BUSINESS_NOTICE.equals(item.bizType());
            case CATEGORY_RISK -> CATEGORY_RISK.equals(item.category());
            default -> true;
        };
    }

    private boolean matchesKeyword(MessageCenterModels.Item item, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        String normalizedKeyword = keyword.trim().toLowerCase(Locale.ROOT);
        return String.valueOf(item.title()).toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                || String.valueOf(item.summary()).toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                || String.valueOf(item.content()).toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                || String.valueOf(item.currentStepName()).toLowerCase(Locale.ROOT).contains(normalizedKeyword);
    }

    private boolean shouldInclude(MessageCenterModels.Item item, Boolean includeRisk) {
        return !CATEGORY_RISK.equals(item.category()) || Boolean.TRUE.equals(includeRisk);
    }

    private String buildWorkflowMessageId(WorkflowTaskResponse task) {
        return "workflow:" + task.getInstanceId() + ":" + task.getTaskId();
    }

    private String resolveWorkflowActionUrl(WorkflowTaskResponse task, Long approvalInstanceId) {
        String claimUrl = normalizeInternalUrl(task.getClaimUrl());
        if (StringUtils.hasText(claimUrl)) {
            return claimUrl;
        }

        String entityType = normalize(task.getEntityType());
        if ("PLAN".equals(entityType) || "PLAN_REPORT".equals(entityType) || "TASK".equals(entityType)) {
            return approvalInstanceId == null
                    ? "/strategic-tasks"
                    : "/strategic-tasks?tab=approval&approvalInstanceId=" + approvalInstanceId;
        }
        if ("INDICATOR".equals(entityType)) {
            return task.getEntityId() == null ? "/indicators" : "/indicators/" + task.getEntityId();
        }
        if ("INDICATOR_DISTRIBUTION".equals(entityType)) {
            return "/distribution";
        }
        return "/workflow-tasks";
    }

    private String normalizeInternalUrl(String actionUrl) {
        if (!StringUtils.hasText(actionUrl)) {
            return null;
        }
        String trimmed = actionUrl.trim();
        if (!trimmed.startsWith("/")) {
            return null;
        }
        return trimmed;
    }

    private Long extractApprovalInstanceId(String actionUrl) {
        if (!StringUtils.hasText(actionUrl)) {
            return null;
        }
        try {
            String value = UriComponentsBuilder.fromUriString(actionUrl.startsWith("/") ? "https://local.test" + actionUrl : actionUrl)
                    .build()
                    .getQueryParams()
                    .getFirst("approvalInstanceId");
            Long parsed = toLong(value);
            if (parsed != null) {
                return parsed;
            }
        } catch (IllegalArgumentException ex) {
            // Fall through to a minimal parser for malformed or relative URLs.
        }
        int queryIndex = actionUrl.indexOf('?');
        if (queryIndex < 0 || queryIndex == actionUrl.length() - 1) {
            return null;
        }
        String query = actionUrl.substring(queryIndex + 1);
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && "approvalInstanceId".equals(parts[0])) {
                return toLong(parts[1]);
            }
        }
        return null;
    }

    private String summarize(String content) {
        String normalized = firstNonBlank(content, "").replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 117) + "...";
    }

    private String buildSenderDisplay(UserNotification notification) {
        if (notification.getSenderUserId() != null) {
            return "用户#" + notification.getSenderUserId();
        }
        if (notification.getSenderOrgId() != null) {
            return "组织#" + notification.getSenderOrgId();
        }
        return "系统";
    }

    private ParsedMessageId parseMessageId(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            throw new IllegalArgumentException("messageId 不能为空");
        }
        if (messageId.startsWith("notification:")) {
            String sourceId = messageId.substring("notification:".length());
            Long notificationId = toLong(sourceId);
            if (notificationId == null) {
                throw new IllegalArgumentException("无效的通知消息标识: " + messageId);
            }
            return new ParsedMessageId("notification", sourceId, null, notificationId);
        }
        if (messageId.startsWith("workflow:")) {
            String[] parts = messageId.split(":", 3);
            if (parts.length < 3) {
                throw new IllegalArgumentException("无效的审批待办标识: " + messageId);
            }
            return new ParsedMessageId("workflow", parts[2], parts[1], null);
        }
        throw new IllegalArgumentException("不支持的消息标识: " + messageId);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String buildEntityKey(String entityType, Long entityId) {
        String normalizedEntityType = normalize(entityType);
        if (!StringUtils.hasText(normalizedEntityType) || entityId == null) {
            return null;
        }
        return normalizedEntityType + ":" + entityId;
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate.trim();
            }
        }
        return null;
    }

    private <T> T firstNonNull(T... candidates) {
        if (candidates == null) {
            return null;
        }
        for (T candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String normalized = String.valueOf(value).trim();
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        return null;
    }

    private MessageCenterModels.Capabilities defaultCapabilities() {
        return new MessageCenterModels.Capabilities(false, true, true);
    }

    private record Aggregation(
            List<MessageCenterModels.Item> items,
            MessageCenterModels.Capabilities capabilities,
            boolean partialSuccess,
            List<String> unavailableSources,
            LocalDateTime lastRefreshTime
    ) {
    }

    private record ParsedMessageId(
            String sourceType,
            String sourceId,
            String instanceId,
            Long notificationId
    ) {
    }

    private record NotificationCandidateResult(
            List<MessageCenterModels.Item> items,
            boolean exhausted
    ) {
        private static NotificationCandidateResult empty() {
            return new NotificationCandidateResult(List.of(), true);
        }
    }

    private record WorkflowKeywordResult(
            List<MessageCenterModels.Item> items,
            long total
    ) {
        private static WorkflowKeywordResult empty() {
            return new WorkflowKeywordResult(List.of(), 0);
        }
    }

    private record WorkflowIdentityLookup(
            Set<Long> instanceIds,
            Set<String> entityKeys
    ) {
        private static WorkflowIdentityLookup from(List<WorkflowQueryRepository.PendingTaskIdentity> taskIdentities) {
            if (taskIdentities == null || taskIdentities.isEmpty()) {
                return empty();
            }
            Set<Long> instanceIds = taskIdentities.stream()
                    .map(WorkflowQueryRepository.PendingTaskIdentity::instanceId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> entityKeys = taskIdentities.stream()
                    .map(taskIdentity -> buildStaticEntityKey(taskIdentity.entityType(), taskIdentity.entityId()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            return new WorkflowIdentityLookup(Set.copyOf(instanceIds), Set.copyOf(entityKeys));
        }

        private static WorkflowIdentityLookup fromItems(List<MessageCenterModels.Item> workflowItems) {
            if (workflowItems == null || workflowItems.isEmpty()) {
                return empty();
            }
            Set<Long> instanceIds = workflowItems.stream()
                    .map(MessageCenterModels.Item::approvalInstanceId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> entityKeys = workflowItems.stream()
                    .map(item -> buildStaticEntityKey(item.entityType(), item.entityId()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            return new WorkflowIdentityLookup(Set.copyOf(instanceIds), Set.copyOf(entityKeys));
        }

        private static WorkflowIdentityLookup empty() {
            return new WorkflowIdentityLookup(Set.of(), Set.of());
        }

        private boolean isEmpty() {
            return instanceIds.isEmpty() && entityKeys.isEmpty();
        }

        private static String buildStaticEntityKey(String entityType, Long entityId) {
            if (entityType == null || entityType.isBlank() || entityId == null) {
                return null;
            }
            return entityType.trim().toUpperCase(Locale.ROOT) + ":" + entityId;
        }
    }
}
