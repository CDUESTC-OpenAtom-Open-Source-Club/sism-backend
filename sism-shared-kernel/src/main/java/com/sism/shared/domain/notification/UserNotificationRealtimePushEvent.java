package com.sism.shared.domain.notification;

/**
 * 消息中心通知落库后的实时推送事件（2026-10-07）。
 *
 * <p>背景：审批提交/通过/驳回此前只在消息中心落库，接收方页面上的
 * 「处理审批/查看审批」按钮与状态标签要等心跳兜底或手动刷新才更新。</p>
 *
 * <p>发布方：sism-iam UserNotificationService（审批待办/审批结果通知创建后）。
 * 监听方：sism-main 在事务提交后通过 WebSocketNotificationService 推送给
 * 接收人，前端按数据域（plan/indicator）定向刷新并弹出提示。</p>
 */
public record UserNotificationRealtimePushEvent(
        Long recipientUserId,
        Long senderUserId,
        String type,
        String title,
        String content,
        String entityType,
        Long entityId,
        Long approvalInstanceId,
        String stepName
) {
}
