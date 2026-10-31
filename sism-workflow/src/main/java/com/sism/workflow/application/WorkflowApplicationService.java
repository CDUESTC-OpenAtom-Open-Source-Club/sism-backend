package com.sism.workflow.application;

import com.sism.workflow.application.definition.WorkflowDefinitionQueryService;
import com.sism.workflow.application.runtime.*;
import com.sism.workflow.application.support.WorkflowEventDispatcher;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.WorkflowTask;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import com.sism.workflow.domain.runtime.WorkflowTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * WorkflowApplicationService - 工作流兼容门面
 * 对外保留既有 API，内部转调新的查询服务和用例服务。
 */
@Service
@RequiredArgsConstructor
public class WorkflowApplicationService {

    private final WorkflowDefinitionQueryService workflowDefinitionQueryService;
    private final WorkflowInstanceQueryService workflowInstanceQueryService;
    private final StartWorkflowUseCase startWorkflowUseCase;
    private final ApproveWorkflowUseCase approveWorkflowUseCase;
    private final RejectWorkflowUseCase rejectWorkflowUseCase;
    private final CancelWorkflowUseCase cancelWorkflowUseCase;
    private final WorkflowTaskCommandService workflowTaskCommandService;
    private final AuditInstanceRepository auditInstanceRepository;
    private final WorkflowTaskRepository workflowTaskRepository;
    private final WorkflowEventDispatcher workflowEventDispatcher;

    public List<AuditFlowDef> getAllAuditFlowDefs() {
        return workflowDefinitionQueryService.getAllAuditFlowDefs();
    }

    public AuditFlowDef getAuditFlowDefById(Long id) {
        return workflowDefinitionQueryService.getAuditFlowDefById(id);
    }

    public AuditFlowDef getAuditFlowDefByCode(String flowCode) {
        return workflowDefinitionQueryService.getAuditFlowDefByCode(flowCode);
    }

    public List<AuditFlowDef> getAuditFlowDefsByEntityType(String entityType) {
        return workflowDefinitionQueryService.getAuditFlowDefsByEntityType(entityType);
    }

    public AuditFlowDef createAuditFlowDef(AuditFlowDef flowDef) {
        return workflowDefinitionQueryService.createAuditFlowDef(flowDef);
    }

    public List<AuditInstance> getAllAuditInstances() {
        return workflowInstanceQueryService.getAllAuditInstances();
    }

    public AuditInstance getAuditInstanceById(Long instanceId) {
        return workflowInstanceQueryService.getAuditInstanceById(instanceId);
    }

    public List<AuditInstance> getPendingAuditInstancesByUserId(Long userId) {
        return workflowInstanceQueryService.getPendingAuditInstancesByUserId(userId);
    }

    public List<AuditInstance> getApprovedAuditInstancesByUserId(Long userId) {
        return workflowInstanceQueryService.getApprovedAuditInstancesByUserId(userId);
    }

    public List<AuditInstance> getAppliedAuditInstancesByUserId(Long userId) {
        return workflowInstanceQueryService.getAppliedAuditInstancesByUserId(userId);
    }

    public List<AuditInstance> getAuditInstanceHistory(Long instanceId) {
        return workflowInstanceQueryService.getAuditInstanceHistory(instanceId);
    }

    public AuditInstance startAuditInstance(AuditInstance instance,
                                            Long requesterId,
                                            Long requesterOrgId,
                                            String submitComment) {
        return startWorkflowUseCase.startAuditInstance(instance, requesterId, requesterOrgId, submitComment);
    }

    public AuditInstance startAuditInstance(AuditInstance instance,
                                            Long requesterId,
                                            Long requesterOrgId) {
        return startAuditInstance(instance, requesterId, requesterOrgId, null);
    }

    public AuditInstance approveAuditInstance(AuditInstance instance, Long userId, String comment) {
        return approveWorkflowUseCase.approve(instance, userId, comment);
    }

    public AuditInstance approveAuditInstance(AuditInstance instance, Long userId, String comment, String appraisalLevel) {
        return approveWorkflowUseCase.approve(instance, userId, comment, appraisalLevel);
    }

    /** 2026-10-20：系统管理员代批通道（导入自动下发）。 */
    public AuditInstance approveAuditInstanceAsSystem(AuditInstance instance, Long userId, String comment) {
        return approveWorkflowUseCase.approve(instance, userId, comment, null, true);
    }

