package com.sism.workflow.application.support;

import com.sism.shared.domain.user.UserIdentity;
import com.sism.shared.domain.user.UserProvider;
import com.sism.shared.domain.workflow.WorkflowBusinessContextPort;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.interfaces.dto.ApproverCandidateResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@Slf4j
public class ApproverResolver {

    private static final String PLAN_ENTITY_TYPE = "PLAN";
    private static final String PLAN_REPORT_ENTITY_TYPE = "PLAN_REPORT";
    private static final String COLLEGE_FINAL_APPROVAL_STEP_NAME = "职能部门终审";

    private final UserProvider userProvider;
    private final List<WorkflowBusinessContextPort> workflowBusinessContextPorts;
    private final WorkflowApproverProperties workflowApproverProperties;

    public ApproverResolver(
            UserProvider userProvider,
            List<WorkflowBusinessContextPort> workflowBusinessContextPorts,
            WorkflowApproverProperties workflowApproverProperties
    ) {
        this.userProvider = userProvider;
        this.workflowBusinessContextPorts = workflowBusinessContextPorts;
        this.workflowApproverProperties = workflowApproverProperties;
    }

    public Long resolveApproverId(AuditStepDef stepDef, Long requesterId, Long requesterOrgId) {
        return resolveApproverId(stepDef, requesterId, requesterOrgId, null);
    }

    public Long resolveApproverId(AuditStepDef stepDef, Long requesterId, Long requesterOrgId, AuditInstance instance) {
        Long roleId = stepDef.getRoleId();
        if (roleId == null || roleId <= 0) {
            throw new IllegalStateException("Workflow step is missing role assignment: " + stepDef.getStepName());
        }

        Long scopeOrgId = resolveScopeOrgId(stepDef, requesterOrgId, instance);
        return resolveByRoleId(roleId, scopeOrgId, stepDef.getStepName());
    }

    public Long resolveAssignedApproverId(AuditStepDef stepDef, Long requesterId, Long requesterOrgId, AuditInstance instance) {
        if (stepDef == null) {
            throw new IllegalArgumentException("Workflow step is required");
        }
        if (stepDef.isSubmitStep()) {
            return requesterId;
        }
        if (requesterId != null
                && requesterId > 0
                && isDemoUser(requesterId)
                && canUserApprove(stepDef, requesterId, requesterOrgId, instance)) {
            return requesterId;
        }
        return resolveApproverId(stepDef, requesterId, requesterOrgId, instance);
    }

    private Long resolveByRoleId(Long roleId, Long requesterOrgId, String stepName) {
        List<UserIdentity> candidates = findScopedActiveUsersByRole(roleId, requesterOrgId);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No available approver candidates for step: " + stepName);
        }

        return candidates.stream()
                .map(UserIdentity::id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No available approver candidates for step: " + stepName));
    }

    public String resolveApproverName(Long userId) {
        if (userId == null || userId <= 0) {
            return "Unknown";
        }
        return userProvider.findIdentity(userId)
                .map(user -> {
                    if (user.realName() != null && !user.realName().isBlank()) {
                        return user.realName();
                    }
                    return user.username() != null ? user.username() : "User-" + userId;
                })
                .orElse("User-" + userId);
    }

    public Long resolveApproverOrgId(Long userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        return userProvider.getUserOrgId(userId)
                .orElse(null);
    }

    public Long resolveApproverOrgId(AuditStepDef stepDef, Long requesterOrgId, AuditInstance instance) {
        if (stepDef == null) {
            return requesterOrgId;
        }
        if (stepDef.isSubmitStep()) {
            return requesterOrgId;
        }

        Long scopeOrgId = resolveScopeOrgId(stepDef, requesterOrgId, instance);
        Long roleId = stepDef.getRoleId();
        if (getStrategyDeptHeadRoleId().equals(roleId)) {
            return getStrategyOrgId();
        }
        if (getVicePresidentRoleId().equals(roleId)) {
            return resolveVicePresidentScopeOrgId(scopeOrgId);
        }
        return scopeOrgId;
    }

