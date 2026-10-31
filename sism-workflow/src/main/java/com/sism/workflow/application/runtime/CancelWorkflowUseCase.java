package com.sism.workflow.application.runtime;

import com.sism.shared.domain.notification.WorkflowChainWithdrawnEvent;
import com.sism.workflow.application.support.WorkflowEventDispatcher;
import com.sism.workflow.application.WorkflowBusinessStatusSyncService;
import com.sism.workflow.application.definition.WorkflowDefinitionQueryService;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import com.sism.workflow.domain.runtime.AuditStepInstance;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CancelWorkflowUseCase {

    /** 2026-10-30（异动撤回=退回填报人活链）：退回提交步骤的统一备注。 */
    private static final String WITHDRAW_RETURN_COMMENT = "提交人撤回";

    private final AuditInstanceRepository auditInstanceRepository;
    private final WorkflowEventDispatcher workflowEventDispatcher;
    private final WorkflowBusinessStatusSyncService workflowBusinessStatusSyncService;
    private final WorkflowDefinitionQueryService workflowDefinitionQueryService;
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
     * 未完结、存在退回提交步骤），任一失败整批抛异常回滚；落库后逐实例同步业务
     * 解锁（INDICATOR/TASK 的 mutation_status 清理走既有 sync 通道）并广播事件。
     * 退回待重报态下没有待审批人，不再发 WorkflowChainWithdrawnEvent 撤回通知。
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
     * 不再终结链，而是把整批异动实例退回填报人节点、链保持存活。逐实例经
     * {@link AuditInstance#withdrawToRequester()} 做状态校验与转移（IN_REVIEW →
     * WITHDRAWN、completedAt 保持 NULL、当前在审步骤置 WITHDRAWN），再由本用例
     * 追加「退回提交步骤」（WITHDRAWN、审批人=发起人，形态与驳回退回填报人
     * RejectWorkflowUseCase.createReturnedStep 的 submit 步骤分支完全同构），
     * 任一失败整批抛异常回滚；落库后逐实例同步业务解锁（INDICATOR/TASK 的
     * mutation_status 清理走既有 sync 通道）并广播事件。与 cancelReturnedBatch
     * 同口径：退回填报人态没有待审批人，不发 WorkflowChainWithdrawnEvent 撤回通知。
     */
    @Transactional
    public List<AuditInstance> withdrawToRequesterBatch(List<AuditInstance> instances) {
        List<AuditInstance> savedList = new java.util.ArrayList<>();
        for (AuditInstance instance : instances) {
            instance.withdrawToRequester();
            appendReturnedSubmitStep(instance);
            savedList.add(auditInstanceRepository.save(instance));
        }
        for (AuditInstance saved : savedList) {
            workflowBusinessStatusSyncService.syncAfterWorkflowChanged(saved);
            workflowEventDispatcher.publish(saved);
        }
        return savedList;
    }

    /**
     * 追加「退回提交步骤」，解析口径与 RejectWorkflowUseCase.createReturnedStep
     * 的 submit 步骤分支一致：步骤定义取流程定义里序号最小的提交步骤；审批人=
     * 发起人、审批组织=发起组织、状态 WITHDRAWN、approvedAt 置空等待重提。
     * 流程定义缺失（历史数据）时步骤名回退为含「提交」的固定文案，
     * 保证退回链查询（approver=requester 或步骤名含「提交」）仍可识别。
     */
    private void appendReturnedSubmitStep(AuditInstance instance) {
        AuditStepDef submitStepDef = resolveSubmitStepDef(instance);
        AuditStepInstance returnedStep = new AuditStepInstance();
        returnedStep.setStepNo(instance.nextStepInstanceNo());
        if (submitStepDef != null) {
            returnedStep.setStepDefId(submitStepDef.getId());
            returnedStep.setStepName(submitStepDef.getStepName());
        } else {
            returnedStep.setStepName("提交人撤回退回填报");
        }
        returnedStep.setApproverId(instance.getRequesterId());
        returnedStep.setApproverOrgId(instance.getRequesterOrgId());
        returnedStep.setStatus(AuditInstance.STEP_STATUS_WITHDRAWN);
        returnedStep.setComment(WITHDRAW_RETURN_COMMENT);
        returnedStep.setApprovedAt(null);
        instance.addStepInstance(returnedStep);
    }

    private AuditStepDef resolveSubmitStepDef(AuditInstance instance) {
        AuditFlowDef flowDef = workflowDefinitionQueryService.getAuditFlowDefById(instance.getFlowDefId());
        if (flowDef == null || flowDef.getSteps() == null || flowDef.getSteps().isEmpty()) {
            return null;
        }
        return flowDef.getSteps().stream()
                .filter(AuditStepDef::isSubmitStep)
                .min(Comparator.comparing(step -> step.getStepOrder() == null ? Integer.MAX_VALUE : step.getStepOrder()))
                .orElse(null);
    }
}
