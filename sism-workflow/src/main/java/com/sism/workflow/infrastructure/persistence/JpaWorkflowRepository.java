package com.sism.workflow.infrastructure.persistence;

import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.WorkflowTask;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JpaWorkflowRepository - 工作流实例与查询 JPA 实现
 */
@Repository
public interface JpaWorkflowRepository extends JpaRepository<AuditInstance, Long> {

    @Query("SELECT DISTINCT d FROM WorkflowAuditFlowDef d LEFT JOIN FETCH d.steps")
    List<AuditFlowDef> findAllAuditFlowDefs();

    @Query("SELECT DISTINCT d FROM WorkflowAuditFlowDef d LEFT JOIN FETCH d.steps WHERE d.id = :id")
    Optional<AuditFlowDef> findAuditFlowDefById(@Param("id") Long id);

    @Query("SELECT DISTINCT d FROM WorkflowAuditFlowDef d LEFT JOIN FETCH d.steps WHERE d.flowCode = :flowCode")
    Optional<AuditFlowDef> findAuditFlowDefByCode(@Param("flowCode") String flowCode);

    @Query("SELECT DISTINCT d FROM WorkflowAuditFlowDef d LEFT JOIN FETCH d.steps WHERE d.entityType = :entityType")
    List<AuditFlowDef> findAuditFlowDefsByEntityType(@Param("entityType") String entityType);

    @Query("SELECT DISTINCT a FROM WorkflowAuditInstance a LEFT JOIN FETCH a.stepInstances WHERE a.id = :instanceId")
    Optional<AuditInstance> findByIdWithSteps(@Param("instanceId") Long instanceId);

    List<AuditInstance> findAll();

    @Query("SELECT DISTINCT a FROM WorkflowAuditInstance a LEFT JOIN FETCH a.stepInstances WHERE a.entityType = :businessType AND a.entityId = :businessId")
    List<AuditInstance> findByBusinessTypeAndBusinessId(@Param("businessType") String businessType,
                                                         @Param("businessId") Long businessId);

    @Query("SELECT DISTINCT a FROM WorkflowAuditInstance a LEFT JOIN FETCH a.stepInstances WHERE a.entityId = :businessId")
    List<AuditInstance> findByBusinessId(@Param("businessId") Long businessId);

    @Query("SELECT DISTINCT a FROM WorkflowAuditInstance a LEFT JOIN FETCH a.stepInstances WHERE a.status = :status")
    List<AuditInstance> findByStatus(@Param("status") String status);

    @Query("SELECT a FROM WorkflowAuditInstance a WHERE a.requesterId = :initiatorId")
    List<AuditInstance> findByInitiatorId(@Param("initiatorId") Long initiatorId);

    @Query("SELECT DISTINCT a FROM WorkflowAuditInstance a LEFT JOIN FETCH a.stepInstances WHERE a.id = :instanceId")
    Optional<AuditInstance> findAuditInstanceById(@Param("instanceId") Long instanceId);

    @Query("SELECT DISTINCT a FROM WorkflowAuditInstance a JOIN a.stepInstances s WHERE s.id = :stepInstanceId")
    Optional<AuditInstance> findByStepInstanceId(@Param("stepInstanceId") Long stepInstanceId);

    @Query("SELECT DISTINCT a FROM WorkflowAuditInstance a JOIN a.stepInstances s " +
            "WHERE a.status = 'IN_REVIEW' AND s.status = 'PENDING' AND s.approverId = :userId")
    List<AuditInstance> findPendingAuditInstancesByUserId(@Param("userId") Long userId);

    @EntityGraph(attributePaths = "stepInstances")
    @Query(
            value = "SELECT DISTINCT a FROM WorkflowAuditInstance a JOIN a.stepInstances s " +
                    "WHERE a.status = 'IN_REVIEW' AND s.status = 'PENDING' AND s.approverId = :userId",
            countQuery = "SELECT COUNT(DISTINCT a.id) FROM WorkflowAuditInstance a JOIN a.stepInstances s " +
                    "WHERE a.status = 'IN_REVIEW' AND s.status = 'PENDING' AND s.approverId = :userId"
    )
    Page<AuditInstance> findPendingAuditInstancesByUserId(@Param("userId") Long userId, Pageable pageable);