    /**
     * 解析某审批节点的候选审批人（用于流程预览）。
     *
     * 注意：候选人为空是合法业务状态——预览方（如战略发展部）的组织范围内可能本就没有
     * 该节点的审批人（例如战略部用户查看职能链的"职能部门审批人"节点）。此处返回空列表，
     * 由调用方按空态展示；不得抛异常，否则预览接口会对正常页面渲染返回 400。
     * 真正缺失角色配置（roleId 未设置）仍视为配置错误并抛异常。
     */
    public List<ApproverCandidateResponse> resolveCandidates(AuditStepDef stepDef, Long requesterOrgId) {
        Long roleId = stepDef.getRoleId();
        if (roleId == null || roleId <= 0) {
            throw new IllegalStateException("Workflow step is missing role assignment: " + stepDef.getStepName());
        }

        return findScopedActiveUsersByRole(roleId, requesterOrgId).stream()
                .sorted(Comparator.comparing(UserIdentity::id))
                .map(user -> ApproverCandidateResponse.builder()
                        .userId(user.id())
                        .username(user.username())
                        .realName(user.realName())
                        .orgId(user.orgId())
                        .build())
                .toList();
    }

    public boolean canUserApprove(AuditStepDef stepDef, Long userId, Long requesterOrgId) {
        return canUserApprove(stepDef, userId, requesterOrgId, null);
    }

    public boolean canUserApprove(AuditStepDef stepDef, Long userId, Long requesterOrgId, AuditInstance instance) {
        if (stepDef == null || stepDef.isSubmitStep() || userId == null || userId <= 0) {
            return false;
        }

        Long roleId = stepDef.getRoleId();
        if (roleId == null || roleId <= 0) {
            return false;
        }

        List<Long> roleIds = userProvider.getUserRoleIds(userId);

        return userProvider.findIdentity(userId)
                .filter(user -> Boolean.TRUE.equals(user.isActive()))
                .filter(user -> roleIds.contains(roleId))
                .filter(user -> {
                    Long scopeOrgId = resolveScopeOrgId(stepDef, requesterOrgId, instance);
                    return matchesRoleScope(user, roleId, scopeOrgId);
                })
                .isPresent();
    }

    private boolean isDemoUser(Long userId) {
        return userProvider.findIdentity(userId)
                .map(UserIdentity::isDemo)
                .filter(Boolean.TRUE::equals)
                .isPresent();
    }

    public void validateSelectedApprover(AuditStepDef stepDef, Long approverId) {
        validateSelectedApprover(stepDef, approverId, null);
    }

    public void validateSelectedApprover(AuditStepDef stepDef, Long approverId, Long requesterOrgId) {
        if (stepDef == null || stepDef.isSubmitStep()) {
            return;
        }
        if (approverId == null || approverId <= 0) {
            throw new IllegalArgumentException("Approver selection is required for step: " + stepDef.getStepName());
        }

        Long roleId = stepDef.getRoleId();
        if (roleId == null || roleId <= 0) {
            userProvider.findIdentity(approverId)
                    .filter(user -> Boolean.TRUE.equals(user.isActive()))
                    .orElseThrow(() -> new IllegalArgumentException("Invalid approver for step: " + stepDef.getStepName()));
            return;
        }

        boolean valid = findScopedActiveUsersByRole(roleId, requesterOrgId).stream()
                .anyMatch(user -> approverId.equals(user.id()));
        if (!valid) {
            throw new IllegalArgumentException("Selected approver does not match step role: " + stepDef.getStepName());
        }
    }

    private List<UserIdentity> findScopedActiveUsersByRole(Long roleId, Long requesterOrgId) {
        return userProvider.findActiveIdentitiesByRole(roleId).stream()
                .filter(user -> Boolean.TRUE.equals(user.isActive()))
                .filter(user -> matchesRoleScope(user, roleId, requesterOrgId))
                .sorted(Comparator.comparing(UserIdentity::id))
                .toList();
    }

