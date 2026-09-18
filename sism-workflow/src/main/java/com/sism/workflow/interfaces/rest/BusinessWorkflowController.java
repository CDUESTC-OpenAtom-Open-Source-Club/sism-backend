package com.sism.workflow.interfaces.rest;

import com.sism.common.ApiResponse;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.workflow.application.BusinessWorkflowApplicationService;
import com.sism.workflow.interfaces.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * BusinessWorkflowController - 业务工作流控制器
 * 管理固定模板驱动的线性审批流程。
 *
 * API 前缀: /api/v1/workflows
 */
@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
@Tag(name = "业务工作流", description = "业务工作流管理接口")
public class BusinessWorkflowController {

    private final BusinessWorkflowApplicationService workflowService;

    // 1. 启动工作流

    @PostMapping("/start")
    @Operation(summary = "启动固定模板工作流并绑定审批人到审批节点")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> startWorkflow(
            @Valid @RequestBody StartWorkflowRequest request,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        WorkflowInstanceResponse response = workflowService.startWorkflow(
                request, currentUser.getId(), currentUser.getOrgId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{definitionId}/instances")
    @Operation(summary = "通过定义ID启动工作流实例")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> startWorkflowInstance(
            @PathVariable String definitionId,
            @Valid @RequestBody StartInstanceRequest request,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        WorkflowInstanceResponse response = workflowService.startWorkflowInstance(
                definitionId, request, currentUser.getId(), currentUser.getOrgId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 2. 查询工作流

    @GetMapping("/definitions")
    @Operation(summary = "分页列出工作流定义")
    public ResponseEntity<ApiResponse<PageResult<WorkflowDefinitionResponse>>> listDefinitions(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        PageResult<WorkflowDefinitionResponse> result = workflowService.listDefinitions(pageNum, pageSize);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{definitionId}/instances")
    @Operation(summary = "列出工作流定义的实例")
    public ResponseEntity<ApiResponse<PageResult<WorkflowInstanceResponse>>> listInstances(
            @PathVariable String definitionId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        PageResult<WorkflowInstanceResponse> result = workflowService.listInstances(definitionId, pageNum, pageSize);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/instances/{instanceId}")
    @Operation(summary = "获取工作流实例详情")
    public ResponseEntity<ApiResponse<WorkflowInstanceDetailResponse>> getInstanceDetail(
            @PathVariable String instanceId
    ) {
        WorkflowInstanceDetailResponse response = workflowService.getInstanceDetail(instanceId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/instances/entity/{entityType}/{entityId}")
    @Operation(summary = "按业务实体获取最新工作流实例详情")
    public ResponseEntity<ApiResponse<WorkflowInstanceDetailResponse>> getInstanceDetailByBusiness(
            @PathVariable String entityType,
            @PathVariable Long entityId
    ) {
        WorkflowInstanceDetailResponse response = workflowService.getInstanceDetailByBusiness(entityType, entityId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/instances/entity/{entityType}/{entityId}/list")
    @Operation(summary = "按业务实体列出已完成的工作流历史卡片")
    public ResponseEntity<ApiResponse<List<WorkflowHistoryCardResponse>>> listInstanceHistoryByBusiness(
            @PathVariable String entityType,
            @PathVariable Long entityId
    ) {
        List<WorkflowHistoryCardResponse> response = workflowService.listInstanceHistoryByBusiness(entityType, entityId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/my-tasks")
    @Operation(summary = "获取我的待处理工作流任务")
    public ResponseEntity<ApiResponse<PageResult<WorkflowTaskResponse>>> getMyPendingTasks(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(defaultValue = "1") int pageNum
    ) {
        if (currentUser == null) {
            return ResponseEntity.ok(ApiResponse.error(401, "未登录或登录已过期"));
        }
        PageResult<WorkflowTaskResponse> result = workflowService.getMyPendingTasks(
                currentUser.getId(), pageNum);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // 3. 工作流操作

    @PostMapping("/tasks/{taskId}/approve")
    @Operation(summary = "审批当前工作流节点并推进到下一个节点")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> approveTask(
            @PathVariable String taskId,
            @Valid @RequestBody ApprovalRequest request,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        // 透传鉴定进度等级（P1）：中间节点仅留痕，终审由 B9 逻辑投影到业务明细行
        WorkflowInstanceResponse response = workflowService.approveTask(
                taskId, request, currentUser.getId(), request.getAppraisalLevel());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/tasks/{taskId}/decision")
    @Operation(summary = "通过任务实例ID和布尔结果决定当前工作流节点")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> decideTask(
            @PathVariable String taskId,
            @Valid @RequestBody WorkflowTaskDecisionRequest request,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        WorkflowInstanceResponse response = workflowService.decideTask(
                taskId, request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/tasks/{taskId}/reject")
    @Operation(summary = "拒绝当前工作流节点并回退到上一个已审批节点")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> rejectTask(
            @PathVariable String taskId,
            @Valid @RequestBody RejectionRequest request,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        WorkflowInstanceResponse response = workflowService.rejectTask(
                taskId, request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/tasks/{taskId}/reassign")
    @Operation(summary = "重新分配工作流任务(固定审批模板不支持)")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> reassignTask(
            @PathVariable String taskId,
            @Valid @RequestBody ReassignRequest request,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        WorkflowInstanceResponse response = workflowService.reassignTask(
                taskId, request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{instanceId}/cancel")
    @Operation(summary = "在第一个审批节点处理前取消工作流实例")
    public ResponseEntity<ApiResponse<Void>> cancelInstance(
            @PathVariable String instanceId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        if (currentUser == null) {
            return ResponseEntity.ok(ApiResponse.error(401, "未登录或登录已过期"));
        }
        workflowService.cancelInstance(instanceId, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // 4. 流程定义管理

    @GetMapping("/definitions/{definitionId}")
    @Operation(summary = "根据ID获取工作流定义")
    public ResponseEntity<ApiResponse<WorkflowDefinitionResponse>> getDefinitionById(
            @PathVariable String definitionId
    ) {
        WorkflowDefinitionResponse response = workflowService.getDefinitionById(definitionId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/definitions/code/{flowCode}")
    @Operation(summary = "根据代码获取工作流定义")
    public ResponseEntity<ApiResponse<WorkflowDefinitionResponse>> getDefinitionByCode(
            @PathVariable String flowCode
    ) {
        WorkflowDefinitionResponse response = workflowService.getDefinitionByCode(flowCode);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/definitions/code/{flowCode}/preview")
    @Operation(summary = "预览工作流定义及候选审批人")
    public ResponseEntity<ApiResponse<WorkflowDefinitionPreviewResponse>> getDefinitionPreviewByCode(
            @PathVariable String flowCode,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        WorkflowDefinitionPreviewResponse response = workflowService.getDefinitionPreview(flowCode, currentUser.getOrgId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/definitions/entity-type/{entityType}")
    @Operation(summary = "根据实体类型获取工作流定义")
    public ResponseEntity<ApiResponse<List<WorkflowDefinitionResponse>>> getDefinitionsByEntityType(
            @PathVariable String entityType
    ) {
        List<WorkflowDefinitionResponse> response = workflowService.getDefinitionsByEntityType(entityType);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/definitions")
    @Operation(
            summary = "创建带有明确有序步骤的固定工作流模板",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/json",
                            schema = @io.swagger.v3.oas.annotations.media.Schema(
                                    implementation = CreateWorkflowDefinitionRequest.class
                            )
                    )
            )
    )
    public ResponseEntity<ApiResponse<WorkflowDefinitionResponse>> createDefinition(
            @Valid @RequestBody CreateWorkflowDefinitionRequest request
    ) {
        WorkflowDefinitionResponse response = workflowService.createDefinition(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 5. 实例查询

    @GetMapping("/my-approved")
    @Operation(summary = "Get my approved workflow instances")
    public ResponseEntity<ApiResponse<PageResult<WorkflowInstanceResponse>>> getMyApprovedInstances(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        PageResult<WorkflowInstanceResponse> result = workflowService.getMyApprovedInstances(
                currentUser.getId(), pageNum, pageSize);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/my-applied")
    @Operation(summary = "获取我发起的工作流实例")
    public ResponseEntity<ApiResponse<PageResult<WorkflowInstanceResponse>>> getMyAppliedInstances(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        PageResult<WorkflowInstanceResponse> result = workflowService.getMyAppliedInstances(
                currentUser.getId(), pageNum, pageSize);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // 6. 统计

    @GetMapping("/statistics")
    @Operation(summary = "获取工作流统计数据")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatistics() {
        Map<String, Object> stats = workflowService.getStatistics();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }
}
