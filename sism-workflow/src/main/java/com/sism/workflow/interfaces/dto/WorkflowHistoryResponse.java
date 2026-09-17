package com.sism.workflow.interfaces.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工作流历史记录响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowHistoryResponse {

    private String historyId;
    private String taskId;
    private String taskName;
    private Long operatorId;
    private String operatorName;
    private String action; // APPROVE, REJECT, REASSIGN
    private String comment;
    private String appraisalLevel;
    private LocalDateTime operateTime;
}
