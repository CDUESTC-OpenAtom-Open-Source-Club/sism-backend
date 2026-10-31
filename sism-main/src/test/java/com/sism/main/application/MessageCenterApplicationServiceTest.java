package com.sism.main.application;

import com.sism.iam.application.service.UserNotificationService;
import com.sism.iam.domain.announcement.AnnouncementStatus;
import com.sism.iam.domain.announcement.SystemAnnouncement;
import com.sism.iam.domain.announcement.SystemAnnouncementRepository;
import com.sism.iam.domain.notification.UserNotification;
import com.sism.iam.domain.notification.UserNotificationRepository;
import com.sism.main.interfaces.dto.MessageCenterModels;
import com.sism.workflow.application.query.WorkflowReadModelService;
import com.sism.workflow.interfaces.dto.PageResult;
import com.sism.workflow.interfaces.dto.WorkflowTaskResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Message Center Application Service Tests")
class MessageCenterApplicationServiceTest {

    @Mock
    private UserNotificationRepository userNotificationRepository;

    @Mock
    private SystemAnnouncementRepository systemAnnouncementRepository;

    @Mock
    private UserNotificationService userNotificationService;

    @Mock
    private WorkflowReadModelService workflowReadModelService;

    @InjectMocks
    private MessageCenterApplicationService messageCenterApplicationService;

