package com.sism.iam.integration.dingtalk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_dingtalk_todo_task")
@Getter
@Setter
public class DingTalkTodoTask {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sys_dingtalk_todo_task_id_gen")
    @SequenceGenerator(name = "sys_dingtalk_todo_task_id_gen", sequenceName = "sys_dingtalk_todo_task_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "approval_instance_id", nullable = false)
    private Long approvalInstanceId;

    @Column(name = "step_instance_id")
    private Long stepInstanceId;

    @Column(name = "sys_user_id", nullable = false)
    private Long sysUserId;

    @Column(name = "dingtalk_union_id", nullable = false, length = 64)
    private String dingTalkUnionId;

    @Column(name = "dingtalk_task_id", nullable = false, length = 128)
    private String dingTalkTaskId;

    @Column(name = "source_id", nullable = false, length = 128)
    private String sourceId;

    @Column(name = "detail_url", length = 500)
    private String detailUrl;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
