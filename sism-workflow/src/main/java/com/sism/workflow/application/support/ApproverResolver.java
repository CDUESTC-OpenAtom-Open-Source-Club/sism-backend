package com.sism.workflow.application.support;

import com.sism.shared.domain.user.UserIdentity;
import com.sism.shared.domain.user.UserProvider;
import com.sism.shared.domain.workflow.WorkflowBusinessContextPort;
import com.sism.workflow.domain.definition.AuditStepDef;
import com.sism.workflow.domain.definition.FlowDefinitionRepository;
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
    /** 存量月报实例沿用的旧实体类型（与 execution 侧 adapter / workflow 侧口径一致） */
    private static final String LEGACY_PLAN_REPORT_ENTITY_TYPE = "PlanReport";
    private static final String COLLEGE_FINAL_APPROVAL_STEP_NAME = "职能部门终审";
    private static final String FUNCTIONAL_DEPT_STEP_NAME = "职能部门";
    /** 2026-10-26 用户定案：学院链第 4 环「分管职能部门校领导审批」——席位归职能部门本级 */
    private static final String FUNCTIONAL_VICE_PRESIDENT_STEP_NAME = "分管职能部门校领导";
    private static final String COLLEGE_APPROVAL_FLOW_CODE = "PLAN_APPROVAL_COLLEGE";

    private final UserProvider userProvider;
    private final List<WorkflowBusinessContextPort> workflowBusinessContextPorts;
    private final WorkflowApproverProperties workflowApproverProperties;
    private final FlowDefinitionRepository flowDefinitionRepository;

    public ApproverResolver(
            UserProvider userProvider,
            List<WorkflowBusinessContextPort> workflowBusinessContextPorts,
            WorkflowApproverProperties workflowApproverProperties,
            FlowDefinitionRepository flowDefinitionRepository
    ) {
        this.userProvider = userProvider;
        this.workflowBusinessContextPorts = workflowBusinessContextPorts;
        this.workflowApproverProperties = workflowApproverProperties;
        this.flowDefinitionRepository = flowDefinitionRepository;
    }

    public Long resolveApproverId(AuditStepDef stepDef, Long requesterId, Long requesterOrgId) {
        return resolveApproverId(stepDef, requesterId, requesterOrgId, null);
    }

    public Long resolveApproverId(AuditStepDef stepDef, Long requesterId, Long requesterOrgId, AuditInstance instance) {
        Long roleId = stepDef.getRoleId();
        if (roleId == null || roleId <= 0) {
            throw new IllegalStateException("Workflow step is missing role assignment: " + stepDef.getStepName());
        }

        Long scopeOrgId = resolveStepScopeOrgId(stepDef, requesterOrgId, instance);
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

        Long scopeOrgId = resolveStepScopeOrgId(stepDef, requesterOrgId, instance);
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
        return resolveCandidates(stepDef, requesterOrgId, null);
    }

    /**
     * N-03（2026-10-30）：候选审批人解析与派单、鉴权共用同一个范围组织解析入口。
     * 传入实例时，学院上报链里归属职能部门的节点会解析到职能部门本级，
     * 避免「界面显示的是学院的人、后端派的却是职能部门的人」这类口径漂移。
     */
    public List<ApproverCandidateResponse> resolveCandidates(
            AuditStepDef stepDef,
            Long requesterOrgId,
            AuditInstance instance) {
        Long roleId = stepDef.getRoleId();
        if (roleId == null || roleId <= 0) {
            throw new IllegalStateException("Workflow step is missing role assignment: " + stepDef.getStepName());
        }

        Long scopeOrgId = resolveStepScopeOrgId(stepDef, requesterOrgId, instance);

        return findScopedActiveUsersByRole(roleId, scopeOrgId).stream()
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

    /**
     * N-03（2026-10-30）：审批鉴权入口。范围组织一律由 {@link #resolveStepScopeOrgId} 统一解析，
     * 调用方不再自行传 requesterOrgId 决定「谁能审这一步」。
     */
    public boolean canUserApprove(AuditStepDef stepDef, Long userId, AuditInstance instance) {
        return canUserApprove(stepDef, userId, instance == null ? null : instance.getRequesterOrgId(), instance);
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
                    Long scopeOrgId = resolveStepScopeOrgId(stepDef, requesterOrgId, instance);
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

    // ==================== N-03：步骤审批范围组织（唯一解析入口） ====================

    /**
     * N-03（2026-10-30）：唯一的「步骤审批范围组织」解析入口。
     *
     * <p>同一个节点此前在三处各算各的：
     * <ul>
     *   <li>派单（resolveApproverId / resolveAssignedApproverId）</li>
     *   <li>预览候选（resolveCandidates）</li>
     *   <li>审批鉴权（canUserApprove）</li>
     * </ul>
     * 三者一旦口径漂移，就会出现「前端卡片显示可审批 → 点下去后端 403」的死锁
     * （线上现象：二级学院上报链第 4 环「职能部门审批人审批」无人能推进）。
     * 现在三处一律调用本函数，不允许各自换算。
     *
     * <p>解析口径：
     * <ol>
     *   <li>学院上报链（PLAN_APPROVAL_COLLEGE）中归属「职能部门」的节点
     *       （职能部门审批人审批 / 分管职能部门负责人审批 / 分管职能部门校领导审批 / 职能部门终审）
     *       → scopeOrgId = 该学院计划所属的职能部门
     *       （业务摘要 sourceOrgId = plan.createdByOrgId，即下发该学院的职能部门）；</li>
     *   <li>其余节点 → scopeOrgId = 请求方组织（requesterOrgId）。</li>
     * </ol>
     */
    public Long resolveStepScopeOrgId(AuditStepDef stepDef, AuditInstance instance) {
        return resolveStepScopeOrgId(stepDef, instance == null ? null : instance.getRequesterOrgId(), instance);
    }

    /**
     * @param requesterOrgId 请求方组织兜底值（实例缺失时用它；实例存在时以实例为准）
     */
    public Long resolveStepScopeOrgId(AuditStepDef stepDef, Long requesterOrgId, AuditInstance instance) {
        if (!isFunctionalDeptScopedStep(stepDef, instance)) {
            return requesterOrgId;
        }
        return resolveFunctionalDeptOrgId(instance, requesterOrgId);
    }

    /**
     * 职能部门本级组织 = 业务摘要里的 sourceOrgId（= plan.createdByOrgId）。
     */
    private Long resolveFunctionalDeptOrgId(AuditInstance instance, Long fallbackOrgId) {
        if (instance == null) {
            return fallbackOrgId;
        }
        return resolveBusinessSummary(instance.getEntityType(), instance.getEntityId())
                .map(summary -> summary.sourceOrgId() != null ? summary.sourceOrgId() : fallbackOrgId)
                .orElse(fallbackOrgId);
    }

    /**
     * 是否是「审批范围应指到职能部门」的节点。
     * 保留既有两个特判（学院终审步 / 分管职能部门校领导步），
     * 再叠加一条不依赖 roleId 的兜底：学院上报链里步骤名含「职能部门」的席位节点。
     */
    private boolean isFunctionalDeptScopedStep(AuditStepDef stepDef, AuditInstance instance) {
        return isCollegeFinalApprovalStep(stepDef, instance)
                || isCollegeFunctionalVicePresidentStep(stepDef, instance)
                || isCollegeFunctionalDeptApprovalStep(stepDef, instance);
    }

    /**
     * 学院上报链里归属「职能部门」的席位节点（不限 roleId）。
     *
     * <p>覆盖存量链与新链的多种命名，避免步骤改名 / 角色调整后出现
     * 「派单按职能部门、鉴权按学院」的口径漂移：
     * <ul>
     *   <li>职能部门审批人审批（存量第 4 环，role 2）</li>
     *   <li>分管职能部门负责人审批（role 2）</li>
     *   <li>分管职能部门校领导审批（role 4）</li>
     *   <li>职能部门终审（存量终审步）</li>
     * </ul>
     * 判定只依赖「学院上报链 + 步骤名含职能部门」，学院本级席位
     * （学院审核人审批 / 二级学院审批人审批）不含该关键字，不会被误伤。
     */
    private boolean isCollegeFunctionalDeptApprovalStep(AuditStepDef stepDef, AuditInstance instance) {
        if (stepDef == null || instance == null) {
            return false;
        }
        if (!isPlanOrPlanReportEntityType(instance.getEntityType())) {
            return false;
        }
        if (!isCollegeApprovalFlow(instance)) {
            return false;
        }
        String stepName = stepDef.getStepName();
        return stepName != null && stepName.contains(FUNCTIONAL_DEPT_STEP_NAME);
    }

    private boolean isPlanOrPlanReportEntityType(String entityType) {
        return entityType != null
                && (PLAN_ENTITY_TYPE.equalsIgnoreCase(entityType)
                    || PLAN_REPORT_ENTITY_TYPE.equalsIgnoreCase(entityType)
                    || LEGACY_PLAN_REPORT_ENTITY_TYPE.equalsIgnoreCase(entityType));
    }

    /**
     * 2026-10-26 用户定案：学院上报链第 4 环「分管职能部门校领导审批」——
     * 席位属于「分管该学院的职能部门」，而不是学院自己。
     * 判定条件：学院审批流（PLAN_APPROVAL_COLLEGE）+ 步骤名含「分管职能部门校领导」+ role 4。
     * 解析出的 scopeOrgId 会被 matchesRoleScope 的 vice-president 分支
     * 再经 functional-vice-president-scope-by-org 归一，确保命中该职能部门的 leader 席位。
     */
    private boolean isCollegeFunctionalVicePresidentStep(AuditStepDef stepDef, AuditInstance instance) {
        if (stepDef == null || instance == null) {
            return false;
        }
        if (!isPlanOrPlanReportEntityType(instance.getEntityType())) {
            return false;
        }
        if (!getVicePresidentRoleId().equals(stepDef.getRoleId())) {
            return false;
        }
        if (!isCollegeApprovalFlow(instance)) {
            return false;
        }
        String stepName = stepDef.getStepName();
        return stepName != null && stepName.contains(FUNCTIONAL_VICE_PRESIDENT_STEP_NAME);
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
        if (!isPlanOrPlanReportEntityType(instance.getEntityType())) {
            return false;
        }

        if (!getApproverRoleId().equals(stepDef.getRoleId())) {
            return false;
        }

        // 仅学院审批流（PLAN_APPROVAL_COLLEGE）中的「职能部门」节点需要把审批范围
        // 指到职能部门（sourceOrgId）；职能部门自身链（PLAN_APPROVAL_FUNCDEPT）里
        // 同名步骤的 requesterOrgId 本就是职能部门，不适用。
        if (!isCollegeApprovalFlow(instance)) {
            return false;
        }

        if (Boolean.TRUE.equals(stepDef.getIsTerminal())) {
            return true;
        }

        String stepName = stepDef.getStepName();
        // 新 5 节点链：第 4 步「职能部门审批人审批」（非终审）；
        // 旧 4 节点链：终审步骤名含「职能部门终审」（存量实例兜底）。
        boolean matched = stepName != null
                && (stepName.contains(FUNCTIONAL_DEPT_STEP_NAME)
                    || stepName.contains(COLLEGE_FINAL_APPROVAL_STEP_NAME));
        if (matched && !stepName.contains(FUNCTIONAL_DEPT_STEP_NAME)) {
            log.warn("Using legacy step-name fallback for college final approval scope: stepName={}", stepName);
        }
        return matched;
    }

    private boolean isCollegeApprovalFlow(AuditInstance instance) {
        if (instance == null || instance.getFlowDefId() == null) {
            return false;
        }
        return flowDefinitionRepository.findById(instance.getFlowDefId())
                .map(flowDef -> COLLEGE_APPROVAL_FLOW_CODE.equalsIgnoreCase(flowDef.getFlowCode()))
                .orElse(false);
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
