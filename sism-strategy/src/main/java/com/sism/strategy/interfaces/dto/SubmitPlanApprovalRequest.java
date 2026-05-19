package com.sism.strategy.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SubmitPlanApprovalRequest {

    @NotBlank(message = "Workflow code is required")
    private String workflowCode;

    private String comment;
}
