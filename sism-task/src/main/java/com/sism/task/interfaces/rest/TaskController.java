package com.sism.task.interfaces.rest;

import com.sism.common.ApiResponse;
import com.sism.common.PageResult;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.shared.domain.exception.AuthorizationException;
import com.sism.task.application.TaskApplicationService;
import com.sism.task.application.dto.*;
import com.sism.task.domain.task.TaskType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
@Tag(name = "任务管理", description = "任务中心相关接口。战略任务是当前的任务类型之一。")
public class TaskController {

    private static final String TASK_WRITE_ACCESS =
            "hasAnyRole('REPORTER','STRATEGY_DEPT_HEAD','VICE_PRESIDENT')";
    private static final String TASK_DELETE_ACCESS =
            "hasAnyRole('STRATEGY_DEPT_HEAD','VICE_PRESIDENT')";

    private final TaskApplicationService taskApplicationService;
    private final com.sism.task.application.TaskMutationHistoryService taskMutationHistoryService;

    @PostMapping
    @Operation(summary = "创建新任务", description = "创建战略任务，并为未来任务类型保留明确的任务类别语义。")
    @PreAuthorize(TASK_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<TaskResponse>> createTask(
            @Valid @RequestBody CreateTaskRequest request,
            Authentication authentication) {
        boolean admin = isAdmin(authentication);
        CurrentUser currentUser = admin ? null : requireCurrentUser(authentication);
        TaskResponse created = taskApplicationService.createTask(request, currentUser, admin);
        return ResponseEntity.ok(ApiResponse.success(created));
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据ID获取任务")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<TaskResponse>> getTask(
            @PathVariable Long id,
            Authentication authentication) {
        TaskResponse task = taskApplicationService.getTaskById(id);
        ensureCanAccessTask(authentication, task);
        return ResponseEntity.ok(ApiResponse.success(task));
    }

    @GetMapping
    @Operation(summary = "获取所有任务")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<TaskResponse>>> getAllTasks(Authentication authentication) {
        List<TaskResponse> tasks;
        if (isAdmin(authentication)) {
            tasks = taskApplicationService.getAllTasks();
        } else {
            CurrentUser currentUser = requireCurrentUser(authentication);
            tasks = taskApplicationService.getAccessibleTasksByOrgId(currentUser.getOrgId());
        }
        return ResponseEntity.ok(ApiResponse.success(tasks));
    }

    @GetMapping("/search")
    @Operation(summary = "搜索任务(带过滤和分页)", description = "搜索响应区分计划状态和任务自身状态。")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PageResult<TaskResponse>>> searchTasks(
            @Parameter(description = "计划ID") @RequestParam(required = false) Long planId,
            @Parameter(description = "考核周期ID") @RequestParam(required = false) Long cycleId,
            @Parameter(description = "组织ID") @RequestParam(required = false) Long orgId,
            @Parameter(description = "创建组织ID") @RequestParam(required = false) Long createdByOrgId,
            @Parameter(description = "任务类型") @RequestParam(required = false) String taskType,
            @Parameter(description = "规划投影状态") @RequestParam(required = false) String planStatus,
            @Parameter(description = "任务自身状态") @RequestParam(required = false) String taskStatus,
            @Parameter(description = "任务名称模糊搜索") @RequestParam(required = false) String name,
            @Parameter(description = "页码") @RequestParam(required = false, defaultValue = "0") Integer page,
            @Parameter(description = "每页大小") @RequestParam(required = false, defaultValue = "10") Integer size,
            Authentication authentication) {
        TaskQueryRequest queryRequest = new TaskQueryRequest();
        queryRequest.setPlanId(planId);
        queryRequest.setCycleId(cycleId);
        queryRequest.setOrgId(orgId);
        queryRequest.setCreatedByOrgId(createdByOrgId);
        queryRequest.setTaskType(parseTaskType(taskType));
        queryRequest.setPlanStatus(planStatus);
        queryRequest.setTaskStatus(taskStatus);
        queryRequest.setName(name);
        queryRequest.setPage(page);
        queryRequest.setSize(size);

        Long accessibleOrgId = null;
        if (!isAdmin(authentication)) {
            CurrentUser currentUser = requireCurrentUser(authentication);
            if (currentUser.getOrgId() == null) {
                throw new AuthorizationException("当前用户缺少组织信息，无法访问任务数据");
            }
            accessibleOrgId = currentUser.getOrgId();
        }

        PageResult<TaskResponse> result = taskApplicationService.searchTasks(queryRequest, accessibleOrgId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "激活任务", description = "仅更新任务状态。此接口不代表计划审批或分发状态。")
    @PreAuthorize(TASK_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<TaskResponse>> activateTask(@PathVariable Long id, Authentication authentication) {
        boolean admin = isAdmin(authentication);
        CurrentUser currentUser = admin ? null : requireCurrentUser(authentication);
        TaskResponse activated = taskApplicationService.activateTask(id, currentUser, admin);
        return ResponseEntity.ok(ApiResponse.success(activated));
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "完成任务", description = "仅更新任务状态。此接口不代表计划审批或分发状态。")
    @PreAuthorize(TASK_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<TaskResponse>> completeTask(@PathVariable Long id, Authentication authentication) {
        boolean admin = isAdmin(authentication);
        CurrentUser currentUser = admin ? null : requireCurrentUser(authentication);
        TaskResponse completed = taskApplicationService.completeTask(id, currentUser, admin);
        return ResponseEntity.ok(ApiResponse.success(completed));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "取消任务", description = "仅更新任务状态。此接口不代表计划审批或分发状态。")
    @PreAuthorize(TASK_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<TaskResponse>> cancelTask(@PathVariable Long id, Authentication authentication) {
        boolean admin = isAdmin(authentication);
        CurrentUser currentUser = admin ? null : requireCurrentUser(authentication);
        TaskResponse cancelled = taskApplicationService.cancelTask(id, currentUser, admin);
        return ResponseEntity.ok(ApiResponse.success(cancelled));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新任务")
    @PreAuthorize(TASK_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<TaskResponse>> updateTask(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTaskRequest request,
            Authentication authentication) {
        boolean admin = isAdmin(authentication);
        CurrentUser currentUser = admin ? null : requireCurrentUser(authentication);
        TaskResponse updated = taskApplicationService.updateTask(id, request, currentUser, admin);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    @PutMapping("/{id}/name")
    @Operation(summary = "更新任务名称")
    @PreAuthorize(TASK_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<TaskResponse>> updateTaskName(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTaskNameRequest request,
            Authentication authentication) {
        boolean admin = isAdmin(authentication);
        CurrentUser currentUser = admin ? null : requireCurrentUser(authentication);
        TaskResponse updated = taskApplicationService.updateTaskName(id, request.getName(), currentUser, admin);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    @GetMapping("/{id}/mutation-history")
    @Operation(summary = "战略任务变更历史", description = "该任务全部改名快照（已更改 N 次）")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> taskMutationHistory(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(taskMutationHistoryService.history(id)));
    }

    @PutMapping("/{id}/sort-order")
    @Operation(summary = "更新任务排序顺序")
    @PreAuthorize(TASK_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<TaskResponse>> updateSortOrder(
            @PathVariable Long id,
            @RequestParam Integer sortOrder,
            Authentication authentication) {
        boolean admin = isAdmin(authentication);
        CurrentUser currentUser = admin ? null : requireCurrentUser(authentication);
        TaskResponse updated = taskApplicationService.updateSortOrder(id, sortOrder, currentUser, admin);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    @PutMapping("/{id}/desc")
    @Operation(summary = "更新任务描述")
    @PreAuthorize(TASK_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<TaskResponse>> updateTaskDesc(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTaskDescRequest request,
            Authentication authentication) {
        boolean admin = isAdmin(authentication);
        CurrentUser currentUser = admin ? null : requireCurrentUser(authentication);
        TaskResponse updated = taskApplicationService.updateTaskDesc(id, request.getDesc(), currentUser, admin);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    @PutMapping("/{id}/remark")
    @Operation(summary = "更新任务备注")
    @PreAuthorize(TASK_WRITE_ACCESS)
    public ResponseEntity<ApiResponse<TaskResponse>> updateTaskRemark(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTaskRemarkRequest request,
            Authentication authentication) {
        boolean admin = isAdmin(authentication);
        CurrentUser currentUser = admin ? null : requireCurrentUser(authentication);
        TaskResponse updated = taskApplicationService.updateTaskRemark(id, request.getRemark(), currentUser, admin);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除任务(软删除)")
    @PreAuthorize(TASK_DELETE_ACCESS)
    public ResponseEntity<ApiResponse<Void>> deleteTask(@PathVariable Long id, Authentication authentication) {
        boolean admin = isAdmin(authentication);
        CurrentUser currentUser = admin ? null : requireCurrentUser(authentication);
        taskApplicationService.deleteTask(id, currentUser, admin);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping("/by-org/{orgId}")
    @Operation(summary = "根据组织ID获取任务列表")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<TaskResponse>>> getTasksByOrgId(
            @PathVariable @Parameter(description = "组织ID") Long orgId,
            Authentication authentication) {
        List<TaskResponse> tasks = filterTasksByPermission(taskApplicationService.getTasksByOrgId(orgId), authentication);
        return ResponseEntity.ok(ApiResponse.success(tasks));
    }

    @GetMapping("/by-plan/{planId}")
    @Operation(summary = "根据计划ID获取任务列表")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<TaskResponse>>> getTasksByPlanId(
            @PathVariable @Parameter(description = "计划ID") Long planId,
            Authentication authentication) {
        List<TaskResponse> tasks = filterTasksByPermission(taskApplicationService.getTasksByPlanId(planId), authentication);
        return ResponseEntity.ok(ApiResponse.success(tasks));
    }

    @GetMapping("/by-cycle/{cycleId}")
    @Operation(summary = "根据考核周期ID获取任务列表")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<TaskResponse>>> getTasksByCycleId(
            @PathVariable @Parameter(description = "考核周期ID") Long cycleId,
            Authentication authentication) {
        List<TaskResponse> tasks = filterTasksByPermission(taskApplicationService.getTasksByCycleId(cycleId), authentication);
        return ResponseEntity.ok(ApiResponse.success(tasks));
    }

    @GetMapping("/by-type/{taskType}")
    @Operation(summary = "根据任务类型获取任务列表")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<TaskResponse>>> getTasksByType(
            @PathVariable @Parameter(description = "任务类型") TaskType taskType,
            Authentication authentication) {
        List<TaskResponse> tasks = filterTasksByPermission(taskApplicationService.getTasksByType(taskType), authentication);
        return ResponseEntity.ok(ApiResponse.success(tasks));
    }

    private List<TaskResponse> filterTasksByPermission(List<TaskResponse> tasks, Authentication authentication) {
        if (isAdmin(authentication)) {
            return tasks;
        }

        CurrentUser currentUser = requireCurrentUser(authentication);
        if (currentUser.getOrgId() == null) {
            throw new AuthorizationException("当前用户缺少组织信息，无法访问任务数据");
        }

        return tasks.stream()
                .filter(task -> canAccessTask(currentUser, task))
                .toList();
    }

    private void ensureCanAccessTask(Authentication authentication, TaskResponse task) {
        if (isAdmin(authentication)) {
            return;
        }
        CurrentUser currentUser = requireCurrentUser(authentication);
        if (!canAccessTask(currentUser, task)) {
            throw new AuthorizationException("无权访问该任务");
        }
    }

    private boolean canAccessTask(CurrentUser currentUser, TaskResponse task) {
        if (task == null) {
            return false;
        }

        Long currentOrgId = currentUser.getOrgId();
        return Objects.equals(task.getOrgId(), currentOrgId)
                || Objects.equals(task.getCreatedByOrgId(), currentOrgId);
    }

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(authority -> "ROLE_VICE_PRESIDENT".equals(authority)
                        || "ROLE_STRATEGY_DEPT_HEAD".equals(authority));
    }

    private CurrentUser requireCurrentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser currentUser)) {
            throw new AuthorizationException("无法获取当前用户信息，请联系管理员");
        }
        return currentUser;
    }

    private TaskType parseTaskType(String rawTaskType) {
        if (rawTaskType == null || rawTaskType.isBlank()) {
            return null;
        }
        try {
            return TaskType.valueOf(rawTaskType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不支持的任务类型: " + rawTaskType);
        }
    }
}