    public AuditInstance rejectAuditInstance(AuditInstance instance, Long userId, String comment) {
        return rejectWorkflowUseCase.reject(instance, userId, comment);
    }

    public AuditInstance cancelAuditInstance(AuditInstance instance) {
        return cancelWorkflowUseCase.cancel(instance);
    }

    /**
     * 2026-10-30 用户定案（异动驳回后重报/取消闭环）：填报人「取消异动」——
     * 把「退回待重报」的整批异动实例一次性终结为 CANCELLED。状态校验在领域内
     * （AuditInstance.cancelReturned），调用方须先做发起人权限校验。
     */
    public List<AuditInstance> cancelReturnedMutationBatch(List<AuditInstance> instances) {
        return cancelWorkflowUseCase.cancelReturnedBatch(instances);
    }

    /**
     * 2026-10-30 用户定案（异动撤回=退回填报人活链）：异动发起人在审批中的「撤回」
     * 把整批异动实例退回填报人节点、链保持存活（WITHDRAWN、completedAt 为空、
     * 在审审批步与最新提交类步原地置 WITHDRAWN「提交人撤回」——2026-10-30
     * 二次定案：与上报撤回 cancel 同构的原地翻转、零新增行）。状态校验在领域内
     * （AuditInstance.withdrawToRequester），调用方须先做发起人权限校验。
     */
    public List<AuditInstance> withdrawMutationBatchToRequester(List<AuditInstance> instances) {
        return cancelWorkflowUseCase.withdrawToRequesterBatch(instances);
    }

    public AuditInstance resumeWithdrawnAuditInstance(AuditInstance instance) {
        return resumeWithdrawnAuditInstance(instance, null);
    }

    /**
     * 2026-10-31 修复（重报说明被丢弃，图05）：重报链路把提交人填写的「重报说明」
     * 透传给被复活的提交步；comment 缺省时 {@link AuditInstance#reactivateWithdrawnStep()}
     * 保留原兜底文案，行为与改前一致。
     */
    public AuditInstance resumeWithdrawnAuditInstance(AuditInstance instance, String submitComment) {
        instance.reactivateWithdrawnStep(submitComment);
        return approveOrSavePassthrough(instance);
    }

    public AuditInstance transferAuditInstance(Long instanceId, Long targetUserId) {
        AuditInstance instance = workflowInstanceQueryService.getAuditInstanceById(instanceId);
        if (instance == null) {
            throw new IllegalArgumentException("Audit instance not found: " + instanceId);
        }
        instance.transfer(targetUserId);
        return approveOrSavePassthrough(instance);
    }

    public AuditInstance addApproverToInstance(Long instanceId, Long approverId) {
        AuditInstance instance = workflowInstanceQueryService.getAuditInstanceById(instanceId);
        if (instance == null) {
            throw new IllegalArgumentException("Audit instance not found: " + instanceId);
        }
        instance.addApprover(approverId);
        return approveOrSavePassthrough(instance);
    }

    public WorkflowTask startWorkflowTask(WorkflowTask task, Long operatorId, Long operatorOrgId) {
        return workflowTaskCommandService.start(task, operatorId, operatorOrgId);
    }

    public WorkflowTask getWorkflowTaskById(Long taskId) {
        return workflowTaskRepository.findById(taskId).orElse(null);
    }

    public WorkflowTask completeWorkflowTask(WorkflowTask task, String result) {
        return workflowTaskCommandService.complete(task, result);
    }

    public WorkflowTask failWorkflowTask(WorkflowTask task, String errorMessage) {
        return workflowTaskCommandService.fail(task, errorMessage);
    }

    public WorkflowTask approveWorkflowTask(WorkflowTask task, Long approverId, String comment) {
        return workflowTaskCommandService.approve(task, approverId, comment);
    }

    public WorkflowTask rejectWorkflowTask(WorkflowTask task, Long approverId, String comment) {
        return workflowTaskCommandService.reject(task, approverId, comment);
    }

    public Map<String, Object> getApprovalStatistics() {
        return workflowInstanceQueryService.getApprovalStatistics();
    }

    private AuditInstance approveOrSavePassthrough(AuditInstance instance) {
        AuditInstance saved = auditInstanceRepository.save(instance);
        workflowEventDispatcher.publish(saved);
        return saved;
    }
}
