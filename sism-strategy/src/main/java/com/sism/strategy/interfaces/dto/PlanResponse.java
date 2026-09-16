package com.sism.strategy.interfaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * PlanResponse - 计划响应DTO
 * 用于返回计划的完整信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "计划响应")
public class PlanResponse {

    @Schema(description = "计划ID")
    private Long id;

    @Schema(description = "计划名称")
    private String planName;

    @Schema(description = "计划描述")
    private String description;

    @Schema(description = "计划类型", example = "STRATEGY")
    private String planType;

    @Schema(description = "计划状态", example = "DRAFT")
    private String status;

    @Schema(description = "开始日期")
    private LocalDateTime startDate;

    @Schema(description = "结束日期")
    private LocalDateTime endDate;

    @Schema(description = "负责部门")
    private String ownerDepartment;

    @Schema(description = "完成百分比")
    private Integer completionPercentage;

    @Schema(description = "指标数量")
    private Integer indicatorCount;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "年份（用于过滤）")
    private String year;

    @Schema(description = "周期ID")
    private Long cycleId;

    @Schema(description = "目标组织ID")
    private Long targetOrgId;

    @Schema(description = "目标组织名称")
    private String targetOrgName;

    @Schema(description = "创建组织ID")
    private Long createdByOrgId;

    @Schema(description = "创建组织名称")
    private String createdByOrgName;

    @Schema(description = "计划层级", example = "STRATEGIC")
    private String planLevel;

    @Schema(description = "关联的工作流实例ID")
    private Long workflowInstanceId;

    @Schema(description = "提交人ID")
    private Long submittedBy;

    @Schema(description = "提交人姓名")
    private String submittedByName;

    @Schema(description = "提交时间")
    private LocalDateTime submittedAt;

    @Schema(description = "最近一次驳回原因")
    private String lastRejectReason;

    @Schema(description = "是否可编辑")
    private Boolean canEdit;

    @Schema(description = "是否可重新提交审批")
    private Boolean canResubmit;

    @Schema(description = "工作流状态")
    private String workflowStatus;

    @Schema(description = "当前审批节点名称")
    private String currentStepName;

    @Schema(description = "当前审批人ID")
    private Long currentApproverId;

    @Schema(description = "当前审批人名称")
    private String currentApproverName;

    @Schema(description = "是否可撤回")
    private Boolean canWithdraw;
}
