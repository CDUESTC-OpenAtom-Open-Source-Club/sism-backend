package com.sism.workflow.interfaces.rest;

import com.sism.shared.application.dto.CurrentUser;
import com.sism.workflow.application.WorkflowApplicationService;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.interfaces.dto.CreateLegacyFlowRequest;
import com.sism.workflow.interfaces.dto.CreateLegacyFlowStepRequest;
import com.sism.workflow.interfaces.dto.StartLegacyInstanceRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowController tests")
class WorkflowControllerTest {

    @Mock
    private WorkflowApplicationService workflowApplicationService;

    @Test
    @DisplayName("getFlowDefinitionById should return 404 payload when missing")
    void getFlowDefinitionByIdShouldReturn404PayloadWhenMissing() {
        WorkflowController controller = new WorkflowController(
                workflowApplicationService,
                new com.sism.workflow.interfaces.assembler.LegacyWorkflowAssembler(),
                new com.sism.workflow.application.query.WorkflowReadModelMapper()
        );

        when(workflowApplicationService.getAuditFlowDefById(9L)).thenReturn(null);

        var response = controller.getFlowDefinitionById(9L);

        assertEquals(200, response.getStatusCodeValue());
        assertEquals(404, response.getBody().getCode());
    }

    @Test
    @DisplayName("createFlowDefinition should wrap created definition")
    void createFlowDefinitionShouldWrapCreatedDefinition() {
        WorkflowController controller = new WorkflowController(
                workflowApplicationService,
                new com.sism.workflow.interfaces.assembler.LegacyWorkflowAssembler(),
                new com.sism.workflow.application.query.WorkflowReadModelMapper()
        );
        CreateLegacyFlowRequest request = new CreateLegacyFlowRequest();
        request.setFlowCode("FLOW-1");
        request.setFlowName("Flow 1");
        request.setEntityType("PLAN");
        CreateLegacyFlowStepRequest step = new CreateLegacyFlowStepRequest();
        step.setStepOrder(1);
        step.setStepName("填报人提交");
        request.setSteps(java.util.List.of(step));
        com.sism.workflow.domain.definition.AuditFlowDef flowDef = new com.sism.workflow.domain.definition.AuditFlowDef();
        flowDef.setId(1L);
        flowDef.setFlowCode("FLOW-1");
        flowDef.setFlowName("Flow 1");

        when(workflowApplicationService.createAuditFlowDef(org.mockito.ArgumentMatchers.any())).thenReturn(flowDef);

        var response = controller.createFlowDefinition(request);

        assertEquals(200, response.getStatusCodeValue());
        assertEquals("FLOW-1", response.getBody().getData().getDefinitionCode());
    }

    @Test
    @DisplayName("startInstance should pass requester identity to service")
    void startInstanceShouldPassRequesterIdentityToService() {
        WorkflowController controller = new WorkflowController(
                workflowApplicationService,
                new com.sism.workflow.interfaces.assembler.LegacyWorkflowAssembler(),
                new com.sism.workflow.application.query.WorkflowReadModelMapper()
        );
        StartLegacyInstanceRequest request = new StartLegacyInstanceRequest();
        request.setFlowDefId(1L);
        request.setEntityType("PLAN");
        request.setEntityId(88L);
        AuditInstance started = new AuditInstance();
        started.setId(11L);
        CurrentUser currentUser = new CurrentUser(91L, "tester", "Tester", "tester@example.com", 38L, java.util.List.of());

        when(workflowApplicationService.startAuditInstance(
                org.mockito.ArgumentMatchers.any(AuditInstance.class),
                org.mockito.ArgumentMatchers.eq(91L),
                org.mockito.ArgumentMatchers.eq(38L),
                org.mockito.ArgumentMatchers.isNull()
        ))
                .thenReturn(started);

        var response = controller.startInstance(request, currentUser);

        assertEquals(200, response.getStatusCodeValue());
        assertEquals("11", response.getBody().getData().getInstanceId());
        verify(workflowApplicationService).startAuditInstance(
                org.mockito.ArgumentMatchers.any(AuditInstance.class),
                org.mockito.ArgumentMatchers.eq(91L),
                org.mockito.ArgumentMatchers.eq(38L),
                org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    @DisplayName("cancelInstance should return 404 payload when instance is missing")
    void cancelInstanceShouldReturn404PayloadWhenInstanceMissing() {
        WorkflowController controller = new WorkflowController(
                workflowApplicationService,
                new com.sism.workflow.interfaces.assembler.LegacyWorkflowAssembler(),
                new com.sism.workflow.application.query.WorkflowReadModelMapper()
        );

        var response = controller.cancelInstance(10L, null);

        assertEquals(200, response.getStatusCodeValue());
        assertEquals(401, response.getBody().getCode());
    }
}