    private Long resolveScopeOrgId(AuditStepDef stepDef, Long requesterOrgId, AuditInstance instance) {
        if (!isCollegeFinalApprovalStep(stepDef, instance)) {
            return requesterOrgId;
        }
        return resolveBusinessSummary(instance.getEntityType(), instance.getEntityId())
                .map(summary -> summary.sourceOrgId() != null ? summary.sourceOrgId() : requesterOrgId)
                .orElse(requesterOrgId);
    }

    private java.util.Optional<WorkflowBusinessContextPort.BusinessSummary> resolveBusinessSummary(
            String entityType,
            Long entityId
    ) {
        for (WorkflowBusinessContextPort contextPort : workflowBusinessContextPorts) {
            java.util.Optional<WorkflowBusinessContextPort.BusinessSummary> summary =
                    contextPort.getBusinessSummary(entityType, entityId);
            if (summary.isPresent()) {
                return summary;
            }
        }
        return java.util.Optional.empty();
    }

    private boolean isCollegeFinalApprovalStep(AuditStepDef stepDef, AuditInstance instance) {
        if (stepDef == null || instance == null) {
            return false;
        }
        if (!PLAN_ENTITY_TYPE.equalsIgnoreCase(instance.getEntityType())
                && !PLAN_REPORT_ENTITY_TYPE.equalsIgnoreCase(instance.getEntityType())) {
            return false;
        }

        if (!getApproverRoleId().equals(stepDef.getRoleId())) {
            return false;
        }

        if (Boolean.TRUE.equals(stepDef.getIsTerminal())) {
            return true;
        }

        String stepName = stepDef.getStepName();
        boolean fallbackMatched = stepName != null && stepName.contains(COLLEGE_FINAL_APPROVAL_STEP_NAME);
        if (fallbackMatched) {
            log.warn("Using legacy step-name fallback for college final approval scope: stepName={}", stepName);
        }
        return fallbackMatched;
    }

    private boolean matchesRoleScope(UserIdentity user, Long roleId, Long requesterOrgId) {
        Long userOrgId = user.orgId();

        if (getApproverRoleId().equals(roleId)) {
            return requesterOrgId != null && requesterOrgId.equals(userOrgId);
        }
        if (getStrategyDeptHeadRoleId().equals(roleId)) {
            return isStrategyOrg(userOrgId);
        }
        if (getVicePresidentRoleId().equals(roleId)) {
            Long scopeOrgId = resolveVicePresidentScopeOrgId(requesterOrgId);
            return scopeOrgId != null && scopeOrgId.equals(userOrgId);
        }

        return true;
    }

    private Long resolveVicePresidentScopeOrgId(Long requesterOrgId) {
        Map<Long, Long> scopeByOrg = workflowApproverProperties.getFunctionalVicePresidentScopeByOrg();
        if (scopeByOrg == null || scopeByOrg.isEmpty()) {
            return requesterOrgId;
        }
        return scopeByOrg.getOrDefault(requesterOrgId, requesterOrgId);
    }

    private boolean isStrategyOrg(Long orgId) {
        return orgId != null && orgId.equals(getStrategyOrgId());
    }

    private Long getApproverRoleId() {
        return requireConfigured(workflowApproverProperties.getApproverRoleId(), "workflow.approver.approver-role-id");
    }

    private Long getStrategyDeptHeadRoleId() {
        return requireConfigured(
                workflowApproverProperties.getStrategyDeptHeadRoleId(),
                "workflow.approver.strategy-dept-head-role-id"
        );
    }

    private Long getVicePresidentRoleId() {
        return requireConfigured(
                workflowApproverProperties.getVicePresidentRoleId(),
                "workflow.approver.vice-president-role-id"
        );
    }

    private Long getStrategyOrgId() {
        return requireConfigured(workflowApproverProperties.getStrategyOrgId(), "workflow.approver.strategy-org-id");
    }

    private Long requireConfigured(Long value, String propertyName) {
        return Objects.requireNonNull(value, propertyName + " is not configured");
    }
}
