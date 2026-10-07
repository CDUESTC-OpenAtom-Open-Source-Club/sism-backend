package com.sism.workflow.application.runtime;

import com.sism.shared.domain.notification.WorkflowChainWithdrawnEvent;
import com.sism.workflow.application.support.WorkflowEventDispatcher;
import com.sism.workflow.application.WorkflowBusinessStatusSyncService;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CancelWorkflowUseCase {

    private final AuditInstanceRepository auditInstanceRepository;
    private final WorkflowEventDispatcher workflowEventDispatcher;
    private final WorkflowBusinessStatusSyncService workflowBusinessStatusSyncService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public AuditInstance cancel(AuditInstance instance) {
        // 撤回前抓取原待审批人（cancel 会把 PENDING 步骤置回 WAITING）
        List<Long> pendingApproverIds = instance.getPendingApproverIds();
        instance.cancel();
        AuditInstance saved = auditInstanceRepository.save(instance);
        workflowBusinessStatusSyncService.syncAfterWorkflowChanged(saved);
        workflowEventDispatcher.publish(saved);
        // 2026-10-07：撤回成功后实时通知原待审批人（sism-main 监听并推送 WebSocket）
        eventPublisher.publishEvent(new WorkflowChainWithdrawnEvent(
                saved.getId(),
                saved.getEntityType(),
                saved.getEntityId(),
                saved.getRequesterId(),
                pendingApproverIds
        ));
        return saved;
    }
}
