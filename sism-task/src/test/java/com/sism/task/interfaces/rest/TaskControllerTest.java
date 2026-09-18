package com.sism.task.interfaces.rest;

import com.sism.shared.application.dto.CurrentUser;
import com.sism.common.PageResult;
import com.sism.task.application.TaskApplicationService;
import com.sism.task.application.dto.CreateTaskRequest;
import com.sism.task.application.dto.TaskResponse;
import com.sism.task.domain.task.TaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskControllerTest {

    @Mock
    private TaskApplicationService taskApplicationService;

    @Mock
    private com.sism.task.application.TaskMutationHistoryService taskMutationHistoryService;

    @InjectMocks
    private TaskController taskController;

    @Test
    void getAllTasksShouldFilterByCurrentUserOrg() {
        Authentication authentication = authentication(11L, 35L, "ROLE_USER");

        TaskResponse allowed = taskResponse(1L, 35L, 35L);
        TaskResponse createdByOrg = taskResponse(2L, 99L, 35L);

        when(taskApplicationService.getAccessibleTasksByOrgId(35L)).thenReturn(List.of(allowed, createdByOrg));

        var response = taskController.getAllTasks(authentication);

        assertEquals(2, response.getBody().getData().size());
        assertEquals(1L, response.getBody().getData().get(0).getId());
        assertEquals(2L, response.getBody().getData().get(1).getId());
        verify(taskApplicationService).getAccessibleTasksByOrgId(35L);
        verify(taskApplicationService, never()).getAllTasks();
    }

    @Test
    void createTaskShouldRejectForeignOrgForNonAdmin() {
        Authentication authentication = authentication(11L, 35L, "ROLE_STRATEGY_DEPT");

        CreateTaskRequest request = new CreateTaskRequest();
        request.setName("测试任务");
        request.setTaskType(TaskType.BASIC);
        request.setPlanId(100L);
        request.setCycleId(200L);
        request.setOrgId(99L);
        request.setCreatedByOrgId(35L);

        when(taskApplicationService.createTask(eq(request), any(CurrentUser.class), eq(false)))
                .thenThrow(new RuntimeException("无权操作该组织下的任务"));

        assertThrows(RuntimeException.class, () -> taskController.createTask(request, authentication));
        verify(taskApplicationService).createTask(eq(request), any(CurrentUser.class), eq(false));
    }

    @Test
    void searchTasksShouldPassCurrentOrgForNonAdmin() {
        Authentication authentication = authentication(11L, 35L, "ROLE_USER");

        PageResult<TaskResponse> pageResult = PageResult.of(List.of(taskResponse(1L, 35L, 35L)), 1, 0, 10);
        when(taskApplicationService.searchTasks(any(), eq(35L))).thenReturn(pageResult);

        var response = taskController.searchTasks(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "任务",
                0,
                10,
                authentication);

        assertEquals(1L, response.getBody().getData().getTotal());
        assertEquals(1, response.getBody().getData().getItems().size());
        verify(taskApplicationService).searchTasks(any(), eq(35L));
    }

    @Test
    void getTasksByOrgIdShouldFilterByCurrentUserOrg() {
        Authentication authentication = authentication(11L, 35L, "ROLE_USER");
        when(taskApplicationService.getTasksByOrgId(99L)).thenReturn(List.of(
                taskResponse(1L, 35L, 99L),
                taskResponse(2L, 99L, 99L)
        ));

        var response = taskController.getTasksByOrgId(99L, authentication);

        assertEquals(1, response.getBody().getData().size());
        assertEquals(1L, response.getBody().getData().get(0).getId());
        verify(taskApplicationService).getTasksByOrgId(99L);
    }

    @Test
    void searchTasksShouldRejectUnsupportedTaskType() {
        Authentication authentication = authentication(11L, 35L, "ROLE_USER");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                taskController.searchTasks(
                        null,
                        null,
                        null,
                        null,
                        "mystery",
                        null,
                        null,
                        null,
                        0,
                        10,
                        authentication)
        );

        assertTrue(error.getMessage().contains("不支持的任务类型"));
        verify(taskApplicationService, never()).searchTasks(any(), any());
    }

    private Authentication authentication(Long userId, Long orgId, String authority) {
        CurrentUser currentUser = new CurrentUser(
                userId,
                String.valueOf(userId),
                "User-" + userId,
                "user@example.com",
                orgId,
                List.of(new SimpleGrantedAuthority(authority))
        );
        return UsernamePasswordAuthenticationToken.authenticated(
                currentUser,
                null,
                currentUser.getAuthorities()
        );
    }

    private TaskResponse taskResponse(Long id, Long orgId, Long createdByOrgId) {
        TaskResponse response = new TaskResponse();
        response.setId(id);
        response.setName("任务-" + id);
        response.setOrgId(orgId);
        response.setCreatedByOrgId(createdByOrgId);
        response.setPlanStatus("DRAFT");
        response.setTaskStatus("DRAFT");
        return response;
    }
}
