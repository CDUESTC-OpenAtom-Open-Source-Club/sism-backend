package com.sism.workflow.infrastructure.persistence;

import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AuditInstanceRepositoryImpl implements AuditInstanceRepository {

    private final JpaWorkflowRepository jpaWorkflowRepository;

    @Override
    public Optional<AuditInstance> findById(Long id) {
        return jpaWorkflowRepository.findByIdWithSteps(id);
    }

    @Override
    public Optional<AuditInstance> findByStepInstanceId(Long stepInstanceId) {
        return jpaWorkflowRepository.findByStepInstanceId(stepInstanceId);
    }

    @Override
    public List<AuditInstance> findAll() {
        return jpaWorkflowRepository.findAll();
    }

    @Override
    public List<AuditInstance> findByBusinessTypeAndBusinessId(String businessType, Long businessId) {
        return jpaWorkflowRepository.findByBusinessTypeAndBusinessId(businessType, businessId);
    }

    @Override
    public List<AuditInstance> findByBusinessId(Long businessId) {
        return jpaWorkflowRepository.findByBusinessId(businessId);
    }

    @Override
    public List<AuditInstance> findByStatus(String status) {
        return jpaWorkflowRepository.findByStatus(status);
    }

    @Override
    public List<AuditInstance> findByInitiatorId(Long initiatorId) {
        return jpaWorkflowRepository.findByInitiatorId(initiatorId);
    }

    @Override
    public AuditInstance save(AuditInstance auditInstance) {
        return jpaWorkflowRepository.saveAndFlush(auditInstance);
    }

    @Override
    public void delete(AuditInstance auditInstance) {
        jpaWorkflowRepository.delete(auditInstance);
    }

    @Override
    public boolean existsById(Long id) {
        return jpaWorkflowRepository.existsById(id);
    }

    @Override
    public boolean hasActiveInstance(Long businessEntityId, String entityType) {
        return jpaWorkflowRepository.hasActiveInstance(businessEntityId, entityType);
    }

    @Override
    public long countPendingBatchSiblingsAtStep(String batchId, Long excludeInstanceId, Integer stepNo) {
        return jpaWorkflowRepository.countPendingBatchSiblingsAtStep(batchId, excludeInstanceId, stepNo);
    }

    @Override
    public List<AuditInstance> findByBatchId(String batchId) {
        return jpaWorkflowRepository.findByBatchId(batchId);
    }

    @Override
    public Page<AuditInstance> findByFlowDefId(Long flowDefId, Pageable pageable) {
        return jpaWorkflowRepository.findAuditInstancesByFlowDefId(flowDefId, pageable);
    }

    @Override
    public long countAll() {
        return jpaWorkflowRepository.countAuditInstances();
    }

    @Override
    public long countPending() {
        return jpaWorkflowRepository.countPendingAuditInstances();
    }

    @Override
    public long countApproved() {
        return jpaWorkflowRepository.countApprovedAuditInstances();
    }

    @Override
    public long countRejected() {
        return jpaWorkflowRepository.countRejectedAuditInstances();
    }
}
