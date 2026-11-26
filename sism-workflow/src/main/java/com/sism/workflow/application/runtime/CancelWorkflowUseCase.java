package com.sism.workflow.application.runtime;

import com.sism.exception.BusinessConflictException;
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
        // 2026-11-26（撤回窗口收窄，用户定调最初设计）：仅第一个审批环节（学院审核人/
        // 职能部门审批人）审核前可撤。领域层 AuditInstance.cancel → canRequesterWithdraw
        // 一直有此门（当前待办步的上一步必须是发起人提交步=尚无任何审批人通过），但失败
        // 一律落成 IllegalStateException → HTTP 400 英文通用文案，调用方无从区分「环节已
        // 推进」。这里在用例层先做同口径预判：审批中且环节已推进 → 409 业务冲突，文案带
        // 当前环节；非审批中等其余非法态仍走领域层原异常（原语义保留）。
        assertWithdrawWindowOpen(instance);
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
     * 2026-11-26（撤回窗口收窄，用户定调最初设计）：撤回窗口=「填报人提交之后 →
     * 第一个审批环节审核之前」。学院线第一审批环节=学院审核人审批，职能线=职能
     * 部门审批人审批（口径泛化：当前待办步的上一步仍是发起人提交步，即尚无任何
     * 审批人通过）。窗口关闭时抛 {@link BusinessConflictException}（HTTP 409），
     * 文案带当前环节，前端透出后填报人能明确知道撤回为何不可用。实例不在审批中
     * （已完结/已撤回/不存在待办步）时不在此拦截，保留领域层 AuditInstance.cancel
     * 的原 IllegalStateException 语义（HTTP 400）。
     */
    private void assertWithdrawWindowOpen(AuditInstance instance) {
        if (instance.canRequesterWithdraw()) {
            return;
        }
        if (!AuditInstance.STATUS_PENDING.equals(instance.getStatus())) {
            return;
        }
        instance.resolveCurrentPendingStep().ifPresent(currentStep -> {
            String stepName = currentStep.getStepName() == null || currentStep.getStepName().isBlank()
                    ? "下一审批环节"
                    : currentStep.getStepName().trim();
            throw new BusinessConflictException(
                    "审批已推进至「" + stepName + "」环节，不可撤回：仅提交后第一个审批环节"
                            + "（学院审核人/职能部门审批人）审核前可撤回");
        });
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
