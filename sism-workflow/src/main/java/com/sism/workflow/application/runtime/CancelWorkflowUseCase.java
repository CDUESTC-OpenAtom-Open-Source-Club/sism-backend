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

    /**
     * 2026-10-30 用户定案（异动驳回后重报/取消闭环）：填报人「取消异动」——
     * 把「退回待重报」的整批异动实例一次性置为终态 CANCELLED（completedAt=now）。
     * 逐实例经 {@link AuditInstance#cancelReturned()} 做状态校验（WITHDRAWN、
     * 未完结、存在退回提交步骤——新形态（提交步原地 WITHDRAWN）与旧形态
     * （存量追加的退回提交行）都认），任一失败整批抛异常回滚；落库后逐实例
     * 同步业务解锁（INDICATOR/TASK 的 mutation_status 清理走既有 sync 通道）
     * 并广播事件。退回待重报态下没有待审批人，不再发 WorkflowChainWithdrawnEvent
     * 撤回通知。
     */
    @Transactional
    public List<AuditInstance> cancelReturnedBatch(List<AuditInstance> instances) {
        List<AuditInstance> savedList = new java.util.ArrayList<>();
        for (AuditInstance instance : instances) {
            instance.cancelReturned();
            savedList.add(auditInstanceRepository.save(instance));
        }
        for (AuditInstance saved : savedList) {
            workflowBusinessStatusSyncService.syncAfterWorkflowChanged(saved);
            workflowEventDispatcher.publish(saved);
        }
        return savedList;
    }

    /**
     * 2026-10-30 用户定案（异动撤回=退回填报人活链）：异动发起人在审批中的「撤回」
     * 把整批异动实例退回填报人节点、链保持存活。逐实例经
     * {@link AuditInstance#withdrawToRequester()} 做状态校验与转移（IN_REVIEW →
     * WITHDRAWN、completedAt 保持 NULL、在审审批步与最新提交类步原地置 WITHDRAWN
     * 「提交人撤回」）。2026-10-30 二次定案（撤回节点生成与 cancel 同构：原地翻转、
     * 零新增行）：不再追加「退回提交步骤」行（旧行为每撤一次节点链越长），链位由
     * 被翻转的提交步代表，重报时经 resume 原地复活（见 AuditInstance.
     * reactivateWithdrawnStep 双形态兼容）。任一失败整批抛异常回滚；落库后逐实例
     * 同步业务解锁（INDICATOR/TASK 的 mutation_status 清理走既有 sync 通道）并
     * 广播事件。与 cancelReturnedBatch 同口径：退回填报人态没有待审批人，
     * 不发 WorkflowChainWithdrawnEvent 撤回通知。
     */
    @Transactional
    public List<AuditInstance> withdrawToRequesterBatch(List<AuditInstance> instances) {
        List<AuditInstance> savedList = new java.util.ArrayList<>();
        for (AuditInstance instance : instances) {
            instance.withdrawToRequester();
            savedList.add(auditInstanceRepository.save(instance));
        }
        for (AuditInstance saved : savedList) {
            workflowBusinessStatusSyncService.syncAfterWorkflowChanged(saved);
            workflowEventDispatcher.publish(saved);
        }
        return savedList;
    }
}
