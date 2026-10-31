package com.sism.workflow.domain.runtime;

import com.sism.workflow.domain.runtime.AuditInstance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface AuditInstanceRepository {

    Optional<AuditInstance> findById(Long id);

    Optional<AuditInstance> findByStepInstanceId(Long stepInstanceId);

    List<AuditInstance> findAll();

    List<AuditInstance> findByBusinessTypeAndBusinessId(String businessType, Long businessId);

    List<AuditInstance> findByBusinessId(Long businessId);

    List<AuditInstance> findByStatus(String status);

    List<AuditInstance> findByInitiatorId(Long initiatorId);

    AuditInstance save(AuditInstance auditInstance);

    void delete(AuditInstance auditInstance);

    boolean existsById(Long id);

    boolean hasActiveInstance(Long businessEntityId, String entityType);

    /**
     * 2026-10-30 用户定案：异动按计划聚合，消息只有一条，无 N 项文案。
     * 统计同一异动批次内、与刚被审批实例同环节（stepNo）的其余在途实例数——
     * 大于 0 说明整批动作尚未完成，期间跳过逐实例结果/推进通知。
     */
    long countPendingBatchSiblingsAtStep(String batchId, Long excludeInstanceId, Integer stepNo);

    /**
     * 2026-10-30 用户定案（异动驳回后重报/取消闭环）：按批次号取同批全部实例，
     * 供「取消异动」把整批退回待重报实例一次性置为终态。
     */
    List<AuditInstance> findByBatchId(String batchId);

    Page<AuditInstance> findByFlowDefId(Long flowDefId, Pageable pageable);

    long countAll();

    long countPending();

    long countApproved();

    long countRejected();
}
