package com.sism.workflow.interfaces.rest;

import com.sism.common.ApiResponse;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.workflow.application.WorkflowApplicationService;
import com.sism.workflow.application.query.WorkflowReadModelMapper;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.WorkflowTask;
import com.sism.workflow.interfaces.assembler.LegacyWorkflowAssembler;
import com.sism.workflow.interfaces.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * WorkflowController - 工作流API控制器
 * 提供工作流相关的REST API端点
 */
@RestController
@RequestMapping("/api/v1/approval")
@RequiredArgsConstructor
@Tag(name = "Workflows", description = "Workflow and approval management endpoints")
public class WorkflowController {

    private final WorkflowApplicationService workflowApplicationService;
    private final LegacyWorkflowAssembler legacyWorkflowAssembler;
    private final WorkflowReadModelMapper workflowReadModelMapper;

    // ==================== Audit Flow Definition Endpoints ====================

    @GetMapping("/legacy-flows")
    @Operation(summary = "Get all approval flow definitions")
    public ResponseEntity<ApiResponse<List<WorkflowDefinitionResponse>>> getAllFlowDefinitions() {
        List<AuditFlowDef> flowDefs = workflowApplicationService.getAllAuditFlowDefs();
        List<WorkflowDefinitionResponse> responses = flowDefs.stream()
                .map(workflowReadModelMapper::toDefinitionResponse)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/legacy-flows/{id}")
    @Operation(summary = "Get approval flow definition by ID")
    public ResponseEntity<ApiResponse<WorkflowDefinitionResponse>> getFlowDefinitionById(@PathVariable Long id) {
        AuditFlowDef flowDef = workflowApplicationService.getAuditFlowDefById(id);
        if (flowDef == null) {
            return ResponseEntity.ok(ApiResponse.error(404, "Flow definition not found"));
        }
        return ResponseEntity.ok(ApiResponse.success(workflowReadModelMapper.toDefinitionResponse(flowDef)));
    }

    @GetMapping("/legacy-flows/code/{flowCode}")
    @Operation(summary = "Get approval flow definition by code")
    public ResponseEntity<ApiResponse<WorkflowDefinitionResponse>> getFlowDefinitionByCode(@PathVariable String flowCode) {
        AuditFlowDef flowDef = workflowApplicationService.getAuditFlowDefByCode(flowCode);
        if (flowDef == null) {
            return ResponseEntity.ok(ApiResponse.error(404, "Flow definition not found"));
        }
        return ResponseEntity.ok(ApiResponse.success(workflowReadModelMapper.toDefinitionResponse(flowDef)));
    }

    @GetMapping("/legacy-flows/entity-type/{entityType}")
    @Operation(summary = "Get approval flow definitions by entity type")
    public ResponseEntity<ApiResponse<List<WorkflowDefinitionResponse>>> getFlowDefinitionsByEntityType(@PathVariable String entityType) {
        List<AuditFlowDef> flowDefs = workflowApplicationService.getAuditFlowDefsByEntityType(entityType);
        List<WorkflowDefinitionResponse> responses = flowDefs.stream()
                .map(workflowReadModelMapper::toDefinitionResponse)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @PostMapping("/legacy-flows")
    @Operation(summary = "Create approval flow definition")
    public ResponseEntity<ApiResponse<WorkflowDefinitionResponse>> createFlowDefinition(
            @Valid @RequestBody CreateLegacyFlowRequest request) {
        AuditFlowDef created = workflowApplicationService.createAuditFlowDef(
                legacyWorkflowAssembler.toAuditFlowDef(request)
        );
        return ResponseEntity.ok(ApiResponse.success(workflowReadModelMapper.toDefinitionResponse(created)));
    }

    // ==================== Audit Instance Endpoints ====================

    @GetMapping("/instances")
    @Operation(summary = "Get all approval instances")
    public ResponseEntity<ApiResponse<List<WorkflowInstanceResponse>>> getAllInstances() {
        List<AuditInstance> instances = workflowApplicationService.getAllAuditInstances();
        List<WorkflowInstanceResponse> responses = instances.stream()
                .map(workflowReadModelMapper::toInstanceResponse)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/instances/{instanceId}")
    @Operation(summary = "Get approval instance by ID")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> getInstanceById(@PathVariable Long instanceId) {
        AuditInstance instance = workflowApplicationService.getAuditInstanceById(instanceId);
        if (instance == null) {
            return ResponseEntity.ok(ApiResponse.error(404, "Approval instance not found"));
        }
        return ResponseEntity.ok(ApiResponse.success(workflowReadModelMapper.toInstanceResponse(instance)));
    }

    @GetMapping("/instances/my-pending")
    @Operation(summary = "Get my pending approval instances")
    public ResponseEntity<ApiResponse<List<WorkflowInstanceResponse>>> getMyPendingInstances(
            @AuthenticationPrincipal CurrentUser currentUser) {
        List<AuditInstance> instances = workflowApplicationService.getPendingAuditInstancesByUserId(currentUser.getId());
        List<WorkflowInstanceResponse> responses = instances.stream()
                .map(workflowReadModelMapper::toInstanceResponse)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/instances/my-approved")
    @Operation(summary = "Get my approved instances")
    public ResponseEntity<ApiResponse<List<WorkflowInstanceResponse>>> getMyApprovedInstances(
            @AuthenticationPrincipal CurrentUser currentUser) {
        List<AuditInstance> instances = workflowApplicationService.getApprovedAuditInstancesByUserId(currentUser.getId());
        List<WorkflowInstanceResponse> responses = instances.stream()
                .map(workflowReadModelMapper::toInstanceResponse)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/instances/my-applied")
    @Operation(summary = "Get my applied instances")
    public ResponseEntity<ApiResponse<List<WorkflowInstanceResponse>>> getMyAppliedInstances(
            @AuthenticationPrincipal CurrentUser currentUser) {
        List<AuditInstance> instances = workflowApplicationService.getAppliedAuditInstancesByUserId(currentUser.getId());
        List<WorkflowInstanceResponse> responses = instances.stream()
                .map(workflowReadModelMapper::toInstanceResponse)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/instances/{instanceId}/history")
    @Operation(summary = "Get approval instance history")
    public ResponseEntity<ApiResponse<List<WorkflowInstanceResponse>>> getInstanceHistory(@PathVariable Long instanceId) {
        List<AuditInstance> instances = workflowApplicationService.getAuditInstanceHistory(instanceId);
        List<WorkflowInstanceResponse> responses = instances.stream()
                .map(workflowReadModelMapper::toInstanceResponse)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @PostMapping("/instances")
    @Operation(summary = "Start approval instance")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> startInstance(
            @Valid @RequestBody StartLegacyInstanceRequest request,
            @AuthenticationPrincipal CurrentUser currentUser) {
        AuditInstance instance = legacyWorkflowAssembler.toAuditInstance(request);
        AuditInstance started = workflowApplicationService.startAuditInstance(
                instance,
                currentUser.getId(),
                currentUser.getOrgId(),
                null
        );
        return ResponseEntity.ok(ApiResponse.success(workflowReadModelMapper.toInstanceResponse(started)));
    }

    @PostMapping("/instances/{instanceId}/approve")
    @Operation(summary = "Approve approval instance")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> approveInstance(
            @PathVariable Long instanceId,
            @Valid @RequestBody ApprovalRequest request,
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) String comment) {
        // 先从数据库获取完整的实例信息
        AuditInstance existingInstance = workflowApplicationService.getAuditInstanceById(instanceId);
        if (existingInstance == null) {
            return ResponseEntity.ok(ApiResponse.error(404, "Approval instance not found"));
        }
        // 使用数据库中的实例进行操作
        String resolvedComment = request.getComment() != null ? request.getComment() : comment;
        AuditInstance approved = workflowApplicationService.approveAuditInstance(
                existingInstance,
                currentUser.getId(),
                resolvedComment
        );
        return ResponseEntity.ok(ApiResponse.success(workflowReadModelMapper.toInstanceResponse(approved)));
    }

    @PostMapping("/instances/{instanceId}/reject")
    @Operation(summary = "Reject approval instance")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> rejectInstance(
            @PathVariable Long instanceId,
            @Valid @RequestBody RejectionRequest request,
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) String comment) {
        // 先从数据库获取完整的实例信息
        AuditInstance existingInstance = workflowApplicationService.getAuditInstanceById(instanceId);
        if (existingInstance == null) {
            return ResponseEntity.ok(ApiResponse.error(404, "Approval instance not found"));
        }
        // 使用数据库中的实例进行操作
        String resolvedComment = request.getReason() != null ? request.getReason() : comment;
        AuditInstance rejected = workflowApplicationService.rejectAuditInstance(
                existingInstance,
                currentUser.getId(),
                resolvedComment
        );
        return ResponseEntity.ok(ApiResponse.success(workflowReadModelMapper.toInstanceResponse(rejected)));
    }

    @PostMapping("/instances/{instanceId}/cancel")
    @Operation(summary = "Cancel approval instance")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> cancelInstance(
            @PathVariable Long instanceId,
            @AuthenticationPrincipal CurrentUser currentUser) {
        if (currentUser == null) {
            return ResponseEntity.ok(ApiResponse.error(401, "未登录或登录已过期"));
        }
        // 先从数据库获取完整的实例信息
        AuditInstance existingInstance = workflowApplicationService.getAuditInstanceById(instanceId);
        if (existingInstance == null) {
            return ResponseEntity.ok(ApiResponse.error(404, "Approval instance not found"));
        }
        if (!currentUser.getId().equals(existingInstance.getRequesterId())) {
            return ResponseEntity.ok(ApiResponse.error(403, "Only requester can cancel"));
        }
        AuditInstance cancelled = workflowApplicationService.cancelAuditInstance(existingInstance);
        return ResponseEntity.ok(ApiResponse.success(workflowReadModelMapper.toInstanceResponse(cancelled)));
    }

    @PostMapping("/instances/{instanceId}/transfer")
    @Operation(summary = "Transfer approval instance")
    public ResponseEntity<ApiResponse<AuditInstance>> transferInstance(
            @PathVariable Long instanceId,
            @RequestParam Long targetUserId) {
        return ResponseEntity.ok(ApiResponse.error(409, "固定审批模板不支持转办"));
    }

    @PostMapping("/instances/{instanceId}/add-approver")
    @Operation(summary = "Add approver to instance")
    public ResponseEntity<ApiResponse<AuditInstance>> addApprover(
            @PathVariable Long instanceId,
            @RequestParam Long approverId) {
        return ResponseEntity.ok(ApiResponse.error(409, "固定审批模板不支持加签"));
    }

    // ==================== Workflow Task Endpoints ====================

    @PostMapping("/tasks")
    @Operation(summary = "Start workflow task")
    public ResponseEntity<ApiResponse<WorkflowTask>> startTask(
            @Valid @RequestBody WorkflowTaskStartRequest request,
            @AuthenticationPrincipal CurrentUser currentUser) {
        WorkflowTask task = new WorkflowTask();
        task.setWorkflowId(request.getWorkflowId());
        task.setWorkflowType(request.getWorkflowType());
        task.setTaskName(request.getTaskName());
        task.setTaskType(request.getTaskType());
        task.setCurrentStep(request.getCurrentStep());
        task.setNextStep(request.getNextStep());
        task.setInitiatorId(request.getInitiatorId());
        task.setInitiatorOrgId(request.getInitiatorOrgId());
        task.setDueDate(request.getDueDate());
        WorkflowTask started = workflowApplicationService.startWorkflowTask(task, currentUser.getId(), currentUser.getOrgId());
        return ResponseEntity.ok(ApiResponse.success(started));
    }

    @PostMapping("/tasks/{id}/complete")
    @Operation(summary = "Complete workflow task")
    public ResponseEntity<ApiResponse<WorkflowTask>> completeTask(
            @PathVariable Long id,
            @Valid @RequestBody WorkflowTaskCompleteRequest request) {
        WorkflowTask task = workflowApplicationService.getWorkflowTaskById(id);
        if (task == null) {
            return ResponseEntity.ok(ApiResponse.error(404, "Workflow task not found"));
        }
        WorkflowTask completed = workflowApplicationService.completeWorkflowTask(task, request.getResult());
        return ResponseEntity.ok(ApiResponse.success(completed));
    }

    @PostMapping("/tasks/{id}/fail")
    @Operation(summary = "Fail workflow task")
    public ResponseEntity<ApiResponse<WorkflowTask>> failTask(
            @PathVariable Long id,
            @Valid @RequestBody WorkflowTaskFailRequest request) {
        WorkflowTask task = workflowApplicationService.getWorkflowTaskById(id);
        if (task == null) {
            return ResponseEntity.ok(ApiResponse.error(404, "Workflow task not found"));
        }
        WorkflowTask failed = workflowApplicationService.failWorkflowTask(task, request.getErrorMessage());
        return ResponseEntity.ok(ApiResponse.success(failed));
    }

    @PostMapping("/tasks/{id}/approve")
    @Operation(summary = "Approve workflow task")
    public ResponseEntity<ApiResponse<WorkflowTask>> approveTask(
            @PathVariable Long id,
            @Valid @RequestBody ApprovalRequest request,
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) String comment) {
        WorkflowTask task = workflowApplicationService.getWorkflowTaskById(id);
        if (task == null) {
            return ResponseEntity.ok(ApiResponse.error(404, "Workflow task not found"));
        }
        String resolvedComment = request.getComment() != null ? request.getComment() : comment;
        WorkflowTask approved = workflowApplicationService.approveWorkflowTask(task, currentUser.getId(), resolvedComment);
        return ResponseEntity.ok(ApiResponse.success(approved));
    }

    @PostMapping("/tasks/{id}/reject")
    @Operation(summary = "Reject workflow task")
    public ResponseEntity<ApiResponse<WorkflowTask>> rejectTask(
            @PathVariable Long id,
            @Valid @RequestBody RejectionRequest request,
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) String comment) {
        WorkflowTask task = workflowApplicationService.getWorkflowTaskById(id);
        if (task == null) {
            return ResponseEntity.ok(ApiResponse.error(404, "Workflow task not found"));
        }
        String resolvedComment = request.getReason() != null ? request.getReason() : comment;
        WorkflowTask rejected = workflowApplicationService.rejectWorkflowTask(task, currentUser.getId(), resolvedComment);
        return ResponseEntity.ok(ApiResponse.success(rejected));
    }

    // ==================== Statistics ====================

    @GetMapping("/statistics")
    @Operation(summary = "Get approval statistics")
    public ResponseEntity<ApiResponse<Object>> getStatistics() {
        Object stats = workflowApplicationService.getApprovalStatistics();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }
}
