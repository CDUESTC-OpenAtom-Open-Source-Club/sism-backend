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
import java.util.HashSet;
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
     * @return 存量计划；不存在则返回 null
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
                .findFirst()
                .orElse(null);
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
        // 2026-11-08（学院指标单层口径）：学院指标由战略部直发、**没有父级**。
        // 原「父级核心指标锚点匹配 + 锚点名称/类型只读校验 + 父级组相对顺序校验」整套机制
        // 的前提是「文件里预填了上级下发的父级行」；新模板（8 列）已没有该列，
        // 继续保留只会把每一行判成「父级核心指标无法匹配」而整批阻断。
        //
        // 因此现在只保留两条仍然成立的逐行校验：
        //   ① 学院列（填写时）必须与本次选择的下发对象一致——下发对象不能靠猜；
        //   ② 「归口职能部门」在同一批文件里只能有一个取值：plan 上只有一个 functional_org_id，
        //      多值无法落库，必须在预览阶段就点出来。名称能否对应到真实组织留到落库时
        //      查 sys_org（那时能给出「哪个名称不存在」的具体提示，也不必为预览多打一次库）。
        List<String> distinctFunctionalOrgs = rows.stream()
                .map(row -> row.normalized().functionalOrg())
                .filter(name -> !isBlank(name))
                .map(String::trim)
                .distinct()
                .toList();
        String conflictMessage = distinctFunctionalOrgs.size() > 1
                ? "本批导入的「归口职能部门」填写不一致（" + String.join("、", distinctFunctionalOrgs)
                        + "），一个学院只能归口一个职能部门，请统一后再导入"
                : null;

        Plan plan = findPlan(cycleId, PlanLevel.FUNC_TO_COLLEGE, sourceOrg.getId(), targetCollege.getId()).orElse(null);
        Map<String, Indicator> existingChildren = existingDistributionIndicators(plan, targetCollege);

        return rows.stream().map(row -> {
            List<String> errors = new ArrayList<>(row.errors());
            List<String> warnings = new ArrayList<>(row.warnings());
            NormalizedImportRow normalized = row.normalized();

            if (!isBlank(normalized.college()) && !sameName(normalized.college(), targetCollege.getName())) {
                errors.add("文件中的学院与当前选择不一致");
            }
            if (conflictMessage != null) {
                errors.add(conflictMessage);
            }

            String businessKey = distributionBusinessKey(
                    cycleId, sourceOrg.getId(), targetCollege.getId(), normalized);
            ImportAction action;
            if (!errors.isEmpty()) {
                action = ImportAction.ERROR;
            } else {
                action = existingChildren.containsKey(businessKey) ? ImportAction.UPDATE : ImportAction.CREATE;
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
                // 2026-11-08（学院指标单层口径）：学院覆盖分支不再要求「是子指标（parent != null）」——
                // 新口径学院指标 parent 恒为 null，旧条件会把该计划下的学院指标全部漏掉，
                // 覆盖模式一条都删不掉（软删除静默失效）。战略任务分支（targetCollegeOrgId == null）
                // 保持只看根指标，口径不变。
                .filter(indicator -> targetCollegeOrgId != null
                        || indicator.getParentIndicatorId() == null)
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
        // 2026-11-08（学院指标单层口径）：归口职能部门按**名称**查 sys_org 换成 id，
        // 落到 plan.functional_org_id（审批席位来源，见 ApproverResolver）。
        // 为什么不能靠上级/组织树推：单层直发后学院指标没有父级，8 个学院的 parent_org_id
        // 全是 35，created_by_org_id 现在也固定是战略部，三条路都推不出「学院归口哪个职能部门」。
        // 留空 = 不指定 → 保持 null，审批席位回退改造前口径（向后兼容）。
        Long functionalOrgId = resolveFunctionalOrgId(context.response().rows());
        if (functionalOrgId != null && !Objects.equals(plan.getFunctionalOrgId(), functionalOrgId)) {
            plan.setFunctionalOrgId(functionalOrgId);
            plan = planRepository.save(plan);
        }
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
            // 2026-11-08（学院指标单层口径）：学院指标不再挂父级，也就没有「父级任务 → 子指标」
            // 的继承链。每行直接决定「学院的某个任务（基础性/发展性）+ 一条指标」：
            //   · 任务类别取自文件「任务类型」列（战略部导入时显式指定）；
            //   · 任务名沿用旧文件的「父级战略任务」列（若有），否则按类别给默认名，
            //     避免基础性/发展性两类指标被塞进同一个同名任务导致类别串味。
            TaskType taskType = toTaskType(normalized.taskType());
            String taskName = firstNonBlank(
                    normalized.parentStrategicTask(),
                    taskType == TaskType.BASIC ? "学院基础性指标" : "学院发展性指标");
            StrategicTask task = findOrCreateTask(plan, context.cycleId(), taskName, taskType, targetCollege, currentOrg);

            // 2026-10-31 层级化定案（2026-11-08 沿用）：按名称匹配更新目标——
            // 匹配键 =（学院任务, 指标名），同名=更新、新名称=新增。改名不支持（请在界面修改）。
            Indicator target = null;
            if (row.action() == ImportAction.UPDATE) {
                target = resolveUpdateTarget(normalized, task.getId(), null);
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
                        // 2026-11-08（学院指标单层口径）：parent 传 null ——
                        // 学院指标不再要求挂父级核心指标，结构上就是一级直发的指标。
                        null,
                        normalized.indicatorType(),
                        defaultWeight(normalized.weight()),
                        // sortOrder 按文件行序（1-based）写入，保持与用户表格一致的稳定排序
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
        // 2026-10-19 用户定案（真覆盖）：勾选覆盖时，该学院计划下不在文件覆盖集合中的指标软删除
        if (conflictMode == ConflictMode.UPDATE) {
            counter.deleted += softDeleteIndicatorsNotCovered(plan, targetCollege.getId(), coveredIds);
        }
        return counter.toCounters();
    }

    /**
     * 2026-11-08（学院指标单层口径）：把文件里的「归口职能部门」名称解析成 sys_org.id。
     *
     * <p>规则：
     * <ul>
     *   <li>整批都留空 → 返回 null（plan.functional_org_id 保持为空，审批席位回退原口径）；</li>
     *   <li>同一批出现多个不同名称 → 直接拒绝：plan 上只有一个 functional_org_id，
     *       多值必然丢一半，而且会让人以为「已经按行分派」，必须让人先统一；</li>
     *   <li>名称查不到、或查到但不是职能部门 → 拒绝并点名名称（不能静默落空，
     *       否则该学院上报的「分管职能部门负责人审批」席位会退回战略部，与业务口径相反）。</li>
     * </ul>
     *
     * <p>为什么用 {@code findByNameContaining} 而不是精确查询：仓储层没有按名精确查的方法，
     * 为这一处新增仓储方法不值得；这里取「包含」结果后再按名称归一化精确匹配，
     * 不会把「教务处」误配到「教务处（旧）」。
     */
    private Long resolveFunctionalOrgId(List<ImportRowPreview> rows) {
        List<String> names = rows.stream()
                .filter(row -> row.action() != ImportAction.ERROR)
                .map(row -> row.normalized().functionalOrg())
                .filter(name -> !isBlank(name))
                .map(String::trim)
                .distinct()
                .toList();
        if (names.isEmpty()) {
            return null;
        }
        if (names.size() > 1) {
            throw new IllegalArgumentException(
                    "本批导入的「归口职能部门」填写不一致（" + String.join("、", names)
                            + "），一个学院只能归口一个职能部门，请统一后再导入");
        }
        String name = names.get(0);
        return organizationRepository.findByNameContaining(name).stream()
                .filter(org -> org.getType() == OrgType.functional)
                .filter(org -> sameName(org.getName(), name))
                .map(SysOrg::getId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "归口职能部门“" + name + "”不存在或不是职能部门，请核对名称"
                                + "（应为组织机构中类型为职能部门的组织名称）"));
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
                // 2026-11-08（学院指标单层口径）：学院指标不再挂父级（parent 恒为 null），
                // 原「必须是子指标」的过滤会让新口径的学院指标全部漏网 → 预览永远判 CREATE，
                // 同名指标被重复创建（同一学院出现两套生效指标，权重合计失真）。
                // 改为按「目标组织 = 本学院」圈定：FUNC_TO_COLLEGE 计划下的指标目标就是该学院。
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
                                        // 2026-11-08：新口径学院指标 parent 恒为 null，
                                        // key 里这一位就是 null（与文件行一致）。
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
                // 2026-11-08（学院指标单层口径）：学院指标不再有父级，这一位恒为 "null"，
                // 匹配键退化为（周期, 归属组织, 学院, 指标名）——同名=更新、新名称=新增。
                // 保留该占位是为了让「存量旧数据（有父级）→ 新口径（无父级）」的 key 仍然可区分，
                // 不会被误判成同一条。
                String.valueOf(row.parentIndicatorId()),
                normalizeKey(row.indicatorName()));
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
