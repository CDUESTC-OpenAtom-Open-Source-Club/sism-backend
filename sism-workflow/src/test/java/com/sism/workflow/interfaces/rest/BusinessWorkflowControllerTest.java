package com.sism.workflow.interfaces.rest;

import com.sism.shared.application.dto.CurrentUser;
import com.sism.workflow.application.BusinessWorkflowApplicationService;
import com.sism.workflow.interfaces.dto.PageResult;
import com.sism.workflow.interfaces.dto.StartInstanceRequest;
import com.sism.workflow.interfaces.dto.WorkflowDefinitionPreviewResponse;
import com.sism.workflow.interfaces.dto.WorkflowInstanceResponse;
import com.sism.workflow.interfaces.dto.WorkflowTaskResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BusinessWorkflowController tests")
class BusinessWorkflowControllerTest {

    @Mock
    private BusinessWorkflowApplicationService workflowService;

    @Mock
    private CurrentUser currentUser;

    @Test
    @DisplayName("getMyPendingTasks should return 401 when user is missing")
    void getMyPendingTasksShouldReturn401WhenUserMissing() {
        BusinessWorkflowController controller = new BusinessWorkflowController(workflowService);

        var response = controller.getMyPendingTasks(null, 1, 10);

        assertEquals(200, response.getStatusCodeValue());
        assertEquals(401, response.getBody().getCode());
        assertEquals("未登录或登录已过期", response.getBody().getMessage());
    }

    @Test
    @DisplayName("cancelInstance should return 401 when user is missing")
    void cancelInstanceShouldReturn401WhenUserMissing() {
        BusinessWorkflowController controller = new BusinessWorkflowController(workflowService);

        var response = controller.cancelInstance("9", null);

        assertEquals(200, response.getStatusCodeValue());
        assertEquals(401, response.getBody().getCode());
        assertEquals("未登录或登录已过期", response.getBody().getMessage());
    }

    @Test
    @DisplayName("startWorkflowInstance should pass authenticated identity to service")
    void startWorkflowInstanceShouldPassAuthenticatedIdentityToService() {
        BusinessWorkflowController controller = new BusinessWorkflowController(workflowService);
        StartInstanceRequest request = new StartInstanceRequest();

        when(currentUser.getId()).thenReturn(17L);
        when(currentUser.getOrgId()).thenReturn(27L);
        when(workflowService.startWorkflowInstance(eq("1"), any(StartInstanceRequest.class), eq(17L), eq(27L)))
                .thenReturn(WorkflowInstanceResponse.builder().instanceId("1").build());

        var response = controller.startWorkflowInstance("1", request, currentUser);

        assertEquals(200, response.getStatusCodeValue());
        assertEquals("1", response.getBody().getData().getInstanceId());
        verify(workflowService).startWorkflowInstance("1", request, 17L, 27L);
    }

    @Test
    @DisplayName("getDefinitionPreviewByCode should use current user org id")
    void getDefinitionPreviewByCodeShouldUseCurrentUserOrgId() {
        BusinessWorkflowController controller = new BusinessWorkflowController(workflowService);

        when(currentUser.getOrgId()).thenReturn(35L);
        when(workflowService.getDefinitionPreview("FLOW", 35L))
                .thenReturn(WorkflowDefinitionPreviewResponse.builder().workflowCode("FLOW").build());

        var response = controller.getDefinitionPreviewByCode("FLOW", currentUser);

        assertEquals(200, response.getStatusCodeValue());
        assertEquals("FLOW", response.getBody().getData().getWorkflowCode());
    }

    @Test
    @DisplayName("getStatistics should return service result")
    void getStatisticsShouldReturnServiceResult() {
        BusinessWorkflowController controller = new BusinessWorkflowController(workflowService);
        Map<String, Object> stats = Map.of("totalInstances", 1);

        when(workflowService.getStatistics()).thenReturn(stats);

        var response = controller.getStatistics();

        assertEquals(200, response.getStatusCodeValue());
        assertEquals(stats, response.getBody().getData());
    }

    @Test
    @DisplayName("getMyPendingTasks should return service page when user exists")
    void getMyPendingTasksShouldReturnServicePageWhenUserExists() {
        BusinessWorkflowController controller = new BusinessWorkflowController(workflowService);
        PageResult<WorkflowTaskResponse> page = PageResult.of(List.of(), 0, 1, 10);

        when(currentUser.getId()).thenReturn(17L);
        when(workflowService.getMyPendingTasks(17L, 1, 10)).thenReturn(page);

        var response = controller.getMyPendingTasks(currentUser, 1, 10);

        assertEquals(200, response.getStatusCodeValue());
        assertEquals(page, response.getBody().getData());
        verify(workflowService).getMyPendingTasks(17L, 1, 10);
    }

    @Test
    @DisplayName("getMyPendingTasks should forward pageSize to the service unmodified")
    void getMyPendingTasksShouldForwardPageSize() {
        BusinessWorkflowController controller = new BusinessWorkflowController(workflowService);
        PageResult<WorkflowTaskResponse> page = PageResult.of(List.of(), 3, 2, 2);

        when(currentUser.getId()).thenReturn(17L);
        when(workflowService.getMyPendingTasks(17L, 2, 2)).thenReturn(page);

        var response = controller.getMyPendingTasks(currentUser, 2, 2);

        assertEquals(200, response.getStatusCodeValue());
        assertEquals(2, response.getBody().getData().getPageSize());
        assertEquals(2, response.getBody().getData().getPageNum());
        verify(workflowService).getMyPendingTasks(17L, 2, 2);
    }
}
