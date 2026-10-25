package com.sism.shared.domain.notification;

/**
 * 2026-10-24 跨窗口数据同步端口：把「业务数据已变更」广播给所有在线客户端。
 *
 * 适用场景：不走审批流的数据变化（如草稿态指标的直接编辑）没有审批通知可搭车，
 * 其他窗口会停留在旧值——由业务模块在变更成功后调用本端口广播
 * {entityType, entityId}，前端据此做定向局部刷新。
 * 实现必须 fail-safe：广播失败不能影响业务事务。
 */
public interface DataChangeBroadcaster {

    void publishDataChanged(String entityType, Long entityId);
}
