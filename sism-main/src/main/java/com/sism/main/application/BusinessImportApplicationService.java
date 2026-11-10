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
import com.sism.strategy.application.CollegeDispatchGuardService;
import com.sism.strategy.application.StrategyApplicationService;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.indicator.IndicatorStatus;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import com.sism.strategy.domain.repository.IndicatorRepository;
import com.sism.strategy.domain.repository.PlanRepository;
import com.sism.strategy.infrastructure.StrategyOrgProperties;
import com.sism.task.domain.repository.TaskRepository;
import com.sism.task.domain.task.StrategicTask;
import com.sism.task.domain.task.TaskType;
import com.sism.workflow.application.BusinessWorkflowApplicationService;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    /** 2026-11-05：战略发展部 org id 兜底值（配置缺失时用 35，与 sism.strategy.org.strategy-org-id 默认一致） */
    private static final Long DEFAULT_STRATEGY_ORG_ID = 35L;
    private static final String STRATEGIC_WORKFLOW_CODE = "PLAN_DISPATCH_STRATEGY";
    /**
     * 2026-11-05（学院指标直发改造）：学院子指标下发复用「战略部下发」审批流程（flow 1）。
     * 原 {@code PLAN_DISPATCH_FUNCDEPT}（职能部门 → 学院）整条链路已取消，流程定义停用
     * （见 V101 迁移），不再新增流程定义行。
     */
    private static final String DISTRIBUTION_WORKFLOW_CODE = "PLAN_DISPATCH_STRATEGY";

    private final ExcelBusinessImportParser parser;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final PlanRepository planRepository;
    private final TaskRepository taskRepository;
    private final IndicatorRepository indicatorRepository;
    private final StrategyApplicationService strategyApplicationService;
    private final BasicTaskWeightValidationService basicTaskWeightValidationService;
    private final WorkflowApplicationService workflowApplicationService;
    /** 2026-10-19：自动审批复用普通审批的节点通知（提交人推进 + 下一环节待审批 + 终态结果） */
    private final BusinessWorkflowApplicationService businessWorkflowApplicationService;
    private final AuditInstanceRepository auditInstanceRepository;
    private final TransactionTemplate transactionTemplate;
    private final org.springframework.jdbc.core.JdbcTemplate importBatchJdbcTemplate;
    /**
     * 2026-11-05（学院指标直发改造）：战略发展部 org id。
     * 学院子指标下发的来源组织由此唯一确定（原来由前端传 sourceOrgId，职能部门也能传自己部门）。
     */
    private final StrategyOrgProperties strategyOrgProperties;
    /**
     * 2026-11-05（学院指标直发改造）：学院下发的「异动在途」守卫。
     * 显式口径（父级核心指标的目标部门），不依赖 created_by_org_id 的语义。
     */
    private final CollegeDispatchGuardService collegeDispatchGuardService;
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
        // 2026-11-05（学院指标直发改造）：学院子指标的下发方唯一确定为战略发展部。
        // 校验顺序必须是「先来源组织合法性 → 再操作者组织归属」。
        // 2026-11-05（minor）：sourceOrgId 缺省时回落到战略发展部，而不是当前用户所在组织——
        // 否则系统管理员（不一定在战略部）等合法代操作账号会在「来源部门合法性」这一步就被误拒，
        // 后面的 isSystemAdmin 豁免形同虚设。
        Long effectiveSourceOrgId = sourceOrgId == null ? strategyOrgId() : sourceOrgId;
        SysOrg sourceOrg = requireOrg(effectiveSourceOrgId, "来源部门不存在");
        if (!isStrategyOrg(sourceOrg)) {
            throw new IllegalArgumentException(
                    "学院指标下发已统一由战略发展部发起，职能部门不再向学院分发指标；来源部门必须是战略发展部");
        }
        if (!Objects.equals(currentUser.getOrgId(), strategyOrgId()) && !isSystemAdmin(currentUser)) {
            // 2026-11-05（minor）：越权（操作者不属于战略发展部）恢复 403 语义——
            // 原实现是 SecurityException(403)，改造时被降级成 IllegalArgumentException(400)；
            // 「来源部门不是战略部」是业务规则（400），「操作者没权限」是越权（403），两者要分开。
            throw new SecurityException(
                    "学院指标下发已统一由战略发展部发起，职能部门不再向学院分发指标；当前账号不属于战略发展部");
        }
        SysOrg targetOrg = requireOrg(targetCollegeOrgId, "目标学院不存在");
        if (targetOrg.getType() != OrgType.academic) {
            throw new IllegalArgumentException("指标下发导入目标必须是学院");
        }

        // 2026-11-05（R10）：本周期该学院若已存在「职能部门创建的」存量下发计划，
        // 必须直接拒绝 —— 需求 4 定的是「存量保留为只读历史、禁止再次下发」。
        //
        // 为什么不等到 commit 阶段再拦：findPlanForImportContext 对学院导入固定用
        // 战略部 org 去查计划（「已下发不可再导入」的判定键），存量计划的 created_by 是职能部门，
        // 永远查不中 → 该拦截对存量完全失效；enrichDistributionRows 的「同名即更新」也只在
        // 新计划下匹配，存量同名子指标会被当成新指标**再创建一条生效的**，
        // 结果同一学院同一周期出现两套生效的学院子指标（口径分裂、权重合计失真）。
        // 所以在预览入口就挡住，并明确提示历史来源。
        //
        // 2026-11-10（用户拍板收窄）：仅当 legacy 计划下存在「未删除的子指标」时才拦——
        // 核心原则：职能部门没有收到「已下发」的核心指标，就不存在任何学院指标下发的资格；
        // 空壳 legacy 计划 = 当年就没下发过任何东西，放行，由战略部 (35→学院) 新计划自然接管
        // （空壳无同名冲突风险，逻辑安全）；有未删子指标的 legacy 计划维持拦截（防同名重复创建）。
        Plan legacyPlan = findLegacyDistributionPlan(cycleId, targetOrg.getId());
        if (legacyPlan != null) {
            throw new IllegalArgumentException(
                    "该学院本周期已存在历史下发计划（来源：职能部门），按只读历史保留，不能再通过导入下发；"
                            + "如需调整请走异动审批");
        }

        ParsedWorkbook parsed = parser.parse(file, ImportType.DISTRIBUTION, sheetName);
        List<ImportRowPreview> rows = enrichDistributionRows(parsed.rows(), cycleId, sourceOrg, targetOrg);
        // 2026-11-05：来源组织一律记为战略发展部，避免批次上下文里残留职能部门来源
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
                    // 2026-10-20 用户定案：文案明确原因与替代路径，避免用户反复尝试导入
                    "该部门计划已下发，不能再通过导入修改。如需新增或调整指标，"
                            + "请在表格中双击指标编辑或点「新增行」，改完后点击顶部「发起异动」提交审批");
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
                counters.deleted(),
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

    /**
     * 查找本周期该学院的<b>存量</b>学院下发计划（创建方不是战略发展部的那条链路）。
     *
     * 2026-11-05（R10）：改造前学院下发计划的创建方是职能部门，改造后固定为战略发展部。
     * 存量计划仍留在库里（需求 4：保留为只读历史），但它与新链路并存会让
     * 「已下发不可再导入」的判定失效、同名子指标被重复创建。
     * 故在导入预览入口显式检出并拒绝（见 previewDistribution）。
     *
     * <p>2026-11-10（用户拍板收窄）：只拦「真的下发过东西」的 legacy 计划——
     * 计划下存在未删除的子指标才算数；空壳计划（没有任何存活指标）视为当年没下发过，
     * 放行由战略部新计划接管（见 {@link #legacyPlanHasLiveIndicators}）。
     *
     * @return 存量计划；不存在或为空壳则返回 null
     */
    private Plan findLegacyDistributionPlan(Long cycleId, Long targetCollegeOrgId) {
        if (cycleId == null || targetCollegeOrgId == null) {
            return null;
        }
        Long strategyOrgId = strategyOrgId();
        return planRepository.findByCycleId(cycleId).stream()
                .filter(plan -> plan.getPlanLevel() == PlanLevel.FUNC_TO_COLLEGE)
                .filter(plan -> targetCollegeOrgId.equals(plan.getTargetOrgId()))
                .filter(plan -> !Objects.equals(plan.getCreatedByOrgId(), strategyOrgId))
                .filter(this::legacyPlanHasLiveIndicators)
                .findFirst()
                .orElse(null);
    }

    /**
     * 2026-11-10（用户拍板口径）：legacy 计划是否真的下发过东西。
     *
     * <p>判定链：计划经 sys_task（task_id → plan_id）关联的任务，再经 indicator.task_id 关联的
     * 指标中存在<b>未删除</b>的记录 → 该计划当年确实下发过子指标，维持拦截（防同名重复创建）。
     * 没有任务、或任务下指标全被软删 → 空壳，当年没下发过任何东西，放行。
     */
    private boolean legacyPlanHasLiveIndicators(Plan plan) {
        if (plan == null || plan.getId() == null) {
            return false;
        }
        List<Long> taskIds = taskRepository.findByPlanId(plan.getId()).stream()
                .map(StrategicTask::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (taskIds.isEmpty()) {
            return false;
        }
        return indicatorRepository.findByTaskIds(taskIds).stream()
                .anyMatch(indicator -> !Boolean.TRUE.equals(indicator.getIsDeleted()));
    }

    private Optional<Plan> findPlanForImportContext(PreviewContext context) {
        PlanLevel planLevel = context.type() == ImportType.STRATEGIC_TASK
                ? PlanLevel.STRAT_TO_FUNC
                : PlanLevel.FUNC_TO_COLLEGE;
        // 2026-11-05（学院指标直发改造）：学院下发计划的创建组织固定为战略发展部，
        // 不再取批次上下文里的 sourceOrgId——历史批次里可能残留职能部门来源，
        // 会让「已下发不可再导入」的判定落到另一条计划上。
        Long createdByOrgId = context.type() == ImportType.STRATEGIC_TASK
                ? context.sourceOrgId()
                : strategyOrgId();
        return findPlan(context.cycleId(), planLevel, createdByOrgId, context.targetOrgId());
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
        Plan plan = findPlan(cycleId, PlanLevel.STRAT_TO_FUNC, currentUser.getOrgId(), targetOrg.getId())
                .orElse(null);
        // 2026-10-31 层级化定案：按名称匹配更新/新增——同任务组内核心指标同名=更新、新名称=新增；
        // 旧「序号→内部ID 行身份精确更新」逻辑移除，序号仅作为任务组号参与分组与排序。
        Map<String, Indicator> existing = existingStrategicIndicators(plan);

        return rows.stream().map(row -> {
            List<String> errors = new ArrayList<>(row.errors());
            List<String> warnings = new ArrayList<>(row.warnings());
            NormalizedImportRow normalized = row.normalized();

            if (!isBlank(normalized.department()) && !sameName(normalized.department(), targetOrg.getName())) {
                errors.add("文件中的职能部门与当前选择不一致");
            }

            String businessKey = strategicBusinessKey(cycleId, targetOrg.getId(), normalized);
            ImportAction action = existing.containsKey(businessKey) ? ImportAction.UPDATE : ImportAction.CREATE;
            if (!errors.isEmpty()) {
                action = ImportAction.ERROR;
            }

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
        // 2026-10-31 层级化定案：父级核心指标按名称反查（父指标是已下发系统数据，文件不再携带父级战略任务列）；
        // 子指标按（父级核心指标 + 名称）匹配——同名=更新、新名称=新增。
        // 2026-10-31 用户拍板（父级行=上级定死锚点）：系统父级锚点按 ID 升序作为「下发顺序」基准
        // （与模板预填顺序一致），用于锚点一致性硬校验、相对顺序校验与未使用预填行识别。
        // 2026-11-05（学院指标直发改造）：父级核心指标锚点的来源口径变了。
        // 原口径 findByTargetOrgId(sourceOrgId) =「下发给来源部门的指标」——改造前来源部门是职能部门，
        // 它收到的正是战略部下发的核心指标，所以按 target 查刚好命中；改造后来源部门变成战略部自己，
        // 再按 target 查会捞到战略部名下全部指标（含学院子指标），口径不再成立。
        //
        // 2026-11-05（BL3）：口径进一步收紧为「同一考核周期 + 目标组织是职能部门 + 无父级 + 未删除」。
        // 只按「owner = 战略部 且无父级」圈定会同时踩三个坑：
        //   (a) 捞到历年核心指标——指标表本身不带 cycle，必须经 sys_task → plan → cycle_id 收敛，
        //       否则跨年同名必然错挂父级；
        //   (b) 把「战略部 → 学院且无父级」的根指标也当成父级（那不是下发锚点）；
        //   (c) 顺序校验基线变成全局 ID 序（含历年），合法文件会被判成乱序而阻断。
        List<Indicator> systemParentAnchors = indicatorRepository
                .findCoreDispatchAnchors(strategyOrgId(), cycleId)
                .stream()
                .filter(indicator -> Boolean.FALSE.equals(indicator.getIsDeleted()))
                .filter(indicator -> indicator.getId() != null)
                .filter(indicator -> indicator.getParentIndicatorId() == null)
                .sorted(Comparator.comparing(Indicator::getId))
                .toList();
        Map<String, List<Indicator>> parentByName = systemParentAnchors.stream()
                .collect(Collectors.groupingBy(indicator -> normalizeKey(indicator.getIndicatorDesc())));
        // 2026-11-10（用户拍板）：锚点集合只含「已下发」核心指标后，匹配失败可能意味着
        // 「同名核心指标存在但尚未下发」。取同一周期内全部核心指标（不滤状态）建「未下发」
        // 名册，用于行级提示区分「系统里没有」与「存在但没下发」，给出可执行的引导文案。
        Map<String, List<Indicator>> undistributedCoreByName = indicatorRepository
                .findCoreIndicatorsInCycle(strategyOrgId(), cycleId)
                .stream()
                .filter(indicator -> indicator.getStatus() != IndicatorStatus.DISTRIBUTED)
                .collect(Collectors.groupingBy(indicator -> normalizeKey(indicator.getIndicatorDesc())));

        Plan plan = findPlan(cycleId, PlanLevel.FUNC_TO_COLLEGE, sourceOrg.getId(), targetCollege.getId()).orElse(null);
        Map<String, Indicator> existingChildren = existingDistributionIndicators(plan, targetCollege);

        // 第一遍：逐行做学院一致性、父级锚点匹配与锚点一致性硬校验（名称/类型被改=阻断）
        List<DistributionRowAnchor> anchored = new ArrayList<>();
        for (ImportRowPreview row : rows) {
            anchored.add(applyDistributionAnchor(row, targetCollege, parentByName, undistributedCoreByName));
        }
        // 第二遍：相对顺序校验——文件中出现的父级组序列必须是系统下发顺序的子序列
        // （允许删掉不填的组，不允许调换/乱序；违反=阻断）
        applyParentOrderValidation(anchored, systemParentAnchors);

        // 第三遍：组装预览行（错误优先阻断；未使用预填行置 SKIP；其余按名称匹配更新/新增）
        return anchored.stream().map(anchor -> {
            NormalizedImportRow normalized = anchor.normalized();
            String businessKey = distributionBusinessKey(cycleId, sourceOrg.getId(), targetCollege.getId(), normalized);
            ImportAction action;
            if (!anchor.errors().isEmpty()) {
                action = ImportAction.ERROR;
            } else if (anchor.unusedParentRow()) {
                // 2026-10-31 用户拍板：父级行未填任何子指标且父级内容未被改动 → 跳过不报错
                action = ImportAction.SKIP;
            } else {
                action = existingChildren.containsKey(businessKey) ? ImportAction.UPDATE : ImportAction.CREATE;
            }
            return new ImportRowPreview(
                    anchor.row().rowNo(),
                    action,
                    businessKey,
                    normalized,
                    anchor.row().source(),
                    anchor.errors(),
                    anchor.warnings()
            );
        }).toList();
    }

    /**
     * 学院导入 enrich 第一遍的逐行中间结果：锚点校验完成、顺序校验与动作判定之前。
     */
    private record DistributionRowAnchor(
            ImportRowPreview row,
            List<String> errors,
            List<String> warnings,
            NormalizedImportRow normalized,
            Indicator matchedParent,
            boolean unusedParentRow
    ) {
    }

    /** 学院导入逐行锚点校验：学院一致性 + 父级匹配 + 名称/类型锚点硬校验（2026-10-31 用户拍板）。 */
    private DistributionRowAnchor applyDistributionAnchor(ImportRowPreview row,
                                                          SysOrg targetCollege,
                                                          Map<String, List<Indicator>> parentByName,
                                                          Map<String, List<Indicator>> undistributedCoreByName) {
        List<String> errors = new ArrayList<>(row.errors());
        List<String> warnings = new ArrayList<>(row.warnings());
        NormalizedImportRow normalized = row.normalized();

        if (!isBlank(normalized.college()) && !sameName(normalized.college(), targetCollege.getName())) {
            errors.add("文件中的学院与当前选择不一致");
        }

        List<Indicator> parentCandidates = parentByName.getOrDefault(normalizeKey(normalized.parentIndicator()), List.of());
        Indicator matchedParent = null;
        boolean unusedParentRow = isUnusedParentRow(normalized);
        if (parentCandidates.isEmpty()) {
            if (isBlank(normalized.parentIndicator())) {
                errors.add("父级核心指标无法匹配");
            } else if (!undistributedCoreByName
                    .getOrDefault(normalizeKey(normalized.parentIndicator()), List.of()).isEmpty()) {
                // 2026-11-10（用户拍板）：同名核心指标在系统里存在（同周期、目标职能部门、未删）
                // 但状态非「已下发」→ 职能部门那一级还没下发完成，给出业务化引导而不是笼统的
                // 「与上级下发内容不一致」——用户该去做的是催下发，不是改表格。
                errors.add("父级核心指标「" + normalized.parentIndicator().trim()
                        + "」尚未下发，请先完成职能部门核心指标下发后再导入学院指标");
            } else {
                // 2026-10-31 用户拍板：父级核心指标名称被改 = 锚点不一致，阻断错误并点名行序与内容
                errors.add("第 " + row.rowNo() + " 行父级核心指标与上级下发内容不一致（不可修改）：文件中填写为“"
                        + normalized.parentIndicator().trim() + "”，在上级下发的父级核心指标中不存在");
            }
        } else {
            // 2026-11-05（BL3）：同名多命中恢复硬阻断并点名。
            // 原口径取 ID 最小的一条继续处理——ID 最小 = 最早年度那条，跨年同名会静默挂错父级，
            // 而且挂错的父级还要继续参与顺序校验/落库校验，错得没边。锚点按周期收敛后，
            // 仍多义说明同一考核周期内确实存在两条同名核心指标（数据异常），必须让人来处理。
            if (parentCandidates.size() > 1) {
                String candidateIds = parentCandidates.stream()
                        .filter(candidate -> candidate.getId() != null)
                        .map(candidate -> String.valueOf(candidate.getId()))
                        .collect(Collectors.joining("、"));
                errors.add("第 " + row.rowNo() + " 行父级核心指标“" + normalized.parentIndicator().trim()
                        + "”在当前考核年度匹配到多条同名记录（ID=" + candidateIds
                        + "），无法确定唯一父级；请先在系统中修正同名指标或改用唯一名称后重新导入");
            } else {
                matchedParent = parentCandidates.get(0);
            }
        }
        if (matchedParent == null) {
            // 同名多命中时不做匹配：阻断错误已给出，等用户消歧后重新导入。
            return new DistributionRowAnchor(row, errors, warnings, normalized, null, unusedParentRow);
        }
        normalized = withParentIndicatorId(normalized, matchedParent.getId());
            // 2026-10-31 终版表头加列（学院模板「任务类型」）：锚点级只读校验——文件填写值与系统
            // 父级核心指标所属任务类型不一致 → 阻断点名（与下方「核心指标类型」不一致=阻断同款
            // 处理与文案风格）。该列为系统预填信息，不新增落库字段：子指标任务类别仍由父级继承链
            // 决定（commitDistribution 落库链同口径）；旧文件无该列时取空串，校验自动跳过（回导兼容）。
            TaskType parentTaskCategory = resolveParentTaskCategory(matchedParent);
            if (!isBlank(normalized.taskType()) && parentTaskCategory == null) {
                errors.add("第 " + row.rowNo() + " 行任务类型与上级下发内容不一致（不可修改）：父级核心指标“"
                        + matchedParent.getIndicatorDesc() + "”所属任务类型在系统中缺失，请重新下载模板预填");
            } else if (!isBlank(normalized.taskType())
                    && !sameName(normalized.taskType(), taskTypeLabel(parentTaskCategory))) {
                errors.add("第 " + row.rowNo() + " 行任务类型与上级下发内容不一致（不可修改）：父级核心指标“"
                        + matchedParent.getIndicatorDesc() + "”所属任务类型应为“"
                        + taskTypeLabel(parentTaskCategory) + "”");
            }
            // 2026-10-31 终版表头：「核心指标类型」=父指标自身类型，模板已按系统数据预填。
            // 2026-10-31 用户拍板（父级行=上级定死锚点）：类型被改从警告升级为阻断错误——
            // 预填父级行的名称/类型必须与系统下发完全一致（不可修改）
            if (!isBlank(normalized.parentIndicatorType())
                    && !sameName(normalized.parentIndicatorType(), matchedParent.getType())) {
                errors.add("第 " + row.rowNo() + " 行父级核心指标与上级下发内容不一致（不可修改）：应为“"
                        + matchedParent.getIndicatorDesc() + "（" + matchedParent.getType() + "）”");
            } else if (unusedParentRow) {
                // 未使用的父级预填行（父级内容未改动且未填子指标）：由组装阶段置 SKIP，不再报「子指标名称不能为空」
            } else if (parentTaskCategory == null) {
                // 2026-11-01（E2E 缺陷修复）：学院模板没有「任务类别」列，子指标落库的任务类别
                // 完全由父级核心指标所属任务继承（commitDistribution 落库链同口径）。父级任务类别
                // 取不到（父指标未关联任务/任务不存在/类别缺失，数据异常）时按系统默认「发展性」
                // 落库——预览行先打警告，不静默。
                warnings.add("父级核心指标「" + matchedParent.getIndicatorDesc()
                        + "」所属任务不存在或任务类别缺失，导入后任务类别将按系统默认「发展性」落库");
            }
        return new DistributionRowAnchor(row, errors, warnings, normalized, matchedParent, unusedParentRow);
    }

    /**
     * 2026-10-31 用户拍板（父级行=上级定死锚点）：未使用的父级预填行识别口径——
     * 子指标名称、指标类型、权重三列全空 = 未填任何子指标（与解析链 isParentOnlyDistributionRow 同口径）。
     */
    private boolean isUnusedParentRow(NormalizedImportRow row) {
        return isBlank(row.indicatorName()) && isBlank(row.indicatorType()) && row.weight() == null;
    }

    /**
     * 2026-10-31 用户拍板（父级行=上级定死锚点）：相对顺序校验——文件中出现的父级组，
     * 其相对顺序必须与系统下发顺序一致（子序列关系；允许删掉不填的组，不允许调换/乱序）。
     * 判定口径：文件父级组序列相对系统顺序存在逆序对即乱序，参与逆序的组全部点名阻断
     * （点名行序与父级指标内容）。
     */
    private void applyParentOrderValidation(List<DistributionRowAnchor> anchors,
                                            List<Indicator> systemParentAnchors) {
        // 文件中父级组出现顺序：按父级 ID 去重（同组多行取首次出现），沿用文件行序
        Map<Long, List<Integer>> rowIndexesByParentId = new LinkedHashMap<>();
        for (int index = 0; index < anchors.size(); index++) {
            Indicator matched = anchors.get(index).matchedParent();
            if (matched == null || matched.getId() == null) {
                // 未匹配/多匹配的行已有各自的阻断错误，不参与顺序校验
                continue;
            }
            rowIndexesByParentId.computeIfAbsent(matched.getId(), key -> new ArrayList<>()).add(index);
        }
        if (rowIndexesByParentId.isEmpty() || systemParentAnchors.isEmpty()) {
            return;
        }
        Map<Long, Integer> systemPositionById = new HashMap<>();
        for (int position = 0; position < systemParentAnchors.size(); position++) {
            systemPositionById.putIfAbsent(systemParentAnchors.get(position).getId(), position);
        }
        List<Long> fileGroupOrder = new ArrayList<>(rowIndexesByParentId.keySet());
        Set<Long> violatingParents = new HashSet<>();
        for (int left = 0; left < fileGroupOrder.size(); left++) {
            Integer leftPosition = systemPositionById.get(fileGroupOrder.get(left));
            if (leftPosition == null) {
                continue;
            }
            for (int right = left + 1; right < fileGroupOrder.size(); right++) {
                Integer rightPosition = systemPositionById.get(fileGroupOrder.get(right));
                if (rightPosition == null) {
                    continue;
                }
                if (leftPosition > rightPosition) {
                    // 逆序对：两个组的相对顺序与系统下发顺序矛盾，双双点名
                    violatingParents.add(fileGroupOrder.get(left));
                    violatingParents.add(fileGroupOrder.get(right));
                }
            }
        }
        if (violatingParents.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, List<Integer>> entry : rowIndexesByParentId.entrySet()) {
            if (!violatingParents.contains(entry.getKey())) {
                continue;
            }
            Indicator parent = anchors.get(entry.getValue().get(0)).matchedParent();
            for (Integer rowIndex : entry.getValue()) {
                DistributionRowAnchor anchor = anchors.get(rowIndex);
                anchor.errors().add("第 " + anchor.row().rowNo() + " 行父级核心指标“"
                        + parent.getIndicatorDesc() + "”的顺序与上级下发顺序不一致（不可调换父级指标顺序，允许删除不填的组）");
            }
        }
    }

    private CommitCounters commitStrategic(PreviewContext context,
                                           ConflictMode conflictMode,
                                           CurrentUser currentUser) {
        SysOrg currentOrg = requireOrg(currentUser.getOrgId(), "当前组织不存在");
        SysOrg targetOrg = requireOrg(context.targetOrgId(), "目标职能部门不存在");
        Plan plan = findOrCreatePlan(context.cycleId(), PlanLevel.STRAT_TO_FUNC, currentOrg.getId(), targetOrg.getId());
        CommitCounter counter = new CommitCounter(plan);

        Set<Long> coveredIds = new HashSet<>();
        List<ImportRowPreview> importRows = context.response().rows();
        for (int rowIndex = 0; rowIndex < importRows.size(); rowIndex++) {
            ImportRowPreview row = importRows.get(rowIndex);
            if (row.action() == ImportAction.ERROR) {
                counter.skipped++;
                continue;
            }
            NormalizedImportRow normalized = row.normalized();

            StrategicTask task = findOrCreateTask(
                    plan,
                    context.cycleId(),
                    normalized.strategicTask(),
                    toTaskType(normalized.taskType()),
                    targetOrg,
                    currentOrg);

            // 2026-10-31 层级化定案：按名称匹配更新目标——同任务组内核心指标同名=更新、新名称=新增；
            // 匹配键 =（任务, 核心指标名）。改名不支持（请在界面修改）。
            Indicator target = null;
            if (row.action() == ImportAction.UPDATE) {
                target = resolveUpdateTarget(normalized, task.getId(), null);
                if (target == null) {
                    // 预览后目标已变化（被删/改归属）：安全跳过，避免误建或误改
                    counter.skipped++;
                    continue;
                }
            }

            Indicator indicator;
            if (target != null) {
                indicator = strategyApplicationService.updateIndicator(
                        target.getId(),
                        normalized.indicatorName(),
                        normalized.weight(),
                        null,
                        null,
                        normalized.remark(),
                        task.getId(),
                        currentOrg,
                        targetOrg);
                counter.updated++;
            } else {
                indicator = strategyApplicationService.createIndicator(
                        normalized.indicatorName(),
                        currentOrg,
                        targetOrg,
                        task.getId(),
                        null,
                        normalized.indicatorType(),
                        defaultWeight(normalized.weight()),
                        // 2026-10-31 层级化定案：序号升级为任务组号（组内共享），sortOrder 改按
                        // 文件行序（1-based）写入，保持与用户表格一致的稳定排序
                        rowIndex + 1,
                        normalized.remark(),
                        0);
                counter.created++;
            }
            if (indicator.getId() != null) {
                coveredIds.add(indicator.getId());
            }
            activateIndicatorIfPlanDistributed(plan, indicator);
        }
        // 2026-10-19 用户定案（真覆盖）：勾选「覆盖已有数据」时，该计划+部门下不在本次文件
        // 覆盖集合中的既有指标全部软删除（打删除标记，历史可查、关联上报不显示）。
        if (conflictMode == ConflictMode.UPDATE) {
            counter.deleted += softDeleteIndicatorsNotCovered(plan, null, coveredIds);
        }
        return counter.toCounters();
    }

    private int softDeleteIndicatorsNotCovered(Plan plan, Long targetCollegeOrgId, Set<Long> coveredIds) {
        if (plan == null) {
            return 0;
        }
        List<Indicator> scopeIndicators = indicatorsInPlanScope(plan, targetCollegeOrgId);
        int deleted = 0;
        for (Indicator indicator : scopeIndicators) {
            if (indicator.getId() == null || coveredIds.contains(indicator.getId())) {
                continue;
            }
            indicator.setIsDeleted(true);
            indicator.setUpdatedAt(java.time.LocalDateTime.now());
            indicatorRepository.save(indicator);
            deleted++;
        }
        return deleted;
    }

    /** 按计划+可选学院范围列出现有未删指标（覆盖删除的作用域口径，与预览 existing 查询一致） */
    private List<Indicator> indicatorsInPlanScope(Plan plan, Long targetCollegeOrgId) {
        List<StrategicTask> tasks = taskRepository.findByPlanId(plan.getId());
        List<Long> taskIds = tasks.stream().map(StrategicTask::getId).filter(Objects::nonNull).toList();
        if (taskIds.isEmpty()) {
            return List.of();
        }
        return indicatorRepository.findByTaskIds(taskIds).stream()
                .filter(indicator -> targetCollegeOrgId == null
                        ? indicator.getParentIndicatorId() == null
                        : indicator.getParentIndicatorId() != null)
                .filter(indicator -> targetCollegeOrgId == null
                        || (indicator.getTargetOrg() != null
                        && Objects.equals(indicator.getTargetOrg().getId(), targetCollegeOrgId)))
                .filter(indicator -> Boolean.FALSE.equals(indicator.getIsDeleted()))
                .toList();
    }

    /**
     * 解析 UPDATE 行的更新目标（2026-10-31 层级化定案）：
     * 按「任务 + 名称（+父指标）」匹配——同名=更新、新名称=新增（新增不进此方法）。
     */
    private Indicator resolveUpdateTarget(NormalizedImportRow normalized,
                                          Long taskId,
                                          Long parentIndicatorId) {
        return findIndicatorByTaskAndName(taskId, normalized.indicatorName(), parentIndicatorId).orElse(null);
    }

    private CommitCounters commitDistribution(PreviewContext context,
                                             ConflictMode conflictMode) {
        // 2026-11-05（学院指标直发改造）：来源组织固定为战略发展部（B7）。
        // 预览阶段已保证 sourceOrgId = 战略部，这里再做一次防御性校验，
        // 防止任何绕过预览直接落库的路径把职能部门当成下发方。
        SysOrg currentOrg = requireOrg(context.sourceOrgId(), "来源部门不存在");
        if (!isStrategyOrg(currentOrg)) {
            throw new IllegalArgumentException(
                    "学院指标下发已统一由战略发展部发起，职能部门不再向学院分发指标");
        }
        SysOrg targetCollege = requireOrg(context.targetOrgId(), "目标学院不存在");
        // 2026-11-05（B7）：计划创建组织固定为战略发展部（复用 FUNC_TO_COLLEGE 级别，不新增枚举）
        Plan plan = findOrCreatePlan(context.cycleId(), PlanLevel.FUNC_TO_COLLEGE, currentOrg.getId(), targetCollege.getId());
        CommitCounter counter = new CommitCounter(plan);
        Set<Long> coveredIds = new HashSet<>();
        // 2026-11-09（修复学院上报链第 3 环派单失败）：本批导入所依赖的职能部门集合。
        // 父级核心指标的目标组织 = 「该学院归口哪个职能部门」，这正是
        // ApproverResolver 派「分管职能部门负责人审批」要用的席位来源。
        // 不写 plan.functional_org_id 时它会回退到计划创建方（战略部 org 35），
        // 而 org 35 没有 role2 账号 → 派单抛 No available approver candidates，上报卡死。
        Set<Long> dispatchFunctionalOrgIds = new LinkedHashSet<>();
        List<ImportRowPreview> importRows = context.response().rows();

        for (int rowIndex = 0; rowIndex < importRows.size(); rowIndex++) {
            ImportRowPreview row = importRows.get(rowIndex);
            // 2026-10-31 用户拍板（父级行=上级定死锚点）：未使用的父级预填行（SKIP）不落库
            if (row.action() == ImportAction.ERROR || row.action() == ImportAction.SKIP
                    || row.normalized().parentIndicatorId() == null) {
                counter.skipped++;
                continue;
            }
            NormalizedImportRow normalized = row.normalized();
            Indicator parent = indicatorRepository.findById(normalized.parentIndicatorId())
                    .orElseThrow(() -> new IllegalArgumentException("父级指标不存在: " + normalized.parentIndicatorId()));
            // P6 ID 三类校验（会议定案：异常难追踪，必须前置拦截）
            // 2026-11-05（学院指标直发改造）：父级归属口径改为「父级归属组织 = 战略发展部 且父级本身无上级」。
            // 原口径是「父级 target_org == 当前来源部门」——改造前来源部门是职能部门，父级正是下发给
            // 它的核心指标；改造后来源部门变成战略部自己，父级的 target 是职能部门，
            // 原条件恒不成立 → 这道校验会静默失效，任何父级都能被挂上。
            Long parentOwnerOrgId = parent.getOwnerOrg() == null ? null : parent.getOwnerOrg().getId();
            if (parentOwnerOrgId == null
                    || !parentOwnerOrgId.equals(currentOrg.getId())
                    || parent.getParentIndicatorId() != null) {
                throw new IllegalArgumentException(
                        "父级指标 ID " + normalized.parentIndicatorId() + " 不属于战略发展部下发的核心指标，请核对导出表中的内部 ID");
            }
            // 2026-11-09：父级核心指标的目标组织就是「归口职能部门」，收集起来落进计划
            if (parent.getTargetOrg() != null && parent.getTargetOrg().getId() != null) {
                dispatchFunctionalOrgIds.add(parent.getTargetOrg().getId());
            }
            StrategicTask parentTask = parent.getTaskId() == null
                    ? null
                    : taskRepository.findById(parent.getTaskId()).orElse(null);
            if (parentTask != null && parentTask.getCycleId() != null) {
                if (!parentTask.getCycleId().equals(context.cycleId())) {
                    throw new IllegalArgumentException(
                            "父级指标 ID " + normalized.parentIndicatorId() + " 属于其他考核年度，不能跨年度关联，请重新导出最新模板");
                }
            } else {
                // 2026-11-05（BL3）：父级没有可用任务时，跨年度校验不能整段跳过——
                // 原口径是「父指标有 task 才查」，父指标没挂任务时校验被跳过 → 静默把别的年度的
                // 父级挂上来（错挂且无痕迹）。改用「父级是否仍在当前考核周期的锚点集合内」判定：
                // 不在 → 明确拒绝跨年度关联；在 → 允许落库（保留 2026-11-01 定案：父级任务类别
                // 取不到时按系统默认发展性落库并给警告，不静默）。
                boolean parentInCurrentCycleAnchors = indicatorRepository
                        .findCoreDispatchAnchors(currentOrg.getId(), context.cycleId())
                        .stream()
                        .anyMatch(anchor -> Objects.equals(anchor.getId(), parent.getId()));
                if (!parentInCurrentCycleAnchors) {
                    throw new IllegalArgumentException(
                            "父级指标 ID " + normalized.parentIndicatorId()
                                    + " 未关联有效战略任务，且不属于本考核年度战略发展部下发的核心指标，"
                                    + "不能跨年度关联，请重新导出最新模板");
                }
            }
            String taskName = firstNonBlank(normalized.parentStrategicTask(), parentTask == null ? null : parentTask.getName(), "学院子指标");
            // 2026-11-01（E2E 缺陷修复）：学院模板没有「任务类别」列，子指标落库的 taskCategory
            // 必须继承父级核心指标所属任务的类别（父指标 → 其任务 → task_type，与预览
            // enrichDistributionRows 的警告口径一致）；父级任务类别取不到（数据异常）时
            // 沿用系统默认值 DEVELOPMENT 并打警告日志，不静默。
            TaskType taskType = parentTask == null ? null : parentTask.getTaskType();
            if (taskType == null) {
                log.warn("[Import] 批次 {} 学院子指标落库：父级核心指标 {} 未关联任务或任务类别缺失，任务类别按系统默认 DEVELOPMENT 处理",
                        context.batchId(), normalized.parentIndicatorId());
                taskType = TaskType.DEVELOPMENT;
            }
            StrategicTask task = findOrCreateTask(plan, context.cycleId(), taskName, taskType, targetCollege, currentOrg);

            // 2026-10-31 层级化定案：按名称匹配更新目标——同父级组内子指标同名=更新、新名称=新增；
            // 匹配键 =（父级核心指标, 子指标名）。改名不支持（请在界面修改）。
            Indicator target = null;
            if (row.action() == ImportAction.UPDATE) {
                target = resolveUpdateTarget(normalized, task.getId(), normalized.parentIndicatorId());
                if (target == null) {
                    counter.skipped++;
                    continue;
                }
            }

            Indicator indicator;
            if (target != null) {
                indicator = strategyApplicationService.updateIndicator(
                        target.getId(),
                        normalized.indicatorName(),
                        normalized.weight(),
                        null,
                        null,
                        normalized.remark(),
                        task.getId(),
                        currentOrg,
                        targetCollege);
                counter.updated++;
            } else {
                indicator = strategyApplicationService.createIndicator(
                        normalized.indicatorName(),
                        currentOrg,
                        targetCollege,
                        task.getId(),
                        normalized.parentIndicatorId(),
                        normalized.indicatorType(),
                        defaultWeight(normalized.weight()),
                        // 2026-10-31 层级化定案：序号升级为父级核心指标组号（组内共享），sortOrder 改按
                        // 文件行序（1-based）写入，保持与用户表格一致的稳定排序
                        rowIndex + 1,
                        normalized.remark(),
                        0);
                counter.created++;
            }
            if (indicator.getId() != null) {
                coveredIds.add(indicator.getId());
            }
            activateIndicatorIfPlanDistributed(plan, indicator);
        }
        // 2026-10-19 用户定案（真覆盖）：勾选覆盖时，该学院链路上不在文件覆盖集合中的子指标软删除
        if (conflictMode == ConflictMode.UPDATE) {
            counter.deleted += softDeleteIndicatorsNotCovered(plan, targetCollege.getId(), coveredIds);
        }
        // 2026-11-09：把本批导入推出的归口职能部门落到计划上（学院上报链席位用）
        applyFunctionalOrgFromDispatchChain(plan, dispatchFunctionalOrgIds);
        return counter.toCounters();
    }

    /**
     * 2026-11-09（修复学院上报链派单失败）：把「归口职能部门」写进计划。
     *
     * <p><b>为什么需要</b>：学院上报链第 3 环「分管职能部门负责人审批」（role2）的席位，
     * {@code ApproverResolver} 优先读 {@code plan.functional_org_id}；该字段为空时会回退到
     * 「计划创建方」，而学院下发计划的创建方是战略发展部（org 35）——org 35 没有 role2 账号，
     * 派单直接抛 {@code No available approver candidates}，学院上报会永久卡在第 3 环。
     *
     * <p><b>为什么能从父级推出来</b>：学院子指标的父级核心指标是「战略部 → 职能部门」下发的，
     * 其 {@code target_org} 就是那个职能部门，也就是「该学院归口哪个部门」的准确答案。
     *
     * <p><b>多部门的情况</b>：一个学院理论上只归口一个职能部门，但历史数据可能跨多个部门。
     * 这里取 id 最小的作为稳定选择并打 warn，便于运维发现口径分裂，而不是静默随机。
     */
    private void applyFunctionalOrgFromDispatchChain(Plan plan, Set<Long> functionalOrgIds) {
        if (plan == null || functionalOrgIds == null || functionalOrgIds.isEmpty()) {
            return;
        }
        Long chosen = functionalOrgIds.stream()
                .filter(Objects::nonNull)
                .min(Long::compareTo)
                .orElse(null);
        if (chosen == null) {
            return;
        }
        if (functionalOrgIds.size() > 1) {
            log.warn("学院计划 {} 的父级核心指标来自多个职能部门 {}，归口部门取 id 最小者 {}；"
                    + "学院上报审批将派给该部门，请确认数据口径", plan.getId(), functionalOrgIds, chosen);
        }
        if (!Objects.equals(plan.getFunctionalOrgId(), chosen)) {
            plan.setFunctionalOrgId(chosen);
            planRepository.save(plan);
        }
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

            // 导入旁路守卫：与 PlanApplicationService.submitPlanForApprovalInTransaction 口径一致，
            // 下发所依赖的职能部门指标发起异动（PLAN_MUTATION_STRATEGY / IN_REVIEW）期间，
            // 不得再向学院自动下发（仅拦 FUNC_TO_COLLEGE 计划）。
            if (plan.getPlanLevel() == PlanLevel.FUNC_TO_COLLEGE) {
                assertFunctionalDeptMutationNotInFlight(plan);
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
                // 2026-10-19 用户定案：自动审批逐节点通知，与人工下发一致——
                // 每步通知提交人推进 + 下一环节候选人待审批，终态通知结果。
                AuditInstance approvedStep = businessWorkflowApplicationService.autoApproveCurrentStep(
                        current,
                        systemAdmin.getId(),
                        "系统自动审批通过：来源于导入批次，系统管理员统一处理，确认人 " + currentUser.getUsername());
                if (approvedStep == null) {
                    break;
                }
                current = approvedStep;
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

    /**
     * 导入自动下发旁路守卫：与 PlanApplicationService.submitPlanForApprovalInTransaction 中的
     * 同名守卫口径一致，防止「下发所依赖的职能部门指标正在异动审批期间，仍通过导入向学院下发」。
     *
     * <p>2026-11-05（学院指标直发改造）：冲突范围改为<b>显式口径</b>——本下发计划下学院子指标的
     * 父级核心指标，其目标部门（职能部门）的指标是否正在异动审批中。
     * 原口径用 {@code plan.createdByOrgId} 圈定「该职能部门」：改造后 created_by 恒为战略部（35），
     * 条件恒不成立 → 守卫静默失效（既不报错也不拦截）。
     */
    private void assertFunctionalDeptMutationNotInFlight(Plan plan) {
        if (plan == null || plan.getId() == null) {
            return;
        }
        if (collegeDispatchGuardService.isSourceOrgMutationInFlight(plan.getId(), plan.getCycleId())) {
            throw new IllegalStateException(
                    "下发所依赖的职能部门指标正在异动审批中，暂不能下发；请等异动审批结束或撤回后再操作");
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

    /**
     * 2026-11-05（学院指标直发改造）：战略发展部 org id（配置项 {@code sism.strategy.org.strategy-org-id}，
     * 默认 35）。学院子指标下发的来源组织、计划创建组织、指标归属组织全部以它为准。
     */
    private Long strategyOrgId() {
        Long orgId = strategyOrgProperties.getStrategyOrgId();
        return orgId == null ? DEFAULT_STRATEGY_ORG_ID : orgId;
    }

    /**
     * 2026-11-05（学院指标直发改造）：判断组织是否为战略发展部。
     * 既校验组织类型（admin）也校验具体 org id——系统里可能存在其它 admin 类型组织，
     * 只看类型会把它们误当成合法下发方。
     */
    private boolean isStrategyOrg(SysOrg org) {
        return org != null
                && org.getType() == OrgType.admin
                && Objects.equals(org.getId(), strategyOrgId());
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
                                            null,
                                            task == null ? "" : taskTypeLabel(task.getTaskType()),
                                            task == null ? "" : task.getName(),
                                            null,
                                            null,
                                            null,
                                            indicator.getIndicatorDesc(),
                                            indicator.getType(),
                                            indicator.getWeightPercent(),
                                            indicator.getRemark(),
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
                                        null,
                                        null,
                                        indicator.getIndicatorDesc(),
                                        indicator.getType(),
                                        indicator.getWeightPercent(),
                                        indicator.getRemark(),
                                        indicator.getParentIndicatorId())),
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
                row.seq(),
                row.department(),
                row.college(),
                row.taskType(),
                row.strategicTask(),
                row.parentStrategicTask(),
                row.parentIndicator(),
                row.parentIndicatorType(),
                row.indicatorName(),
                row.indicatorType(),
                row.weight(),
                row.remark(),
                parentIndicatorId);
    }

    /**
     * 解析父级核心指标所属任务的类别（学院子指标 taskCategory 的继承来源，2026-11-01 E2E 缺陷修复）。
     * 父指标未关联任务、任务不存在或任务类别缺失（数据异常）时返回 null，
     * 由调用方决定兜底方式：预览行加警告、落库按系统默认 DEVELOPMENT 并打 log.warn，不静默。
     */
    private TaskType resolveParentTaskCategory(Indicator parent) {
        if (parent == null || parent.getTaskId() == null) {
            return null;
        }
        StrategicTask parentTask = taskRepository.findById(parent.getTaskId()).orElse(null);
        return parentTask == null ? null : parentTask.getTaskType();
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
            int deleted,
            Plan plan
    ) {
    }

    private static class CommitCounter {
        private int created;
        private int updated;
        private int skipped;
        private int deleted;
        private final Plan plan;

        private CommitCounter(Plan plan) {
            this.plan = plan;
        }

        private CommitCounters toCounters() {
            return new CommitCounters(created, updated, skipped, deleted, plan);
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
