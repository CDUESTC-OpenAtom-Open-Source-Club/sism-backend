package com.sism.workflow.domain.runtime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * AuditStepInstance - 审批步骤实例
 * Represents a single step in an approval workflow instance.
 */
@Getter
@Setter
@Entity(name = "WorkflowAuditStepInstance")
@Table(name = "audit_step_instance")
public class AuditStepInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instance_id", nullable = false)
    @JsonIgnore
    private AuditInstance instance;

    @Column(name = "step_no", nullable = false)
    private Integer stepNo;

    @Column(name = "step_name", nullable = false)
    private String stepName;

    @Column(name = "step_def_id")
    private Long stepDefId;

    @Column(name = "status")
    private String status;

    @Column(name = "approver_id")
    private Long approverId;

    @Column(name = "approver_org_id")
    private Long approverOrgId;

    @Column(name = "comment")
    private String comment;

    /**
     * 本节点的鉴定进度等级（超前 AHEAD / 正常 NORMAL / 延期 DELAYED）。
     * 仅审批节点（APPROVAL）在通过时填写；填报人提交节点为空。
     */
    @Column(name = "appraisal_level")
    private String appraisalLevel;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
