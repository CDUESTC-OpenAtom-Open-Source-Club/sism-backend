package com.sism.workflow.domain.query.repository;

import com.sism.workflow.domain.runtime.AuditInstance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface WorkflowQueryRepository {

    Optional<AuditInstance> findAuditInstanceById(Long instanceId);

    Optional<AuditInstance> findPendingAuditInstanceByStepIdAndUserId(Long stepInstanceId, Long userId);

    List<AuditInstance> findPendingAuditInstancesByUserId(Long userId);

    Page<AuditInstance> findPendingAuditInstancesByUserId(Long userId, Pageable pageable);

    long countPendingTasksByUserId(Long userId);

    List<PendingTaskIdentity> findPendingTaskIdentitiesByUserId(Long userId);

    /**
     * 2026-10-30 用户定案：异动按计划聚合，消息只有一条，无 N 项文案。
     * 按异动批次号查询某用户名下的在途审批实例（仅 PLAN_MUTATION_STRATEGY 批次有值），
     * 供消息中心按批次重建聚合后的待办消息详情。
     */
    List<AuditInstance> findPendingAuditInstancesByUserIdAndBatchId(Long userId, String batchId);

    List<AuditInstance> findApprovedAuditInstancesByUserId(Long userId);

    Page<AuditInstance> findApprovedAuditInstancesByUserId(Long userId, Pageable pageable);

    List<AuditInstance> findAppliedAuditInstancesByUserId(Long userId);

    Page<AuditInstance> findAppliedAuditInstancesByUserId(Long userId, Pageable pageable);

    List<AuditInstance> findAuditInstanceHistory(Long instanceId);

    record PendingTaskIdentity(
            Long stepInstanceId,
            Long instanceId,
            String entityType,
            Long entityId,
            /** 2026-10-30 用户定案：异动按计划聚合——同批次实例计数/展示只计 1，无 N 项文案。 */
            String batchId
    ) {
    }
}
