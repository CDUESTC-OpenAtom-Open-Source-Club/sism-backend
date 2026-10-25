package com.sism.config;

import com.sism.shared.domain.notification.DataChangeBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 2026-10-24 跨窗口数据同步：把 DATA_CHANGED 广播到全部在线 WebSocket 客户端。
 *
 * 时序保证：调用方都在事务内，若在事务提交前推送，其他窗口会拉到旧值（推-提交竞态）。
 * 因此存在活动事务时挂 afterCommit，提交成功后才真正广播；无事务则立即广播。
 * fail-safe——实时同步失败只记日志，不影响业务事务。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketDataChangeBroadcaster implements DataChangeBroadcaster {

    private final WebSocketNotificationService webSocketNotificationService;

    @Override
    public void publishDataChanged(String entityType, Long entityId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doBroadcast(entityType, entityId);
                }
            });
            return;
        }
        doBroadcast(entityType, entityId);
    }

    private void doBroadcast(String entityType, Long entityId) {
        try {
            boolean delivered = webSocketNotificationService.broadcastDataChanged(entityType, entityId);
            if (!delivered) {
                log.debug("[DataChange] 无在线客户端收到变更广播: entityType={}, entityId={}", entityType, entityId);
            }
        } catch (Exception e) {
            log.warn("[DataChange] 广播数据变更失败（不影响业务）: entityType={}, entityId={}, error={}",
                    entityType, entityId, e.getMessage());
        }
    }
}
