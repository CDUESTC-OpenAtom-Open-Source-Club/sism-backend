package com.sism.main.application;

import com.sism.main.application.ExcelBusinessImportParser.ParsedWorkbook;
import com.sism.main.interfaces.dto.BusinessImportDtos.ConflictMode;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportAction;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportCommitRequest;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportCommitResponse;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportPreviewResponse;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportRowPreview;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportSummary;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportType;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportWorkflowResult;
import com.sism.main.interfaces.dto.BusinessImportDtos.NormalizedImportRow;
import com.sism.iam.domain.user.User;
import com.sism.iam.domain.user.UserRepository;
import com.sism.organization.domain.OrgType;
import com.sism.organization.domain.OrganizationRepository;
import com.sism.organization.domain.SysOrg;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.strategy.application.BasicTaskWeightValidationService;
import com.sism.strategy.application.StrategyApplicationService;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import com.sism.strategy.domain.repository.IndicatorRepository;
import com.sism.strategy.domain.repository.PlanRepository;
import com.sism.task.domain.repository.TaskRepository;
import com.sism.task.domain.task.StrategicTask;
import com.sism.task.domain.task.TaskType;
import com.sism.workflow.application.WorkflowApplicationService;
import com.sism.workflow.domain.definition.AuditFlowDef;
import com.sism.workflow.domain.runtime.AuditInstance;
import com.sism.workflow.domain.runtime.AuditInstanceRepository;
import com.sism.workflow.domain.runtime.AuditStepInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class BusinessImportApplicationService {

    private static final String SYSTEM_ADMIN_USERNAME = "admin";
    private static final String SYSTEM_ADMIN_ROLE_CODE = "ROLE_SYSTEM_ADMIN";
    private static final String STRATEGIC_WORKFLOW_CODE = "PLAN_DISPATCH_STRATEGY";
    private static final String DISTRIBUTION_WORKFLOW_CODE = "PLAN_DISPATCH_FUNCDEPT";

    private final ExcelBusinessImportParser parser;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final PlanRepository planRepository;
    private final TaskRepository taskRepository;
    private final IndicatorRepository indicatorRepository;
    private final StrategyApplicationService strategyApplicationService;
    private final BasicTaskWeightValidationService basicTaskWeightValidationService;
    private final WorkflowApplicationService workflowApplicationService;
    private final AuditInstanceRepository auditInstanceRepository;
    private final TransactionTemplate transactionTemplate;
    private final org.springframework.jdbc.core.JdbcTemplate importBatchJdbcTemplate;
    private final Map<String, PreviewContext> previews = new ConcurrentHashMap<>();

    public ImportPreviewResponse previewStrategicTasks(MultipartFile file,
                                                       Long cycleId,
                                                       Long targetOrgId,
                                                       String sheetName,
                                                       CurrentUser currentUser) {
        requireCycleAndUser(cycleId, currentUser);
        SysOrg targetOrg = requireOrg(targetOrgId, "目标职能部门不存在");
        if (targetOrg.getType() != OrgType.functional) {
            throw new IllegalArgumentException("战略任务导入目标必须是职能部门");
        }

        ParsedWorkbook parsed = parser.parse(file, ImportType.STRATEGIC_TASK, sheetName);
        List<ImportRowPreview> rows = enrichStrategicRows(parsed.rows(), cycleId, targetOrg, currentUser);
        return storePreview(file, parsed, rows, ImportType.STRATEGIC_TASK, cycleId, currentUser.getOrgId(), targetOrg, currentUser);
    }

    public ImportPreviewResponse previewDistribution(MultipartFile file,
                                                     Long cycleId,
                                                     Long targetCollegeOrgId,
                                                     Long sourceOrgId,
                                                     String sheetName,
                                                     CurrentUser currentUser) {
        requireCycleAndUser(cycleId, currentUser);
        Long effectiveSourceOrgId = sourceOrgId == null ? currentUser.getOrgId() : sourceOrgId;
        if (!Objects.equals(effectiveSourceOrgId, currentUser.getOrgId()) && !isSystemAdmin(currentUser)) {
            throw new SecurityException("只能导入当前职能部门的学院子指标");
        }
        SysOrg sourceOrg = requireOrg(effectiveSourceOrgId, "来源职能部门不存在");
        if (sourceOrg.getType() != OrgType.functional) {
            throw new IllegalArgumentException("学院子指标导入来源必须是职能部门");
        }
        SysOrg targetOrg = requireOrg(targetCollegeOrgId, "目标学院不存在");
        if (targetOrg.getType() != OrgType.academic) {
            throw new IllegalArgumentException("指标下发导入目标必须是学院");
        }

        ParsedWorkbook parsed = parser.parse(file, ImportType.DISTRIBUTION, sheetName);
        List<ImportRowPreview> rows = enrichDistributionRows(parsed.rows(), cycleId, sourceOrg, targetOrg);
        return storePreview(file, parsed, rows, ImportType.DISTRIBUTION, cycleId, sourceOrg.getId(), targetOrg, currentUser);
    }

    public ImportCommitResponse commit(String batchId,
                                       ImportCommitRequest request,
                                       CurrentUser currentUser) {
        PreviewContext context = previews.get(batchId);
        if (context == null) {
            throw new IllegalArgumentException("导入批次不存在或已过期");
        }
        if (!Objects.equals(context.currentUserId(), currentUser.getId())) {
            throw new SecurityException("只能确认自己上传的导入批次");
        }
        if (!Objects.equals(context.confirmToken(), request.confirmToken())) {
            throw new IllegalArgumentException("预览内容已变化，请重新上传并解析");
        }
        if (context.response().blocking()) {
            throw new IllegalArgumentException("当前导入存在阻断错误，不能确认导入");
        }

        ConflictMode conflictMode = request.conflictMode() == null ? ConflictMode.APPEND : request.conflictMode();

        // P6 导入留痕：谁/什么文件/什么时间（持久化，替代原内存 Map 丢失风险）
        try {
            importBatchJdbcTemplate.update(
                """
                INSERT INTO public.import_batch (
                    batch_id, import_type, file_name, operator_user_id, operator_org_id, target_org_id, cycle_id, total_rows
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                batchId,
                context.type().name(),
                context.response() == null ? null : context.response().fileName(),
                context.currentUserId(),
                context.sourceOrgId(),
                context.targetOrgId(),
                context.cycleId(),
                context.response() == null || context.response().rows() == null ? 0 : context.response().rows().size()
            );
        } catch (Exception e) {
            log.warn("[Import] 写入导入批次留痕失败（不阻断导入）: batchId={}, err={}", batchId, e.getMessage());
        }
        if (conflictMode == ConflictMode.REPLACE_SCOPE) {
            throw new IllegalArgumentException("替换当前表格暂未开放，请先使用更新已有模式");
        }

        // P6 修订（2026-09-17 用户定案）：导入不做权限限制，任何部门的任何人都可使用；
        // 自动通过仍走系统默认账号（resolveSystemAdmin）留痕
        boolean autoSubmitAndApprove = Boolean.TRUE.equals(request.autoSubmitAndApprove());
        String workflowCode = context.type() == ImportType.STRATEGIC_TASK
                ? STRATEGIC_WORKFLOW_CODE
                : DISTRIBUTION_WORKFLOW_CODE;

        Optional<Plan> currentPlan = findPlanForImportContext(context);
        if (currentPlan.map(Plan::isDistributed).orElse(false)) {
            return blockedCommitResponse(
                    batchId,
                    autoSubmitAndApprove,
                    workflowCode,
                    currentPlan.map(Plan::getAuditInstanceId).orElse(null),
                    "当前任务已下发，不能重复导入或下发");
        }

        CommitCounters counters;
        try {
            counters = transactionTemplate.execute(status -> {
                CommitCounters committed = context.type() == ImportType.STRATEGIC_TASK
                        ? commitStrategic(context, conflictMode, currentUser)
                        : commitDistribution(context, conflictMode);
                if (autoSubmitAndApprove) {
                    validateCommittedPlanForAutoDispatch(committed.plan());
                }
                return committed;
            });
        } catch (ImportCommitBlockedException ex) {
            return blockedCommitResponse(
                    batchId,
                    autoSubmitAndApprove,
                    workflowCode,
                    null,
                    ex.getMessage());
        }
        if (counters == null) {
            throw new IllegalStateException("导入事务未返回提交结果");
        }

        ImportWorkflowResult workflow = null;
        if (autoSubmitAndApprove) {
            workflow = autoSubmitAndApprove(
                    counters.plan(),
                    workflowCode,
                    currentUser,
                    counters.plan() == null ? currentUser.getOrgId() : counters.plan().getCreatedByOrgId(),
                    firstNonBlank(request.comment(), "导入后自动下发审批"));
        } else {
            workflow = new ImportWorkflowResult(
                    false,
                    null,
                    null,
                    null,
                    0,
                    null,
                    "导入成功，未自动发起审批");
        }

        previews.remove(batchId);
        String status = resolveCommitStatus(autoSubmitAndApprove, workflow);
        return new ImportCommitResponse(
                batchId,
                status,
                counters.created(),
                counters.updated(),
                counters.skipped(),
                workflow
        );
    }

    private ImportCommitResponse blockedCommitResponse(String batchId,
                                                       boolean autoSubmitAndApprove,
                                                       String workflowCode,
                                                       Long auditInstanceId,
                                                       String message) {
        return new ImportCommitResponse(
                batchId,
                "COMMIT_BLOCKED",
                0,
                0,
                0,
                new ImportWorkflowResult(
                        autoSubmitAndApprove,
                        workflowCode,
                        auditInstanceId,
                        "BLOCKED",
                        0,
                        null,
                        message));
    }

    private void validateCommittedPlanForAutoDispatch(Plan plan) {
        if (plan == null) {
            throw new ImportCommitBlockedException("导入未产生可审批计划");
        }
        try {
            basicTaskWeightValidationService.validatePlanBasicWeight(plan.getId(), plan.getTargetOrgId());
        } catch (IllegalStateException ex) {
            throw new ImportCommitBlockedException(ex.getMessage(), ex);
        }
    }

    private Optional<Plan> findPlanForImportContext(PreviewContext context) {
        PlanLevel planLevel = context.type() == ImportType.STRATEGIC_TASK
                ? PlanLevel.STRAT_TO_FUNC
                : PlanLevel.FUNC_TO_COLLEGE;
        return findPlan(context.cycleId(), planLevel, context.sourceOrgId(), context.targetOrgId());
    }

    private String resolveCommitStatus(Boolean autoSubmitAndApprove, ImportWorkflowResult workflow) {
        if (!Boolean.TRUE.equals(autoSubmitAndApprove) || workflow == null) {
            return "COMMITTED";
        }
        if ("APPROVED".equalsIgnoreCase(workflow.status())) {
            return "COMMITTED";
        }
        if ("FAILED".equalsIgnoreCase(workflow.status())) {
            return "COMMITTED_WITH_WORKFLOW_FAILED";
        }
        return "COMMITTED_WITH_WORKFLOW_PENDING";
    }

    public ImportPreviewResponse getPreview(String batchId, CurrentUser currentUser) {
        PreviewContext context = previews.get(batchId);
        if (context == null) {
            throw new IllegalArgumentException("导入批次不存在或已过期");
        }
        if (!Objects.equals(context.currentUserId(), currentUser.getId())) {
            throw new SecurityException("只能查看自己的导入批次");
        }
        return context.response();
    }

    private ImportPreviewResponse storePreview(MultipartFile file,
                                               ParsedWorkbook parsed,
                                               List<ImportRowPreview> rows,
                                               ImportType type,
                                               Long cycleId,
                                               Long sourceOrgId,
                                               SysOrg targetOrg,
                                               CurrentUser currentUser) {
        ImportSummary summary = summarize(rows);
        boolean blocking = summary.errorRows() > 0;
        String batchId = "IMP-" + UUID.randomUUID();
        ImportPreviewResponse response = new ImportPreviewResponse(
                batchId,
                type,
                file.getOriginalFilename(),
                parsed.sheetName(),
                targetOrg.getId(),
                targetOrg.getName(),
                summary,
                parsed.fieldMappings(),
                rows,
                blocking,
                parsed.confirmToken()
        );
        previews.put(batchId, new PreviewContext(
                batchId,
                type,
                cycleId,
                sourceOrgId,
                targetOrg.getId(),
                currentUser.getId(),
                parsed.confirmToken(),
                response
        ));
        return response;
    }

    private List<ImportRowPreview> enrichStrategicRows(List<ImportRowPreview> rows,
                                                       Long cycleId,
                                                       SysOrg targetOrg,
                                                       CurrentUser currentUser) {
        Plan plan = findPlan(cycleId, PlanLevel.STRAT_TO_FUNC, currentUser.getOrgId(), targetOrg.getId()).orElse(null);
        Map<String, Indicator> existing = existingStrategicIndicators(plan);

        return rows.stream().map(row -> {
            List<String> errors = new ArrayList<>(row.errors());
            List<String> warnings = new ArrayList<>(row.warnings());
            NormalizedImportRow normalized = row.normalized();

            if (!isBlank(normalized.department()) && !sameName(normalized.department(), targetOrg.getName())) {
                errors.add("文件中的职能部门与当前选择不一致");
            }

            // D4（2026-09-18 定案）：「内部ID」列填写时按 ID 精确关联，并做三类校验
            //（① ID 存在 ② 属于导入目标部门 ③ 属于当年考核周期），异常前置拦截
            Long linkedIndicatorId = parseIndicatorId(normalized.indicatorId());
            boolean linkedById = linkedIndicatorId != null;
            if (linkedById) {
                Indicator linked = indicatorRepository.findById(linkedIndicatorId)
                        .filter(indicator -> Boolean.FALSE.equals(indicator.getIsDeleted()))
                        .orElse(null);
                if (linked == null) {
                    errors.add("内部 ID " + linkedIndicatorId + " 不存在（或已被删除），请核对导出表中的内部ID列");
                } else {
                    Long linkedTargetOrgId = linked.getTargetOrg() == null ? null : linked.getTargetOrg().getId();
                    if (!targetOrg.getId().equals(linkedTargetOrgId)) {
                        errors.add("内部 ID " + linkedIndicatorId + " 属于其他部门，不能导入到「"
                                + targetOrg.getName() + "」，请核对导出表中的内部ID列");
                    } else {
                        Long linkedCycleId = linked.getTaskId() == null ? null
                                : taskRepository.findById(linked.getTaskId())
                                        .map(StrategicTask::getCycleId).orElse(null);
                        if (!cycleId.equals(linkedCycleId)) {
                            errors.add("内部 ID " + linkedIndicatorId + " 属于其他考核年度，请重新导出最新模板");
                        }
                    }
                }
                normalized = withIndicatorId(normalized, linkedIndicatorId);
                String businessKey = "id:" + linkedIndicatorId;
                return new ImportRowPreview(
                        row.rowNo(),
                        errors.isEmpty() ? ImportAction.UPDATE : ImportAction.ERROR,
                        businessKey,
                        normalized,
                        row.source(),
                        errors,
                        warnings
                );
            }

            String businessKey = strategicBusinessKey(cycleId, targetOrg.getId(), normalized);
            ImportAction action = errors.isEmpty()
                    ? (existing.containsKey(businessKey) ? ImportAction.UPDATE : ImportAction.CREATE)
                    : ImportAction.ERROR;

            return new ImportRowPreview(
                    row.rowNo(),
                    action,
                    businessKey,
                    normalized,
                    row.source(),
                    errors,
                    warnings
            );
        }).toList();
    }

    private List<ImportRowPreview> enrichDistributionRows(List<ImportRowPreview> rows,
                                                          Long cycleId,
                                                          SysOrg sourceOrg,
                                                          SysOrg targetCollege) {
        Map<String, List<Indicator>> parentByName = indicatorRepository.findByTargetOrgId(sourceOrg.getId()).stream()
                .filter(indicator -> Boolean.FALSE.equals(indicator.getIsDeleted()))
                .collect(Collectors.groupingBy(indicator -> normalizeKey(indicator.getIndicatorDesc())));

        Plan plan = findPlan(cycleId, PlanLevel.FUNC_TO_COLLEGE, sourceOrg.getId(), targetCollege.getId()).orElse(null);
        Map<String, Indicator> existingChildren = existingDistributionIndicators(plan, targetCollege);

        return rows.stream().map(row -> {
            List<String> errors = new ArrayList<>(row.errors());
            List<String> warnings = new ArrayList<>(row.warnings());
            NormalizedImportRow normalized = row.normalized();

            if (!isBlank(normalized.college()) && !sameName(normalized.college(), targetCollege.getName())) {
                errors.add("文件中的学院与当前选择不一致");
            }

            List<Indicator> parentCandidates = parentByName.getOrDefault(normalizeKey(normalized.parentIndicator()), List.of());
            if (parentCandidates.isEmpty()) {
                errors.add("父级核心指标无法匹配");
            } else if (parentCandidates.size() > 1) {
                errors.add("父级核心指标匹配到多条记录，请补充更明确的父级指标信息");
            } else {
                normalized = withParentIndicatorId(normalized, parentCandidates.get(0).getId());
            }

            String businessKey = distributionBusinessKey(cycleId, sourceOrg.getId(), targetCollege.getId(), normalized);
            ImportAction action = errors.isEmpty()
                    ? (existingChildren.containsKey(businessKey) ? ImportAction.UPDATE : ImportAction.CREATE)
                    : ImportAction.ERROR;

            return new ImportRowPreview(
                    row.rowNo(),
                    action,
                    businessKey,
                    normalized,
                    row.source(),
                    errors,
                    warnings
            );
        }).toList();
    }

    private CommitCounters commitStrategic(PreviewContext context,
                                           ConflictMode conflictMode,
                                           CurrentUser currentUser) {
        SysOrg currentOrg = requireOrg(currentUser.getOrgId(), "当前组织不存在");
        SysOrg targetOrg = requireOrg(context.targetOrgId(), "目标职能部门不存在");
        Plan plan = findOrCreatePlan(context.cycleId(), PlanLevel.STRAT_TO_FUNC, currentOrg.getId(), targetOrg.getId());
        CommitCounter counter = new CommitCounter(plan);

        for (ImportRowPreview row : context.response().rows()) {
            if (row.action() == ImportAction.ERROR) {
                counter.skipped++;
                continue;
            }
            NormalizedImportRow normalized = row.normalized();

            // D4（2026-09-18 定案）：带「内部ID」的行按 ID 精确更新（任务归属不变，仅更新指标自身字段）
            if (row.normalized().indicatorId() != null) {
                Long linkedId = Long.valueOf(row.normalized().indicatorId());
                Indicator linked = strategyApplicationService.updateIndicator(
                        linkedId,
                        normalized.indicatorName(),
                        normalized.weight(),
                        null,
                        null,
                        normalized.remark(),
                        null,
                        currentOrg,
                        targetOrg);
                counter.updated++;
                activateIndicatorIfPlanDistributed(plan, linked);
                continue;
            }

            StrategicTask task = findOrCreateTask(
                    plan,
                    context.cycleId(),
                    normalized.strategicTask(),
                    toTaskType(normalized.taskType()),
                    targetOrg,
                    currentOrg);

            Indicator existing = findIndicatorByTaskAndName(task.getId(), normalized.indicatorName(), null).orElse(null);
            Indicator indicator;
            if (existing == null || conflictMode == ConflictMode.APPEND) {
                indicator = strategyApplicationService.createIndicator(
                        normalized.indicatorName(),
                        currentOrg,
                        targetOrg,
                        task.getId(),
                        null,
                        normalized.indicatorType(),
                        defaultWeight(normalized.weight()),
                        null,
                        normalized.remark(),
                        0);
                counter.created++;
            } else {
                indicator = strategyApplicationService.updateIndicator(
                        existing.getId(),
                        normalized.indicatorName(),
                        normalized.weight(),
                        null,
                        null,
                        normalized.remark(),
                        task.getId(),
                        currentOrg,
                        targetOrg);
                counter.updated++;
            }
            activateIndicatorIfPlanDistributed(plan, indicator);
        }
        return counter.toCounters();
    }

    private CommitCounters commitDistribution(PreviewContext context,
                                             ConflictMode conflictMode) {
        SysOrg currentOrg = requireOrg(context.sourceOrgId(), "来源职能部门不存在");
        SysOrg targetCollege = requireOrg(context.targetOrgId(), "目标学院不存在");
        Plan plan = findOrCreatePlan(context.cycleId(), PlanLevel.FUNC_TO_COLLEGE, currentOrg.getId(), targetCollege.getId());
        CommitCounter counter = new CommitCounter(plan);

        for (ImportRowPreview row : context.response().rows()) {
            if (row.action() == ImportAction.ERROR || row.normalized().parentIndicatorId() == null) {
                counter.skipped++;
                continue;
            }
            NormalizedImportRow normalized = row.normalized();
            Indicator parent = indicatorRepository.findById(normalized.parentIndicatorId())
                    .orElseThrow(() -> new IllegalArgumentException("父级指标不存在: " + normalized.parentIndicatorId()));
            // P6 ID 三类校验（会议定案：异常难追踪，必须前置拦截）
            Long parentTargetOrgId = parent.getTargetOrg() == null ? null : parent.getTargetOrg().getId();
            if (parentTargetOrgId == null || !parentTargetOrgId.equals(currentOrg.getId())) {
                throw new IllegalArgumentException(
                        "父级指标 ID " + normalized.parentIndicatorId() + " 不属于本部门（" + currentOrg.getName() + "），请核对导出表中的内部 ID");
            }
            StrategicTask parentTask = parent.getTaskId() == null
                    ? null
                    : taskRepository.findById(parent.getTaskId()).orElse(null);
            if (parentTask != null && parentTask.getCycleId() != null
                    && !parentTask.getCycleId().equals(context.cycleId())) {
                throw new IllegalArgumentException(
                        "父级指标 ID " + normalized.parentIndicatorId() + " 属于其他考核年度，不能跨年度关联，请重新导出最新模板");
            }
            String taskName = firstNonBlank(normalized.parentStrategicTask(), parentTask == null ? null : parentTask.getName(), "学院子指标");
            TaskType taskType = parentTask == null ? TaskType.DEVELOPMENT : parentTask.getTaskType();
            StrategicTask task = findOrCreateTask(plan, context.cycleId(), taskName, taskType, targetCollege, currentOrg);

            Indicator existing = findIndicatorByTaskAndName(task.getId(), normalized.indicatorName(), normalized.parentIndicatorId()).orElse(null);
            Indicator indicator;
            if (existing == null || conflictMode == ConflictMode.APPEND) {
                indicator = strategyApplicationService.createIndicator(
                        normalized.indicatorName(),
                        currentOrg,
                        targetCollege,
                        task.getId(),
                        normalized.parentIndicatorId(),
                        normalized.indicatorType(),
                        defaultWeight(normalized.weight()),
                        null,
                        normalized.remark(),
                        0);
                counter.created++;
            } else {
                indicator = strategyApplicationService.updateIndicator(
                        existing.getId(),
                        normalized.indicatorName(),
                        normalized.weight(),
                        null,
                        null,
                        normalized.remark(),
                        task.getId(),
                        currentOrg,
                        targetCollege);
                counter.updated++;
            }
            activateIndicatorIfPlanDistributed(plan, indicator);
        }
        return counter.toCounters();
    }

    private ImportWorkflowResult autoSubmitAndApprove(Plan plan,
                                                      String workflowCode,
                                                      CurrentUser currentUser,
                                                      Long requesterOrgId,
                                                      String comment) {
        if (plan == null) {
            return new ImportWorkflowResult(true, workflowCode, null, "FAILED", 0, null, "导入未产生可审批计划");
        }
        String originalPlanStatus = plan.getStatus();
        Long originalAuditInstanceId = plan.getAuditInstanceId();
        AuditInstance startedInstance = null;
        try {
            basicTaskWeightValidationService.validatePlanBasicWeight(plan.getId(), plan.getTargetOrgId());
            if (plan.isDistributed()) {
                return new ImportWorkflowResult(true, workflowCode, plan.getAuditInstanceId(), "FAILED", 0, null, "当前计划已下发，不能重复发起自动审批");
            }

            User systemAdmin = resolveSystemAdmin();
            AuditFlowDef flowDef = workflowApplicationService.getAuditFlowDefByCode(workflowCode);
            if (flowDef == null || !Boolean.TRUE.equals(flowDef.getIsActive())) {
                return new ImportWorkflowResult(true, workflowCode, null, "FAILED", 0, null, "自动审批流程未启用");
            }

            if (!plan.isDistributed()) {
                plan.submitForApproval(true);
                planRepository.save(plan);
            }

            AuditInstance instance = new AuditInstance();
            instance.setFlowDefId(flowDef.getId());
            instance.setEntityType("PLAN");
            instance.setEntityId(plan.getId());
            AuditInstance current = workflowApplicationService.startAuditInstance(
                    instance,
                    currentUser.getId(),
                    requesterOrgId,
                    comment);
            startedInstance = current;

            int approvedSteps = 0;
            for (int attempt = 0; attempt < 20 && AuditInstance.STATUS_PENDING.equals(current.getStatus()); attempt++) {
                Optional<AuditStepInstance> pending = current.resolveCurrentPendingStep();
                if (pending.isEmpty()) {
                    break;
                }
                current = workflowApplicationService.approveAuditInstance(
                        current,
                        systemAdmin.getId(),
                        "系统自动审批通过：来源于导入批次，系统管理员统一处理，确认人 " + currentUser.getUsername());
                approvedSteps++;
            }

            return new ImportWorkflowResult(
                    true,
                    workflowCode,
                    current.getId(),
                    current.getStatus(),
                    approvedSteps,
                    AuditInstance.STATUS_PENDING.equals(current.getStatus())
                            ? current.resolveCurrentPendingStep().map(AuditStepInstance::getStepName).orElse(null)
                            : null,
                    AuditInstance.STATUS_APPROVED.equals(current.getStatus())
                            ? "自动下发审批已完成"
                            : "自动审批未全部完成，请进入审批中心处理");
        } catch (Exception ex) {
            removeFailedAutoWorkflowInstance(startedInstance);
            restorePlanAfterAutoWorkflowFailure(plan, originalPlanStatus, originalAuditInstanceId);
            return new ImportWorkflowResult(true, workflowCode, null, "FAILED", 0, null, ex.getMessage());
        }
    }

    private void removeFailedAutoWorkflowInstance(AuditInstance startedInstance) {
        if (startedInstance == null || startedInstance.getId() == null) {
            return;
        }
        auditInstanceRepository.findById(startedInstance.getId())
                .ifPresent(auditInstanceRepository::delete);
    }

    private void restorePlanAfterAutoWorkflowFailure(Plan plan, String originalStatus, Long originalAuditInstanceId) {
        if (plan == null || plan.isDistributed()) {
            return;
        }
        plan.setStatus(originalStatus);
        plan.setAuditInstanceId(originalAuditInstanceId);
        planRepository.save(plan);
    }

    private User resolveSystemAdmin() {
        User systemAdmin = userRepository.findByUsername(SYSTEM_ADMIN_USERNAME)
                .orElseThrow(() -> new IllegalStateException("系统管理员账号不存在，无法自动审批"));
        if (!Boolean.TRUE.equals(systemAdmin.getIsActive())) {
            throw new IllegalStateException("系统管理员账号已停用，无法自动审批");
        }
        boolean hasSystemAdminRole = systemAdmin.getRoles() != null
                && systemAdmin.getRoles().stream()
                .anyMatch(role -> SYSTEM_ADMIN_ROLE_CODE.equals(role.getRoleCode()));
        if (!hasSystemAdminRole) {
            throw new IllegalStateException("系统管理员账号未配置系统管理员角色，无法自动审批");
        }
        return systemAdmin;
    }

    private boolean isSystemAdmin(CurrentUser currentUser) {
        return currentUser != null
                && currentUser.getAuthorities() != null
                && currentUser.getAuthorities().stream()
                .anyMatch(authority -> SYSTEM_ADMIN_ROLE_CODE.equals(authority.getAuthority()));
    }

    private void activateIndicatorIfPlanDistributed(Plan plan, Indicator indicator) {
        if (plan == null || indicator == null || indicator.getId() == null || !plan.isDistributed()) {
            return;
        }
        indicator.activate();
        indicatorRepository.save(indicator);
    }

    private Plan findOrCreatePlan(Long cycleId, PlanLevel planLevel, Long createdByOrgId, Long targetOrgId) {
        return findPlan(cycleId, planLevel, createdByOrgId, targetOrgId)
                .orElseGet(() -> planRepository.save(Plan.create(cycleId, targetOrgId, createdByOrgId, planLevel)));
    }

    private Optional<Plan> findPlan(Long cycleId, PlanLevel planLevel, Long createdByOrgId, Long targetOrgId) {
        return planRepository.findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                cycleId,
                planLevel,
                createdByOrgId,
                targetOrgId);
    }

    private StrategicTask findOrCreateTask(Plan plan,
                                           Long cycleId,
                                           String taskName,
                                           TaskType taskType,
                                           SysOrg targetOrg,
                                           SysOrg createdByOrg) {
        return taskRepository.findByPlanIdAndCycleId(plan.getId(), cycleId).stream()
                .filter(task -> normalizeKey(task.getName()).equals(normalizeKey(taskName)))
                .filter(task -> task.getTaskType() == taskType)
                .findFirst()
                .orElseGet(() -> {
                    StrategicTask task = StrategicTask.create(taskName, taskType, plan.getId(), cycleId, targetOrg, createdByOrg);
                    return taskRepository.save(task);
                });
    }

    private Optional<Indicator> findIndicatorByTaskAndName(Long taskId, String indicatorName, Long parentIndicatorId) {
        return indicatorRepository.findByTaskId(taskId).stream()
                .filter(indicator -> Boolean.FALSE.equals(indicator.getIsDeleted()))
                .filter(indicator -> normalizeKey(indicator.getIndicatorDesc()).equals(normalizeKey(indicatorName)))
                .filter(indicator -> Objects.equals(indicator.getParentIndicatorId(), parentIndicatorId))
                .findFirst();
    }

    private Map<String, Indicator> existingStrategicIndicators(Plan plan) {
        if (plan == null) {
            return Map.of();
        }
        List<StrategicTask> tasks = taskRepository.findByPlanId(plan.getId());
        Map<Long, StrategicTask> taskById = tasks.stream()
                .filter(task -> task.getId() != null)
                .collect(Collectors.toMap(StrategicTask::getId, Function.identity(), (left, right) -> left));
        if (taskById.isEmpty()) {
            return Map.of();
        }
        return indicatorRepository.findByTaskIds(new ArrayList<>(taskById.keySet())).stream()
                .filter(indicator -> indicator.getTaskId() != null)
                .filter(indicator -> indicator.getParentIndicatorId() == null)
                .filter(indicator -> Boolean.FALSE.equals(indicator.getIsDeleted()))
                .collect(Collectors.toMap(
                        indicator -> {
                            StrategicTask task = taskById.get(indicator.getTaskId());
                            return strategicBusinessKey(
                                    plan.getCycleId(),
                                    plan.getTargetOrgId(),
                                    new NormalizedImportRow(
                                            null,
                                            null,
                                            task == null ? "" : taskTypeLabel(task.getTaskType()),
                                            task == null ? "" : task.getName(),
                                            null,
                                            null,
                                            indicator.getIndicatorDesc(),
                                            indicator.getType(),
                                            indicator.getWeightPercent(),
                                            indicator.getRemark(),
                                            null,
                                            null));
                        },
                        Function.identity(),
                        (left, right) -> left
                ));
    }

    private Map<String, Indicator> existingDistributionIndicators(Plan plan, SysOrg targetCollege) {
        if (plan == null) {
            return Map.of();
        }
        List<StrategicTask> tasks = taskRepository.findByPlanId(plan.getId());
        if (tasks.isEmpty()) {
            return Map.of();
        }
        List<Long> taskIds = tasks.stream().map(StrategicTask::getId).filter(Objects::nonNull).toList();
        return indicatorRepository.findByTaskIds(taskIds).stream()
                .filter(indicator -> indicator.getParentIndicatorId() != null)
                .filter(indicator -> indicator.getTargetOrg() != null && Objects.equals(indicator.getTargetOrg().getId(), targetCollege.getId()))
                .filter(indicator -> Boolean.FALSE.equals(indicator.getIsDeleted()))
                .collect(Collectors.toMap(
                        indicator -> distributionBusinessKey(
                                plan.getCycleId(),
                                plan.getCreatedByOrgId(),
                                targetCollege.getId(),
                                new NormalizedImportRow(
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        indicator.getIndicatorDesc(),
                                        indicator.getType(),
                                        indicator.getWeightPercent(),
                                        indicator.getRemark(),
                                        indicator.getParentIndicatorId(),
                                        null)),
                        Function.identity(),
                        (left, right) -> left
                ));
    }


    private ImportSummary summarize(List<ImportRowPreview> rows) {
        int createRows = 0;
        int updateRows = 0;
        int skipRows = 0;
        int errorRows = 0;
        int warningRows = 0;
        for (ImportRowPreview row : rows) {
            if (row.action() == ImportAction.CREATE) {
                createRows++;
            } else if (row.action() == ImportAction.UPDATE) {
                updateRows++;
            } else if (row.action() == ImportAction.SKIP) {
                skipRows++;
            } else if (row.action() == ImportAction.ERROR) {
                errorRows++;
            }
            if (row.hasWarnings()) {
                warningRows++;
            }
        }
        return new ImportSummary(
                rows.size(),
                rows.size() - errorRows,
                createRows,
                updateRows,
                skipRows,
                errorRows,
                warningRows);
    }

    private SysOrg requireOrg(Long orgId, String message) {
        return organizationRepository.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException(message));
    }

    private void requireCycleAndUser(Long cycleId, CurrentUser currentUser) {
        if (currentUser == null || currentUser.getId() == null || currentUser.getOrgId() == null) {
            throw new SecurityException("未登录或登录已过期");
        }
        if (cycleId == null) {
            throw new IllegalArgumentException("考核周期不能为空");
        }
    }

    private String strategicBusinessKey(Long cycleId, Long targetOrgId, NormalizedImportRow row) {
        return String.join(":",
                String.valueOf(cycleId),
                String.valueOf(targetOrgId),
                normalizeKey(row.taskType()),
                normalizeKey(row.strategicTask()),
                normalizeKey(row.indicatorName()));
    }

    private String distributionBusinessKey(Long cycleId, Long ownerOrgId, Long targetOrgId, NormalizedImportRow row) {
        return String.join(":",
                String.valueOf(cycleId),
                String.valueOf(ownerOrgId),
                String.valueOf(targetOrgId),
                String.valueOf(row.parentIndicatorId()),
                normalizeKey(row.indicatorName()));
    }

    private NormalizedImportRow withParentIndicatorId(NormalizedImportRow row, Long parentIndicatorId) {
        return new NormalizedImportRow(
                row.department(),
                row.college(),
                row.taskType(),
                row.strategicTask(),
                row.parentStrategicTask(),
                row.parentIndicator(),
                row.indicatorName(),
                row.indicatorType(),
                row.weight(),
                row.remark(),
                parentIndicatorId,
                row.indicatorId());
    }

    /** D4：「内部ID」原始串转 Long，空白/非法返回 null（合法性已在预览阶段校验）。 */
    private Long parseIndicatorId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private NormalizedImportRow withIndicatorId(NormalizedImportRow row, Long indicatorId) {
        return new NormalizedImportRow(
                row.department(),
                row.college(),
                row.taskType(),
                row.strategicTask(),
                row.parentStrategicTask(),
                row.parentIndicator(),
                row.indicatorName(),
                row.indicatorType(),
                row.weight(),
                row.remark(),
                row.parentIndicatorId(),
                indicatorId == null ? null : String.valueOf(indicatorId));
    }

    private TaskType toTaskType(String value) {
        return value != null && value.contains("基础") ? TaskType.BASIC : TaskType.DEVELOPMENT;
    }

    private String taskTypeLabel(TaskType taskType) {
        return taskType == TaskType.BASIC ? "基础性" : "发展性";
    }

    private BigDecimal defaultWeight(BigDecimal weight) {
        return weight == null ? BigDecimal.valueOf(100) : weight;
    }

    private boolean sameName(String left, String right) {
        return normalizeKey(left).equals(normalizeKey(right));
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record PreviewContext(
            String batchId,
            ImportType type,
            Long cycleId,
            Long sourceOrgId,
            Long targetOrgId,
            Long currentUserId,
            String confirmToken,
            ImportPreviewResponse response
    ) {
    }

    private record CommitCounters(
            int created,
            int updated,
            int skipped,
            Plan plan
    ) {
    }

    private static class CommitCounter {
        private int created;
        private int updated;
        private int skipped;
        private final Plan plan;

        private CommitCounter(Plan plan) {
            this.plan = plan;
        }

        private CommitCounters toCounters() {
            return new CommitCounters(created, updated, skipped, plan);
        }
    }

    private static class ImportCommitBlockedException extends RuntimeException {
        private ImportCommitBlockedException(String message) {
            super(message);
        }

        private ImportCommitBlockedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