    @EntityGraph(attributePaths = "stepInstances")
    @Query("""
            SELECT DISTINCT a
              FROM WorkflowAuditInstance a
              JOIN a.stepInstances s
             WHERE a.status = 'IN_REVIEW'
               AND s.id = :stepInstanceId
               AND s.status = 'PENDING'
               AND s.approverId = :userId
            """)
    Optional<AuditInstance> findPendingAuditInstanceByStepIdAndUserId(
            @Param("stepInstanceId") Long stepInstanceId,
            @Param("userId") Long userId
    );

    @Query("""
            SELECT COUNT(s.id)
              FROM WorkflowAuditInstance a
              JOIN a.stepInstances s
             WHERE a.status = 'IN_REVIEW'
               AND s.status = 'PENDING'
               AND s.approverId = :userId
            """)
    long countPendingTasksByUserId(@Param("userId") Long userId);

    @Query("""
            SELECT new com.sism.workflow.domain.query.repository.WorkflowQueryRepository$PendingTaskIdentity(
                s.id,
                a.id,
                a.entityType,
                a.entityId,
                a.batchId
            )
              FROM WorkflowAuditInstance a
              JOIN a.stepInstances s
             WHERE a.status = 'IN_REVIEW'
               AND s.status = 'PENDING'
               AND s.approverId = :userId
             ORDER BY COALESCE(s.createdAt, a.startedAt) DESC, s.id DESC
            """)
    List<com.sism.workflow.domain.query.repository.WorkflowQueryRepository.PendingTaskIdentity>
    findPendingTaskIdentitiesByUserId(@Param("userId") Long userId);

    /**
     * 2026-10-30 用户定案：异动按计划聚合，消息只有一条，无 N 项文案——
     * 消息中心按异动批次号回查某用户名下的全部在途实例，重建聚合后的待办消息详情。
     */
    @EntityGraph(attributePaths = "stepInstances")
    @Query("""
            SELECT DISTINCT a
              FROM WorkflowAuditInstance a
              JOIN a.stepInstances s
             WHERE a.status = 'IN_REVIEW'
               AND a.batchId = :batchId
               AND s.status = 'PENDING'
               AND s.approverId = :userId
             ORDER BY a.id ASC
            """)
    List<AuditInstance> findPendingAuditInstancesByUserIdAndBatchId(
            @Param("userId") Long userId,
            @Param("batchId") String batchId);

    /**
     * 2026-10-30 用户定案：异动按计划聚合，消息只有一条，无 N 项文案。
     * 统计同一异动批次内、与刚被审批实例同环节（stepNo）的其余在途实例数——
     * 大于 0 说明整批动作尚未完成（前端逐实例调用审批接口），
     * 期间跳过逐实例的结果/推进通知，由最后一个实例的动作统一产生 1 条。
     */
    @Query("""
            SELECT COUNT(a)
              FROM WorkflowAuditInstance a
              JOIN a.stepInstances s
             WHERE a.batchId = :batchId
               AND a.id <> :excludeInstanceId
               AND a.status = 'IN_REVIEW'
               AND s.status = 'PENDING'
               AND s.stepNo = :stepNo
            """)
    long countPendingBatchSiblingsAtStep(
            @Param("batchId") String batchId,
            @Param("excludeInstanceId") Long excludeInstanceId,
            @Param("stepNo") Integer stepNo);

    /**
     * 2026-10-30 用户定案（异动驳回后重报/取消闭环）：按批次号取同批全部实例
     * （含步骤实例），供「取消异动」整批终结校验与落库。
     */
    @EntityGraph(attributePaths = "stepInstances")
    @Query("SELECT DISTINCT a FROM WorkflowAuditInstance a WHERE a.batchId = :batchId ORDER BY a.id ASC")
    List<AuditInstance> findByBatchId(@Param("batchId") String batchId);

