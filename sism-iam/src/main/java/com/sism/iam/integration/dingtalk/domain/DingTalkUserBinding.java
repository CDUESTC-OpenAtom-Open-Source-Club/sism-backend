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
@Table(name = "sys_dingtalk_binding")
@Getter
@Setter
public class DingTalkUserBinding {

    public static final String SOURCE_AUTO = "AUTO";
    public static final String SOURCE_ADMIN = "ADMIN";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sys_dingtalk_binding_id_gen")
    @SequenceGenerator(name = "sys_dingtalk_binding_id_gen", sequenceName = "sys_dingtalk_binding_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "sys_user_id", nullable = false, unique = true)
    private Long sysUserId;

    @Column(name = "dingtalk_user_id", nullable = false, unique = true)
    private String dingTalkUserId;

    @Column(name = "dingtalk_union_id")
    private String dingTalkUnionId;

    @Column(name = "dingtalk_name", length = 100)
    private String dingTalkName;

    @Column(name = "corp_id", length = 64)
    private String corpId;

    @Column(name = "mobile", length = 20)
    private String mobile;

    @Column(name = "bound_source", nullable = false, length = 16)
    private String boundSource = SOURCE_AUTO;

    @Column(name = "bound_at", nullable = false)
    private LocalDateTime boundAt = LocalDateTime.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
