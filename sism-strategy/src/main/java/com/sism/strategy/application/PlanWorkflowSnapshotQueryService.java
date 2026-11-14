package com.sism.strategy.application;

import com.sism.iam.domain.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PlanWorkflowSnapshotQueryService {

    private static final String PLAN_ENTITY_TYPE = "PLAN";

    @PersistenceContext
    private EntityManager entityManager;

    private final UserRepository userRepository;

    public PlanWorkflowSnapshotQueryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public WorkflowSnapshot getWorkflowSnapshotByPlanId(Long planId) {
        return getWorkflowSnapshot(PLAN_ENTITY_TYPE, planId);
    }

    public Map<Long, WorkflowSnapshot> getWorkflowSnapshotsByPlanIds(Collection<Long> planIds) {
        if (planIds == null || planIds.isEmpty()) {
            return Map.of();
        }

        List<Long> normalizedPlanIds = planIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (normalizedPlanIds.isEmpty()) {
            return Map.of();
        }

        List<?> instanceRows = entityManager.createNativeQuery("""
                SELECT DISTINCT ON (ai.entity_id)
                    ai.entity_id,
                    ai.id,
                    ai.status,
                    ai.requester_id,
                    ai.started_at,
                    ai.completed_at
                FROM audit_instance ai
                WHERE ai.entity_type = :entityType
                  AND ai.entity_id IN :entityIds
                ORDER BY ai.entity_id,
                         -- 2026-10-31 修复（P-06）：非终态（IN_REVIEW/PENDING）实例优先，
                         -- 撤回-重发循环下不再绑到 WITHDRAWN 旧实例；全是终态时排序退化
                         -- 为原 started_at/id 口径，行为不变
                         CASE WHEN ai.status IN ('IN_REVIEW', 'PENDING') THEN 0 ELSE 1 END,
                         ai.started_at DESC NULLS LAST,
                         ai.id DESC
                """)
                .setParameter("entityType", PLAN_ENTITY_TYPE)
                .setParameter("entityIds", normalizedPlanIds)
                .getResultList();

        Map<Long, AuditInstanceRow> latestInstanceByPlanId = new LinkedHashMap<>();
        for (Object row : instanceRows) {
            Object[] columns = (Object[]) row;
            Long planId = asLong(columns[0]);
            String status = asString(columns[2]);
            if (planId == null) {
                continue;
            }
            latestInstanceByPlanId.put(
                    planId,
                    new AuditInstanceRow(
                            asLong(columns[1]),
                            status,
                            asLong(columns[3]),
                            asLocalDateTime(columns[4]),
                            asLocalDateTime(columns[5])
                    )
            );
        }

        if (latestInstanceByPlanId.isEmpty()) {
            return Map.of();
        }

        Set<Long> requesterIds = latestInstanceByPlanId.values().stream()
                .map(AuditInstanceRow::getRequesterId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> requesterNamesById = resolveUserNames(requesterIds);

        Map<Long, WorkflowSnapshot> snapshotsByPlanId = new LinkedHashMap<>();
        latestInstanceByPlanId.forEach((planId, instance) -> snapshotsByPlanId.put(
                planId,
                WorkflowSnapshot.builder()
                        .workflowInstanceId(instance.getInstanceId())
                        .workflowStatus(instance.getStatus())
                        .starterId(instance.getRequesterId())
                        .starterName(requesterNamesById.getOrDefault(
                                instance.getRequesterId(),
                                instance.getRequesterId() == null ? null : "User#" + instance.getRequesterId()
                        ))
                        .startedAt(instance.getStartedAt())
                        .completedAt(instance.getCompletedAt())
                        .build()
        ));

        Map<Long, Long> planIdByInstanceId = latestInstanceByPlanId.entrySet().stream()
                .collect(Collectors.toMap(entry -> entry.getValue().getInstanceId(), Map.Entry::getKey));
        List<Long> instanceIds = latestInstanceByPlanId.values().stream()
                .map(AuditInstanceRow::getInstanceId)
                .toList();

        hydrateCurrentPendingSteps(instanceIds, planIdByInstanceId, snapshotsByPlanId);
        hydrateWithdrawFlags(instanceIds, planIdByInstanceId, snapshotsByPlanId);
        hydrateLastRejectReasons(instanceIds, planIdByInstanceId, snapshotsByPlanId);

        return snapshotsByPlanId;
    }

    public WorkflowSnapshot getWorkflowSnapshot(String entityType, Long entityId) {
        AuditInstanceRow instance = findLatestInstance(entityType, entityId);
        if (instance == null) {
            return null;
        }

        Map<Long, String> userNamesById = resolveUserNames(Set.of(instance.getRequesterId()));

        WorkflowSnapshot snapshot = WorkflowSnapshot.builder()
                .workflowInstanceId(instance.getInstanceId())
                .workflowStatus(instance.getStatus())
                .starterId(instance.getRequesterId())
                .starterName(userNamesById.getOrDefault(
                        instance.getRequesterId(),
                        instance.getRequesterId() == null ? null : "User#" + instance.getRequesterId()))
                .startedAt(instance.getStartedAt())
                .completedAt(instance.getCompletedAt())
                .build();

        List<?> currentSteps = entityManager.createNativeQuery("""
                SELECT asi.step_name, asi.approver_id, asi.status
                FROM audit_step_instance asi
                WHERE asi.instance_id = :instanceId
                  AND asi.status IN ('PENDING', 'WITHDRAWN')
                ORDER BY CASE WHEN asi.status = 'PENDING' THEN 0 ELSE 1 END,
                         asi.step_no DESC,
                         asi.id DESC
                LIMIT 1
                """)
                .setParameter("instanceId", instance.getInstanceId())
                .getResultList();

        if (!currentSteps.isEmpty()) {
            Object[] stepRow = (Object[]) currentSteps.get(0);
            Long approverId = asLong(stepRow[1]);
            if (approverId != null && !userNamesById.containsKey(approverId)) {
                userNamesById = new HashMap<>(userNamesById);
                userNamesById.putAll(resolveUserNames(Set.of(approverId)));
            }
            snapshot.setCurrentStepName(asString(stepRow[0]));
            snapshot.setCurrentApproverId(approverId);
            snapshot.setCurrentApproverName(userNamesById.getOrDefault(
                    approverId,
                    approverId == null ? null : "User#" + approverId));
        }

        boolean hasCurrentPendingStep = !currentSteps.isEmpty()
                && "PENDING".equalsIgnoreCase(asString(((Object[]) currentSteps.get(0))[2]));
        Integer currentPendingStepNo = !currentSteps.isEmpty()
                ? asInteger(fetchCurrentPendingStepNo(instance.getInstanceId()))
                : null;
        boolean previousSubmitApproved = currentPendingStepNo != null
                && currentPendingStepNo > 1
                && hasApprovedSubmitStep(instance.getInstanceId(), currentPendingStepNo - 1);
        // 2026-11-11（驳回退回态撤回放行）：审批人驳回后实例生成「退回待重报」PENDING 步骤，
        // 其前一步（pendingNo-1）是 REJECTED 的 APPROVAL 步骤——原口径只认「前一步 APPROVED
        // 且为 SUBMIT」，驳回退回后 canWithdraw 恒 false，发起人被永久锁死在退回态。
        // 补第四条通路：前一步 REJECTED 即退回待重报，发起人放弃重来的撤回应放行。
        boolean returnedForResubmit = currentPendingStepNo != null
                && currentPendingStepNo > 1
                && hasRejectedPreviousStep(instance.getInstanceId(), currentPendingStepNo - 1);
        boolean canWithdraw = "IN_REVIEW".equalsIgnoreCase(snapshot.getWorkflowStatus())
                && hasCurrentPendingStep
                && (previousSubmitApproved || returnedForResubmit);
        snapshot.setCanWithdraw(canWithdraw);
        snapshot.setLastRejectReason(findLastRejectReason(instance.getInstanceId()));
        return snapshot;
    }

    private void hydrateCurrentPendingSteps(List<Long> instanceIds,
                                            Map<Long, Long> planIdByInstanceId,
                                            Map<Long, WorkflowSnapshot> snapshotsByPlanId) {
        if (instanceIds.isEmpty()) {
            return;
        }

        List<?> currentSteps = entityManager.createNativeQuery("""
                SELECT DISTINCT ON (asi.instance_id)
                    asi.instance_id,
                    asi.step_name,
                    asi.approver_id,
                    asi.status
                FROM audit_step_instance asi
                WHERE asi.instance_id IN :instanceIds
                  AND asi.status IN ('PENDING', 'WITHDRAWN')
                ORDER BY asi.instance_id,
                         CASE WHEN asi.status = 'PENDING' THEN 0 ELSE 1 END,
                         asi.step_no DESC,
                         asi.id DESC
                """)
                .setParameter("instanceIds", instanceIds)
                .getResultList();

        Set<Long> approverIds = currentSteps.stream()
                .map(Object[].class::cast)
                .map(row -> asLong(row[2]))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> userNamesById = resolveUserNames(approverIds);

        for (Object row : currentSteps) {
            Object[] columns = (Object[]) row;
            Long instanceId = asLong(columns[0]);
            Long planId = planIdByInstanceId.get(instanceId);
            WorkflowSnapshot snapshot = snapshotsByPlanId.get(planId);
            if (snapshot == null) {
                continue;
            }
            Long approverId = asLong(columns[2]);
            snapshot.setCurrentStepName(asString(columns[1]));
            snapshot.setCurrentApproverId(approverId);
            snapshot.setCurrentApproverName(userNamesById.getOrDefault(approverId, approverId == null ? null : "User#" + approverId));
        }
    }

    private void hydrateWithdrawFlags(List<Long> instanceIds,
                                      Map<Long, Long> planIdByInstanceId,
                                      Map<Long, WorkflowSnapshot> snapshotsByPlanId) {
        if (instanceIds.isEmpty()) {
            return;
        }

        List<?> pendingCounts = entityManager.createNativeQuery("""
                SELECT asi.instance_id, COUNT(1)
                FROM audit_step_instance asi
                WHERE asi.instance_id IN :instanceIds
                  AND asi.status = 'PENDING'
                GROUP BY asi.instance_id
                """)
                .setParameter("instanceIds", instanceIds)
                .getResultList();

        Map<Long, Long> pendingCountByInstanceId = new HashMap<>();
        for (Object row : pendingCounts) {
            Object[] columns = (Object[]) row;
            pendingCountByInstanceId.put(asLong(columns[0]), ((Number) columns[1]).longValue());
        }

        planIdByInstanceId.forEach((instanceId, planId) -> {
            WorkflowSnapshot snapshot = snapshotsByPlanId.get(planId);
            if (snapshot == null) {
                return;
            }
            long pendingStepCount = pendingCountByInstanceId.getOrDefault(instanceId, 0L);
            boolean hasCurrentPendingStep = pendingStepCount > 0L;
            Integer currentPendingStepNo = asInteger(fetchCurrentPendingStepNo(instanceId));
            boolean previousSubmitApproved = currentPendingStepNo != null
                    && currentPendingStepNo > 1
                    && hasApprovedSubmitStep(instanceId, currentPendingStepNo - 1);
            // 2026-11-11（驳回退回态撤回放行）：与单实例路径同口径，补「前一步 REJECTED」
            // 退回待重报通路——否则列表页驳回退回的单据永远不显示可撤回。
            boolean returnedForResubmit = currentPendingStepNo != null
                    && currentPendingStepNo > 1
                    && hasRejectedPreviousStep(instanceId, currentPendingStepNo - 1);
            boolean canWithdraw = "IN_REVIEW".equalsIgnoreCase(snapshot.getWorkflowStatus())
                    && hasCurrentPendingStep
                    && (previousSubmitApproved || returnedForResubmit);
            snapshot.setCanWithdraw(canWithdraw);
        });
    }

    private Object fetchCurrentPendingStepNo(Long instanceId) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT asi.step_no
                FROM audit_step_instance asi
                WHERE asi.instance_id = :instanceId
                  AND asi.status = 'PENDING'
                ORDER BY asi.step_no DESC, asi.id DESC
                LIMIT 1
                """)
                .setParameter("instanceId", instanceId)
                .getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean hasApprovedSubmitStep(Long instanceId, int stepNo) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT COUNT(1)
                FROM audit_step_instance asi
                WHERE asi.instance_id = :instanceId
                  AND asi.step_no = :stepNo
                  AND asi.status = 'APPROVED'
                  AND asi.step_name LIKE '%提交%'
                """)
                .setParameter("instanceId", instanceId)
                .setParameter("stepNo", stepNo)
                .getResultList();
        long count = rows.isEmpty() ? 0L : ((Number) rows.get(0)).longValue();
        return count > 0L;
    }

    /**
     * 2026-11-11（驳回退回态撤回放行）：pending 步骤的前一相邻步骤（pendingNo-1）
     * 是否为 REJECTED。
     *
     * <p>驳回续链语义：审批人驳回后流程不终止，而是在 REJECTED 步骤之后生成新的
     * 「退回待重报」PENDING 步骤（step_no 顺延），实例保持 IN_REVIEW。此时
     * {@link #hasApprovedSubmitStep} 的「前一步 APPROVED 且为 SUBMIT」恒 false，
     * 发起人撤回被 400 拦截、永久锁死在退回态。该判定补上这条通路：
     * 仅当 pending 的<b>直接前一步</b>是 REJECTED 时放行——
     * <ul>
     *   <li>首步 PENDING（pendingNo=1）无前一步，不命中；</li>
     *   <li>正常审批中（前一步 APPROVED，无论 SUBMIT 还是 APPROVAL），不命中；</li>
     *   <li>历史 REJECTED 行不在 pendingNo-1 位置（如退回重报后已重新通过），
     *       不命中——只认相邻步骤，避免历史驳回误放行。</li>
     * </ul>
     */
    private boolean hasRejectedPreviousStep(Long instanceId, int stepNo) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT COUNT(1)
                FROM audit_step_instance asi
                WHERE asi.instance_id = :instanceId
                  AND asi.step_no = :stepNo
                  AND asi.status = 'REJECTED'
                """)
                .setParameter("instanceId", instanceId)
                .setParameter("stepNo", stepNo)
                .getResultList();
        long count = rows.isEmpty() ? 0L : ((Number) rows.get(0)).longValue();
        return count > 0L;
    }

    private void hydrateLastRejectReasons(List<Long> instanceIds,
                                          Map<Long, Long> planIdByInstanceId,
                                          Map<Long, WorkflowSnapshot> snapshotsByPlanId) {
        if (instanceIds.isEmpty()) {
            return;
        }

        List<?> rejectRows = entityManager.createNativeQuery("""
                SELECT DISTINCT ON (asi.instance_id)
                    asi.instance_id,
                    asi.comment
                FROM audit_step_instance asi
                WHERE asi.instance_id IN :instanceIds
                  AND asi.status = 'REJECTED'
                ORDER BY asi.instance_id, asi.approved_at DESC NULLS LAST, asi.created_at DESC NULLS LAST, asi.id DESC
                """)
                .setParameter("instanceIds", instanceIds)
                .getResultList();

        for (Object row : rejectRows) {
            Object[] columns = (Object[]) row;
            Long instanceId = asLong(columns[0]);
            Long planId = planIdByInstanceId.get(instanceId);
            WorkflowSnapshot snapshot = snapshotsByPlanId.get(planId);
            if (snapshot != null) {
                snapshot.setLastRejectReason(asString(columns[1]));
            }
        }
    }

    public List<WorkflowHistoryItem> getWorkflowHistoryByPlanId(Long planId) {
        AuditInstanceRow instance = findLatestInstance(PLAN_ENTITY_TYPE, planId);
        if (instance == null) {
            return List.of();
        }
        return getWorkflowHistory(instance.getInstanceId());
    }

    public List<WorkflowHistoryItem> getWorkflowHistory(Long workflowInstanceId) {
        if (workflowInstanceId == null) {
            return List.of();
        }

        List<WorkflowHistoryItem> history = new ArrayList<>();
        List<?> rows = entityManager.createNativeQuery("""
                SELECT asi.id, asi.step_name, asi.approver_id, asi.status, asi.comment, asi.approved_at, asi.created_at
                FROM audit_step_instance asi
                WHERE asi.instance_id = :instanceId
                  AND asi.status IN ('APPROVED', 'REJECTED', 'WITHDRAWN')
                ORDER BY asi.step_no ASC, asi.approved_at ASC, asi.created_at ASC
                """)
                .setParameter("instanceId", workflowInstanceId)
                .getResultList();

        Set<Long> operatorIds = rows.stream()
                .map(Object[].class::cast)
                .map(columns -> asLong(columns[2]))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> userNamesById = resolveUserNames(operatorIds);

        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            Long approverId = asLong(columns[2]);
            history.add(WorkflowHistoryItem.builder()
                    .taskId(asLong(columns[0]))
                    .stepName(asString(columns[1]))
                    .operatorId(approverId)
                    .operatorName(userNamesById.getOrDefault(
                            approverId,
                            approverId == null ? null : "User#" + approverId))
                    .action(resolveHistoryAction(asString(columns[3])))
                    .comment(asString(columns[4]))
                    .operateTime(asLocalDateTime(columns[5]) != null ? asLocalDateTime(columns[5]) : asLocalDateTime(columns[6]))
                    .build());
        }
        return history;
    }

    private String resolveHistoryAction(String status) {
        if ("REJECTED".equalsIgnoreCase(status)) {
            return "REJECT";
        }
        if ("WITHDRAWN".equalsIgnoreCase(status)) {
            return "WITHDRAW";
        }
        return "APPROVE";
    }

    private AuditInstanceRow findLatestInstance(String entityType, Long entityId) {
        if (entityId == null || entityType == null || entityType.isBlank()) {
            return null;
        }

        List<?> instances = entityManager.createNativeQuery("""
                SELECT ai.id, ai.status, ai.requester_id, ai.started_at, ai.completed_at
                FROM audit_instance ai
                WHERE ai.entity_type = :entityType
                  AND ai.entity_id = :entityId
                ORDER BY
                    -- 2026-10-31 修复（P-06）：与 getWorkflowSnapshotsByPlanIds 同口径——
                    -- 非终态（IN_REVIEW/PENDING）实例优先，撤回-重发循环下不再绑到
                    -- WITHDRAWN 旧实例；全是终态时退化为原 started_at/id 口径
                    CASE WHEN ai.status IN ('IN_REVIEW', 'PENDING') THEN 0 ELSE 1 END,
                    ai.started_at DESC NULLS LAST,
                    ai.id DESC
                LIMIT 1
                """)
                .setParameter("entityType", entityType)
                .setParameter("entityId", entityId)
                .getResultList();
        if (instances.isEmpty()) {
            return null;
        }

        Object[] row = (Object[]) instances.get(0);
        return new AuditInstanceRow(
                asLong(row[0]),
                asString(row[1]),
                asLong(row[2]),
                asLocalDateTime(row[3]),
                asLocalDateTime(row[4]));
    }

    private String findLastRejectReason(Long workflowInstanceId) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT asi.comment
                FROM audit_step_instance asi
                WHERE asi.instance_id = :instanceId
                  AND asi.status = 'REJECTED'
                ORDER BY asi.approved_at DESC NULLS LAST, asi.created_at DESC NULLS LAST, asi.id DESC
                LIMIT 1
                """)
                .setParameter("instanceId", workflowInstanceId)
                .getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        return asString(rows.get(0));
    }

    private Map<Long, String> resolveUserNames(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }

        Set<Long> normalizedUserIds = userIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (normalizedUserIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> namesById = new HashMap<>();
        List<?> rows = entityManager.createNativeQuery("""
                SELECT u.id, u.real_name, u.username
                FROM sys_user u
                WHERE u.id IN :userIds
                """)
                .setParameter("userIds", normalizedUserIds)
                .getResultList();

        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            Long userId = asLong(columns[0]);
            String realName = asString(columns[1]);
            String username = asString(columns[2]);
            namesById.put(userId, realName != null && !realName.isBlank() ? realName : username);
        }
        return namesById;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private LocalDateTime asLocalDateTime(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        return null;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkflowSnapshot {
        private Long workflowInstanceId;
        private String workflowStatus;
        private Long starterId;
        private String starterName;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private String currentStepName;
        private Long currentApproverId;
        private String currentApproverName;
        private Boolean canWithdraw;
        private String lastRejectReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkflowHistoryItem {
        private Long taskId;
        private String stepName;
        private Long operatorId;
        private String operatorName;
        private String action;
        private String comment;
        private LocalDateTime operateTime;
    }

    @Data
    @AllArgsConstructor
    private static class AuditInstanceRow {
        private Long instanceId;
        private String status;
        private Long requesterId;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
    }
}