    @Query("SELECT DISTINCT a FROM WorkflowAuditInstance a JOIN a.stepInstances s " +
            "WHERE a.status = 'APPROVED' AND s.approverId = :userId")
    List<AuditInstance> findApprovedAuditInstancesByUserId(@Param("userId") Long userId);

    @EntityGraph(attributePaths = "stepInstances")
    @Query(
            value = "SELECT DISTINCT a FROM WorkflowAuditInstance a JOIN a.stepInstances s " +
                    "WHERE a.status = 'APPROVED' AND s.approverId = :userId",
            countQuery = "SELECT COUNT(DISTINCT a.id) FROM WorkflowAuditInstance a JOIN a.stepInstances s " +
                    "WHERE a.status = 'APPROVED' AND s.approverId = :userId"
    )
    Page<AuditInstance> findApprovedAuditInstancesByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT DISTINCT a FROM WorkflowAuditInstance a LEFT JOIN FETCH a.stepInstances WHERE a.requesterId = :userId")
    List<AuditInstance> findAppliedAuditInstancesByUserId(@Param("userId") Long userId);

    @EntityGraph(attributePaths = "stepInstances")
    @Query(
            value = "SELECT DISTINCT a FROM WorkflowAuditInstance a WHERE a.requesterId = :userId",
            countQuery = "SELECT COUNT(a.id) FROM WorkflowAuditInstance a WHERE a.requesterId = :userId"
    )
    Page<AuditInstance> findAppliedAuditInstancesByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT DISTINCT a FROM WorkflowAuditInstance a LEFT JOIN FETCH a.stepInstances WHERE a.id = :instanceId")
    List<AuditInstance> findAuditInstanceHistory(@Param("instanceId") Long instanceId);

    @Query("SELECT COUNT(a) FROM WorkflowAuditInstance a")
    long countAuditInstances();

    @Query("SELECT COUNT(a) FROM WorkflowAuditInstance a WHERE a.status = 'IN_REVIEW'")
    long countPendingAuditInstances();

    @Query("SELECT COUNT(a) FROM WorkflowAuditInstance a WHERE a.status = 'APPROVED'")
    long countApprovedAuditInstances();

    @Query("SELECT COUNT(a) FROM WorkflowAuditInstance a WHERE a.status = 'REJECTED'")
    long countRejectedAuditInstances();

    @Query("SELECT COUNT(a) > 0 FROM WorkflowAuditInstance a WHERE " +
            "a.entityId = :businessEntityId AND " +
            "a.entityType = :entityType AND " +
            "a.status = 'IN_REVIEW'")
    boolean hasActiveInstance(@Param("businessEntityId") Long businessEntityId,
                              @Param("entityType") String entityType);

    @Query("SELECT a FROM WorkflowAuditInstance a WHERE a.flowDefId = :flowDefId")
    Page<AuditInstance> findAuditInstancesByFlowDefId(@Param("flowDefId") Long flowDefId, Pageable pageable);

    @Query("SELECT t FROM WorkflowRuntimeTask t")
    List<WorkflowTask> findAllWorkflowTasks();

    @Query("SELECT t FROM WorkflowRuntimeTask t WHERE t.id = :id")
    Optional<WorkflowTask> findWorkflowTaskById(@Param("id") Long id);

    @Query("SELECT t FROM WorkflowRuntimeTask t WHERE t.status = :status")
    List<WorkflowTask> findWorkflowTasksByStatus(@Param("status") String status);

    @Query("SELECT t FROM WorkflowRuntimeTask t WHERE t.assigneeId = :assigneeId")
    List<WorkflowTask> findWorkflowTasksByAssigneeId(@Param("assigneeId") Long assigneeId);

}
