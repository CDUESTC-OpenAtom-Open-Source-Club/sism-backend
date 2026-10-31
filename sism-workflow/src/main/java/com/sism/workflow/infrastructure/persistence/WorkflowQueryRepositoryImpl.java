package com.sism.workflow.infrastructure.persistence;

import com.sism.workflow.domain.query.repository.WorkflowQueryRepository;
import com.sism.workflow.domain.runtime.AuditInstance;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class WorkflowQueryRepositoryImpl implements WorkflowQueryRepository {

    private final JpaWorkflowRepository jpaWorkflowRepository;

    @Override
    public Optional<AuditInstance> findAuditInstanceById(Long instanceId) {
        return jpaWorkflowRepository.findAuditInstanceById(instanceId);
    }

    @Override
    public Optional<AuditInstance> findPendingAuditInstanceByStepIdAndUserId(Long stepInstanceId, Long userId) {
        return jpaWorkflowRepository.findPendingAuditInstanceByStepIdAndUserId(stepInstanceId, userId);
    }

    @Override
    public List<AuditInstance> findPendingAuditInstancesByUserId(Long userId) {
        return jpaWorkflowRepository.findPendingAuditInstancesByUserId(userId);
    }

    @Override
    public Page<AuditInstance> findPendingAuditInstancesByUserId(Long userId, Pageable pageable) {
        return jpaWorkflowRepository.findPendingAuditInstancesByUserId(userId, pageable);
    }

    @Override
    public long countPendingTasksByUserId(Long userId) {
        return jpaWorkflowRepository.countPendingTasksByUserId(userId);
    }

    @Override
    public List<WorkflowQueryRepository.PendingTaskIdentity> findPendingTaskIdentitiesByUserId(Long userId) {
        return jpaWorkflowRepository.findPendingTaskIdentitiesByUserId(userId);
    }

    @Override
    public List<AuditInstance> findPendingAuditInstancesByUserIdAndBatchId(Long userId, String batchId) {
        return jpaWorkflowRepository.findPendingAuditInstancesByUserIdAndBatchId(userId, batchId);
    }

    @Override
    public List<AuditInstance> findApprovedAuditInstancesByUserId(Long userId) {
        return jpaWorkflowRepository.findApprovedAuditInstancesByUserId(userId);
    }

    @Override
    public Page<AuditInstance> findApprovedAuditInstancesByUserId(Long userId, Pageable pageable) {
        return jpaWorkflowRepository.findApprovedAuditInstancesByUserId(userId, pageable);
    }

    @Override
    public List<AuditInstance> findAppliedAuditInstancesByUserId(Long userId) {
        return jpaWorkflowRepository.findAppliedAuditInstancesByUserId(userId);
    }

    @Override
    public Page<AuditInstance> findAppliedAuditInstancesByUserId(Long userId, Pageable pageable) {
        return jpaWorkflowRepository.findAppliedAuditInstancesByUserId(userId, pageable);
    }

    @Override
    public List<AuditInstance> findAuditInstanceHistory(Long instanceId) {
        return jpaWorkflowRepository.findAuditInstanceHistory(instanceId);
    }
}
