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

    public AuditInstance rejectAuditInstance(AuditInstance instance, Long userId, String comment) {
        return rejectWorkflowUseCase.reject(instance, userId, comment);
    }

    public AuditInstance cancelAuditInstance(AuditInstance instance) {
        return cancelWorkflowUseCase.cancel(instance);
    }

    public AuditInstance resumeWithdrawnAuditInstance(AuditInstance instance) {
        instance.reactivateWithdrawnStep();
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