    @Test
    @DisplayName("Should use lightweight summary path and optimized list merge")
    void shouldUseLightweightSummaryPathAndOptimizedListMerge() {
        UserNotification reminder = notification(101L, "REMINDER", "催办提醒", "请尽快更新进展", "UNREAD", "/indicators/88", "INDICATOR", 88L);
        UserNotification duplicateApprovalNotification = notification(
                102L,
                "SYSTEM",
                "有新的计划待审批",
                "请及时处理。",
                "UNREAD",
                "/strategic-tasks?tab=approval&approvalInstanceId=12",
                "PLAN",
                101L
        );
        WorkflowTaskResponse pendingTask = WorkflowTaskResponse.builder()
                .taskId("501")
                .instanceId("12")
                .taskName("部门负责人审批")
                .currentStepName("部门负责人审批")
                .entityType("PLAN")
                .entityId(101L)
                .planName("2026年度计划")
                .sourceOrgName("计算机学院")
                .assigneeName("李主任")
                .createdTime(LocalDateTime.of(2026, 4, 17, 9, 0))
                .build();

        when(workflowReadModelService.countMyPendingTodoMessages(8L)).thenReturn(1L);
        // 2026-10-30 起聚合需要全量在途待办：改为分页扫描（getMyPendingTasks(userId, pageNum)）
        when(workflowReadModelService.getMyPendingTasks(8L, 1)).thenReturn(PageResult.of(List.of(pendingTask), 1, 1, 10));
        when(userNotificationRepository.countByRecipientUserIdAndStatus(8L, "UNREAD")).thenReturn(2L);
        when(userNotificationRepository.countApprovalLikeUnreadByRecipientUserId(8L)).thenReturn(1L);
        when(userNotificationRepository.countReminderByRecipientUserIdAndStatus(8L, "UNREAD")).thenReturn(1L);
        when(userNotificationRepository.countByRecipientUserId(8L)).thenReturn(2L);
        when(userNotificationRepository.findApprovalLikeByRecipientUserId(eq(8L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(duplicateApprovalNotification), PageRequest.of(0, 200), 1));
        when(userNotificationRepository.findByRecipientUserId(eq(8L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(reminder, duplicateApprovalNotification), PageRequest.of(0, 200), 2));

        MessageCenterModels.Summary summary = messageCenterApplicationService.getSummary(8L);
        MessageCenterModels.ListResponse listResponse = messageCenterApplicationService.getMessages(8L, "ALL", 1, 100, null, true);

        assertEquals(3, summary.totalCount());
        assertEquals(1, summary.todoCount());
        assertEquals(1, summary.reminderCount());
        assertEquals(2, summary.approvalCount());
        assertEquals(2, listResponse.items().size());
        assertTrue(listResponse.items().stream().anyMatch(item -> "APPROVAL_TODO".equals(item.bizType())));
        assertTrue(listResponse.items().stream().anyMatch(item -> "REMINDER_NOTICE".equals(item.bizType())));
        verify(workflowReadModelService, never()).getMyPendingTasks(8L, 1, 100);
    }

    @Test
    @DisplayName("Should return notification detail and support read-all result mapping")
    void shouldReturnNotificationDetailAndSupportReadAllResultMapping() {
        UserNotification notification = notification(
                201L,
                "SYSTEM",
                "系统公告",
                "本周日晚间系统维护，请提前保存数据。",
                "READ",
                "/messages",
                null,
                null
        );
        when(userNotificationRepository.findByIdAndRecipientUserId(201L, 9L)).thenReturn(java.util.Optional.of(notification));
        when(userNotificationService.markAllNotificationsAsRead(9L)).thenReturn(Map.of(
                "readCount", 3L,
                "timestamp", LocalDateTime.of(2026, 4, 17, 10, 0)
        ));

        MessageCenterModels.Item detail = messageCenterApplicationService.getMessageDetail(9L, "notification:201");
        MessageCenterModels.ReadResult readResult = messageCenterApplicationService.markAllAsRead(9L);

        assertEquals("notification:201", detail.messageId());
        assertEquals("SYSTEM_NOTICE", detail.bizType());
        assertEquals("READ", detail.readState());
        assertNotNull(detail.content());
        assertEquals(3, readResult.affectedCount());
        assertEquals("READ", readResult.status());
    }

    @Test
    @DisplayName("Should resolve workflow detail by direct pending task lookup")
    void shouldResolveWorkflowDetailByDirectPendingTaskLookup() {
        WorkflowTaskResponse pendingTask = WorkflowTaskResponse.builder()
                .taskId("701")
                .instanceId("21")
                .taskName("战略发展部审批")
                .currentStepName("战略发展部审批")
                .entityType("PLAN")
                .entityId(301L)
                .planName("2026重点任务")
                .sourceOrgName("教务处")
                .assigneeName("张主任")
                .createdTime(LocalDateTime.of(2026, 4, 17, 11, 0))
                .build();
        when(workflowReadModelService.findMyPendingTaskById(9L, "701")).thenReturn(Optional.of(pendingTask));

        MessageCenterModels.Item detail = messageCenterApplicationService.getMessageDetail(9L, "workflow:21:701");

        assertEquals("workflow:21:701", detail.messageId());
        assertEquals("APPROVAL_TODO", detail.bizType());
        assertEquals("ACTION_REQUIRED", detail.actionState());
        verify(workflowReadModelService, never()).getMyPendingTasks(9L, 1);
    }

    @Test
    @DisplayName("Should optimize reminder category without full aggregation")
    void shouldOptimizeReminderCategoryWithoutFullAggregation() {
        UserNotification reminder = notification(301L, "REMINDER", "催办提醒", "请尽快处理", "UNREAD", "/indicators/11", "INDICATOR", 11L);
        when(userNotificationRepository.countReminderByRecipientUserId(5L)).thenReturn(1L);
        when(userNotificationRepository.findReminderByRecipientUserId(eq(5L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(reminder), PageRequest.of(0, 200), 1));

        MessageCenterModels.ListResponse listResponse = messageCenterApplicationService.getMessages(5L, "REMINDER", 1, 100, null, false);

        assertEquals(1, listResponse.total());
        assertEquals(1, listResponse.items().size());
        assertEquals("REMINDER_NOTICE", listResponse.items().get(0).bizType());
        verify(workflowReadModelService, never()).getMyPendingTasks(5L, 1);
    }

    @Test
    @DisplayName("Should optimize system category without full aggregation")
    void shouldOptimizeSystemCategoryWithoutFullAggregation() {
        UserNotification system = notification(401L, "SYSTEM", "系统公告", "维护通知", "UNREAD", "/messages", null, null);
        UserNotification reminder = notification(402L, "REMINDER", "催办提醒", "请尽快处理", "UNREAD", "/indicators/11", "INDICATOR", 11L);
        UserNotification approval = notification(403L, "SYSTEM", "有新的计划待审批", "请及时处理。", "UNREAD", "/strategic-tasks?tab=approval&approvalInstanceId=88", "PLAN", 88L);

        when(userNotificationRepository.countByRecipientUserId(6L)).thenReturn(3L);
        when(userNotificationRepository.countReminderByRecipientUserId(6L)).thenReturn(1L);
        when(userNotificationRepository.countApprovalLikeByRecipientUserId(6L)).thenReturn(1L);
        when(userNotificationRepository.findByRecipientUserId(eq(6L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(system, reminder, approval), PageRequest.of(0, 200), 3));

        MessageCenterModels.ListResponse listResponse = messageCenterApplicationService.getMessages(6L, "SYSTEM", 1, 100, null, false);

        assertEquals(1, listResponse.total());
        assertEquals(1, listResponse.items().size());
        assertEquals("SYSTEM_NOTICE", listResponse.items().get(0).bizType());
        verify(workflowReadModelService, never()).getMyPendingTasks(6L, 1);
    }

    @Test
    @DisplayName("Should clean up withdrawn announcement notifications before listing messages")
    void shouldCleanUpWithdrawnAnnouncementNotificationsBeforeListingMessages() {
        UserNotification announcementNotification = notification(
                410L,
                "SYSTEM_ANNOUNCEMENT",
                "系统维护公告",
                "维护通知",
                "UNREAD",
                "/announcements/4",
                "ANNOUNCEMENT",
                4L
        );
        SystemAnnouncement withdrawnAnnouncement = new SystemAnnouncement();
        withdrawnAnnouncement.setId(4L);
        withdrawnAnnouncement.setStatus(AnnouncementStatus.WITHDRAWN);

        when(userNotificationRepository.findByRecipientUserIdAndNotificationType(eq(6L), eq("SYSTEM_ANNOUNCEMENT"), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(announcementNotification), PageRequest.of(0, 200), 1));
        when(systemAnnouncementRepository.findById(4L)).thenReturn(Optional.of(withdrawnAnnouncement));
        when(userNotificationRepository.countByRecipientUserId(6L)).thenReturn(0L);
        when(userNotificationRepository.countReminderByRecipientUserId(6L)).thenReturn(0L);
        when(userNotificationRepository.countApprovalLikeByRecipientUserId(6L)).thenReturn(0L);
        when(userNotificationRepository.findByRecipientUserId(eq(6L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 200), 0));

        MessageCenterModels.ListResponse listResponse = messageCenterApplicationService.getMessages(6L, "SYSTEM", 1, 100, null, false);

        assertEquals(0, listResponse.total());
        assertTrue(listResponse.items().isEmpty());
        verify(userNotificationRepository).deleteByNotificationTypeAndRelatedEntityTypeAndRelatedEntityId(
                "SYSTEM_ANNOUNCEMENT",
                "ANNOUNCEMENT",
                4L
        );
    }

    @Test
    @DisplayName("Should optimize reminder keyword search without full aggregation")
    void shouldOptimizeReminderKeywordSearchWithoutFullAggregation() {
        UserNotification reminder = notification(501L, "REMINDER", "催办提醒", "请尽快更新专项进展", "UNREAD", "/indicators/11", "INDICATOR", 11L);
        when(userNotificationRepository.countReminderByRecipientUserIdAndKeyword(7L, "专项")).thenReturn(1L);
        when(userNotificationRepository.findReminderByRecipientUserIdAndKeyword(eq(7L), eq("专项"), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(reminder), PageRequest.of(0, 200), 1));

        MessageCenterModels.ListResponse listResponse = messageCenterApplicationService.getMessages(7L, "REMINDER", 1, 100, "专项", false);

        assertEquals(1, listResponse.total());
        assertEquals(1, listResponse.items().size());
        assertEquals("REMINDER_NOTICE", listResponse.items().get(0).bizType());
        verify(workflowReadModelService, never()).getMyPendingTasks(7L, 1);
    }

    @Test
    @DisplayName("Should optimize todo keyword search with workflow page scan")
    void shouldOptimizeTodoKeywordSearchWithWorkflowPageScan() {
        WorkflowTaskResponse matchedTask = WorkflowTaskResponse.builder()
                .taskId("801")
                .instanceId("31")
                .taskName("学院专项审批")
                .currentStepName("学院专项审批")
                .entityType("PLAN")
                .entityId(901L)
                .planName("专项建设计划")
                .sourceOrgName("计算机学院")
                .assigneeName("李主任")
                .createdTime(LocalDateTime.of(2026, 4, 18, 9, 0))
                .build();
        WorkflowTaskResponse otherTask = WorkflowTaskResponse.builder()
                .taskId("802")
                .instanceId("32")
                .taskName("普通审批")
                .currentStepName("普通审批")
                .entityType("PLAN")
                .entityId(902L)
                .planName("日常计划")
                .sourceOrgName("教务处")
                .assigneeName("王主任")
                .createdTime(LocalDateTime.of(2026, 4, 18, 8, 0))
                .build();
        when(workflowReadModelService.getMyPendingTasks(10L, 1))
                .thenReturn(PageResult.of(List.of(matchedTask, otherTask), 2, 1, 10));

        MessageCenterModels.ListResponse listResponse = messageCenterApplicationService.getMessages(10L, "TODO", 1, 100, "专项", false);

        assertEquals(1, listResponse.total());
        assertEquals(1, listResponse.items().size());
        assertEquals("workflow:31:801", listResponse.items().get(0).messageId());
    }

    @Test
    @DisplayName("2026-10-30 定案：同批次异动多实例聚合为 1 条待办，计数按批次=1")
    void shouldAggregateSameBatchMutationTodosIntoOneMessage() {
        List<WorkflowTaskResponse> tasks = List.of(
                mutationTask("batch-88", 21L, 501L, "战略发展部", "党委宣传部"),
                mutationTask("batch-88", 22L, 502L, "战略发展部", "党委宣传部"),
                mutationTask("batch-88", 23L, 503L, "战略发展部", "党委宣传部"));
        when(workflowReadModelService.getMyPendingTasks(8L, 1)).thenReturn(PageResult.of(tasks, 3, 1, 10));
        when(workflowReadModelService.countMyPendingTodoMessages(8L)).thenReturn(1L);

        MessageCenterModels.Summary summary = messageCenterApplicationService.getSummary(8L);
        MessageCenterModels.ListResponse listResponse =
                messageCenterApplicationService.getMessages(8L, "TODO", 1, 100, null, false);

        assertEquals(1, summary.todoCount());
        assertEquals(1, listResponse.total());
        assertEquals(1, listResponse.items().size());
        MessageCenterModels.Item item = listResponse.items().get(0);
        assertEquals("workflow-mutation-batch:batch-88", item.messageId());
        // 文案与审批中心卡片一致，且不出现任何按条目计数的表述
        assertEquals("异动审批 · 战略发展部 -> 党委宣传部", item.title());
        assertEquals("batch-88", item.metadata().get("batchId"));
        assertEquals(3, ((List<?>) item.metadata().get("instanceIds")).size());
    }

    @Test
    @DisplayName("2026-10-30 定案：不同批次各自独立成条，PLAN/PLAN_REPORT 待办不受影响")
    void shouldKeepDistinctMutationBatchesAndUnaffectedOtherFlows() {
        WorkflowTaskResponse planTask = WorkflowTaskResponse.builder()
                .taskId("701")
                .instanceId("31")
                .taskName("部门负责人审批")
                .currentStepName("部门负责人审批")
                .entityType("PLAN")
                .entityId(901L)
                .planName("2026年度计划")
                .flowCode("PLAN_DISPATCH_STRATEGY")
                .sourceOrgName("教务处")
                .assigneeName("李主任")
                .createdTime(LocalDateTime.of(2026, 4, 17, 12, 0))
                .build();
        WorkflowTaskResponse reportTask = WorkflowTaskResponse.builder()
                .taskId("702")
                .instanceId("32")
                .taskName("学院审核人审批")
                .currentStepName("学院审核人审批")
                .entityType("PLAN_REPORT")
                .entityId(902L)
                .flowCode("PLAN_APPROVAL_COLLEGE")
                .sourceOrgName("计算机学院")
                .targetOrgName("教务处")
                .assigneeName("王主任")
                .createdTime(LocalDateTime.of(2026, 4, 17, 11, 0))
                .build();
        List<WorkflowTaskResponse> tasks = List.of(
                mutationTask("batch-a", 21L, 501L, "战略发展部", "党委宣传部"),
                mutationTask("batch-a", 22L, 502L, "战略发展部", "党委宣传部"),
                mutationTask("batch-b", 23L, 503L, "战略发展部", "校长办公室"),
                planTask,
                reportTask);
        when(workflowReadModelService.getMyPendingTasks(8L, 1)).thenReturn(PageResult.of(tasks, 5, 1, 10));
        // 批次a=1 + 批次b=1 + PLAN=1 + PLAN_REPORT=1
        when(workflowReadModelService.countMyPendingTodoMessages(8L)).thenReturn(4L);

        MessageCenterModels.ListResponse listResponse =
                messageCenterApplicationService.getMessages(8L, "TODO", 1, 100, null, false);

        assertEquals(4, listResponse.total());
        assertEquals(4, listResponse.items().size());
        assertTrue(listResponse.items().stream().anyMatch(item -> "workflow-mutation-batch:batch-a".equals(item.messageId())
                && "异动审批 · 战略发展部 -> 党委宣传部".equals(item.title())));
        assertTrue(listResponse.items().stream().anyMatch(item -> "workflow-mutation-batch:batch-b".equals(item.messageId())
                && "异动审批 · 战略发展部 -> 校长办公室".equals(item.title())));
        assertTrue(listResponse.items().stream().anyMatch(item -> "workflow:31:701".equals(item.messageId())
                && "计划审批 · 教务处".equals(item.title())));
        assertTrue(listResponse.items().stream().anyMatch(item -> "workflow:32:702".equals(item.messageId())
                && "上报审批 · 计算机学院→教务处".equals(item.title())));
    }

    @Test
    @DisplayName("2026-10-30 定案：批次级 messageId 可重建聚合后的待办消息详情")
    void shouldResolveMutationBatchMessageDetailByBatchId() {
        List<WorkflowTaskResponse> tasks = List.of(
                mutationTask("batch-88", 21L, 501L, "战略发展部", "党委宣传部"),
                mutationTask("batch-88", 22L, 502L, "战略发展部", "党委宣传部"));
        when(workflowReadModelService.getMyPendingTasksByBatchId(9L, "batch-88")).thenReturn(tasks);

        MessageCenterModels.Item detail =
                messageCenterApplicationService.getMessageDetail(9L, "workflow-mutation-batch:batch-88");

        assertEquals("workflow-mutation-batch:batch-88", detail.messageId());
        assertEquals("异动审批 · 战略发展部 -> 党委宣传部", detail.title());
        assertEquals("batch-88", detail.metadata().get("batchId"));
        assertEquals(2, ((List<?>) detail.metadata().get("instanceIds")).size());
    }

    private WorkflowTaskResponse mutationTask(
            String batchId, Long instanceId, Long taskId, String sourceOrgName, String targetOrgName) {
        return WorkflowTaskResponse.builder()
                .taskId(String.valueOf(taskId))
                .instanceId(String.valueOf(instanceId))
                .taskName("战略发展部负责人审批")
                .currentStepName("战略发展部负责人审批")
                .entityType("INDICATOR")
                .entityId(instanceId * 10)
                .flowCode("PLAN_MUTATION_STRATEGY")
                .flowName("指标异动审批（战略发展部）")
                .batchId(batchId)
                .sourceOrgName(sourceOrgName)
                .targetOrgName(targetOrgName)
                .assigneeName("审批人甲")
                .createdTime(LocalDateTime.of(2026, 4, 17, 9, 0))
                .build();
    }

    private UserNotification notification(
            Long id,
            String type,
            String title,
            String content,
            String status,
            String actionUrl,
            String entityType,
            Long entityId) {
        UserNotification notification = new UserNotification();
        notification.setId(id);
        notification.setNotificationType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setStatus(status);
        notification.setActionUrl(actionUrl);
        notification.setRelatedEntityType(entityType);
        notification.setRelatedEntityId(entityId);
        notification.setCreatedAt(LocalDateTime.of(2026, 4, 17, 8, 0));
        notification.setUpdatedAt(LocalDateTime.of(2026, 4, 17, 8, 0));
        return notification;
    }
}
