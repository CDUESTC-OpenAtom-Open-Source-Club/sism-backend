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
    /** 2026-10-19：自动审批复用普通审批的节点通知（提交人推进 + 下一环节待审批 + 终态结果） */
    private final BusinessWorkflowApplicationService businessWorkflowApplicationService;
    private final AuditInstanceRepository auditInstanceRepository;
    private final TransactionTemplate transactionTemplate;
    private final org.springframework.jdbc.core.JdbcTemplate importBatchJdbcTemplate;
    private final Map<String, PreviewContext> previews = new ConcurrentHashMap<>();

    public ImportPreviewResponse previewStrategicTasks(MultipartFile file,
                                                       Long cycleId,
                                                       Long targetOrgId,
                                                       String sheetName,
                                                       Map<Integer, Long> sequenceMap,
                                                       CurrentUser currentUser) {
        requireCycleAndUser(cycleId, currentUser);
        SysOrg targetOrg = requireOrg(targetOrgId, "目标职能部门不存在");
        if (targetOrg.getType() != OrgType.functional) {
            throw new IllegalArgumentException("战略任务导入目标必须是职能部门");
        }

        ParsedWorkbook parsed = parser.parse(file, ImportType.STRATEGIC_TASK, sheetName);
        List<ImportRowPreview> rows = enrichStrategicRows(parsed.rows(), cycleId, targetOrg, sequenceMap, currentUser);
        return storePreview(file, parsed, rows, ImportType.STRATEGIC_TASK, cycleId, currentUser.getOrgId(), targetOrg, sequenceMap, currentUser);
    }

    public ImportPreviewResponse previewDistribution(MultipartFile file,
                                                     Long cycleId,
                                                     Long targetCollegeOrgId,
                                                     Long sourceOrgId,
                                                     String sheetName,
                                                     Map<Integer, Long> sequenceMap,
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
        List<ImportRowPreview> rows = enrichDistributionRows(parsed.rows(), cycleId, sourceOrg, targetOrg, sequenceMap);
        return storePreview(file, parsed, rows, ImportType.DISTRIBUTION, cycleId, sourceOrg.getId(), targetOrg, sequenceMap, currentUser);
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
                // 2026-10-28 用户定案：导入后权重自动对齐到「计划合计 100」。
                // 规则：权重全空 → 等分；手填部分 → 保留手填值，其余平分剩余额度。
                normalizePlanWeights(committed.plan(), context.type());
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

    /** 权重分配方式。 */
    enum WeightAllocationMode {
        /** 全部指标等分（权重全空时）。 */
        EVEN_ALL,
        /** 仅留空项等分剩余额度（保留手填值）。 */
        EVEN_BLANKS,
        /** 不自动改写，交由校验拦截（手填值须被尊重）。 */
        KEEP_MANUAL
    }

    /**
     * 权重分配方案（纯计算结果，便于单测）。
     *
     * @param mode        分配方式
     * @param remaining   待分配额度（仅 EVEN_BLANKS 有意义）
     * @param blankCount  留空/待分配的指标条数
     * @param totalCount  参与分配的指标总条数
     */
    record WeightAllocationPlan(WeightAllocationMode mode,
                                BigDecimal remaining,
                                int blankCount,
                                int totalCount) {
    }

    /**
     * 决定权重分配方式（纯函数）。
     *
     * <p>口径来自需求方确认：「等分，但保留手动覆盖」——
     * 手填的权重不得被系统改写，因此只有在「有留空项且已填合计未满 100」时才做分配；
     * 已满/超额一律不改写，交给下发校验拦截。
     *
     * @param weights 参与分配的权重值（null 视为留空；<= 0.01 视为待分配标记）
     * @param target  目标合计（100）
     */
    static WeightAllocationPlan decideAllocation(List<BigDecimal> weights, BigDecimal target) {
        int totalCount = weights.size();
        if (totalCount == 0) {
            return new WeightAllocationPlan(WeightAllocationMode.KEEP_MANUAL, BigDecimal.ZERO, 0, 0);
        }
        int blankCount = 0;
        BigDecimal filledTotal = BigDecimal.ZERO;
        for (BigDecimal weight : weights) {
            if (weight == null || weight.compareTo(PENDING_WEIGHT) <= 0) {
                blankCount++;
            } else {
                filledTotal = filledTotal.add(weight);
            }
        }

        if (blankCount == 0) {
            // 全部手填：合计正好 100 就无需动；否则不改写，交校验拦截
            return new WeightAllocationPlan(WeightAllocationMode.KEEP_MANUAL, BigDecimal.ZERO, 0, totalCount);
        }
        if (blankCount == totalCount) {
            return new WeightAllocationPlan(WeightAllocationMode.EVEN_ALL, target, blankCount, totalCount);
        }
        BigDecimal remaining = target.subtract(filledTotal);
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            return new WeightAllocationPlan(WeightAllocationMode.EVEN_BLANKS, remaining, blankCount, totalCount);
        }
        // 手填合计已满/超额：保留手填值，不自动改写
        return new WeightAllocationPlan(WeightAllocationMode.KEEP_MANUAL, BigDecimal.ZERO, blankCount, totalCount);
    }

    /**
     * 把 target 等分到 size 份，每份不小于 {@link #MIN_WEIGHT}，余数计入最后一份。
     *
     * @return 额度不足以保证每份保底时返回空列表（不分配，交给校验拦截）
     */
    static List<BigDecimal> splitEvenly(int size, BigDecimal target) {
        List<BigDecimal> result = new ArrayList<>(size);
        if (size <= 0) {
            return result;
        }
        BigDecimal minimumTotal = MIN_WEIGHT.multiply(BigDecimal.valueOf(size));
        if (target.compareTo(minimumTotal) < 0) {
            return result;
        }
        BigDecimal distributable = target.subtract(minimumTotal);
        BigDecimal each = distributable.divide(BigDecimal.valueOf(size), 2, java.math.RoundingMode.DOWN);
        BigDecimal assigned = BigDecimal.ZERO;
        for (int i = 0; i < size; i++) {
            BigDecimal weight = (i == size - 1) ? target.subtract(assigned) : each.add(MIN_WEIGHT);
            if (weight.compareTo(MIN_WEIGHT) < 0) {
                weight = MIN_WEIGHT;
            }
            result.add(weight);
            assigned = assigned.add(weight);
        }
        return result;
    }

    /**
     * 2026-10-28 用户定案：导入后把权重自动对齐到「计划合计 100%」。
     *
     * <ul>
     *   <li>权重全部为空（或为待分配标记）→ 按指标数量<b>等分</b>，余数补到最后一项；</li>
     *   <li>用户手填了部分权重 → <b>保留手填值</b>，其余留空项平分剩余额度；</li>
     *   <li>手填合计已满/超额 → <b>不自动改写</b>（包括不整体等分），交由下发/异动校验拦截。</li>
     * </ul>
     *
     * 目的：让「导入即合规」，用户不必自己凑 100；同时保留手动覆盖的表达能力
     * （用户手填的权重不会被系统改写）。
     */
    private void normalizePlanWeights(Plan plan, ImportType type) {
        if (plan == null) {
            return;
        }
        List<Indicator> indicators = loadPlanIndicatorsForPlan(plan, type);
        if (indicators.isEmpty()) {
            return;
        }

        BigDecimal target = BigDecimal.valueOf(100);
        List<BigDecimal> weights = indicators.stream()
                .map(Indicator::getWeightPercent)
                .collect(Collectors.toList());
        WeightAllocationPlan planAllocation = decideAllocation(weights, target);

        if (planAllocation.mode() == WeightAllocationMode.KEEP_MANUAL) {
            BigDecimal total = weights.stream()
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (planAllocation.blankCount() > 0) {
                log.warn("[Import] 手填权重合计 {}% 已满/超额，无法在不改动手填值的前提下分配剩余额度，"
                        + "本次不自动分配，将由校验拦截：planId={}",
                        total.stripTrailingZeros().toPlainString(), plan.getId());
            } else if (total.compareTo(target) != 0) {
                String direction = total.compareTo(target) > 0 ? "超过" : "不足";
                log.warn("[Import] 手填权重合计 {}% {} 100%，未自动归一化，将由校验拦截：planId={}",
                        total.stripTrailingZeros().toPlainString(), direction, plan.getId());
            }
            return;
        }

        List<Indicator> targets = planAllocation.mode() == WeightAllocationMode.EVEN_ALL
                ? indicators
                : indicators.stream()
                        .filter(indicator -> indicator.getWeightPercent() == null
                                || indicator.getWeightPercent().compareTo(PENDING_WEIGHT) <= 0)
                        .collect(Collectors.toList());

        BigDecimal remaining = planAllocation.mode() == WeightAllocationMode.EVEN_ALL
                ? target
                : planAllocation.remaining();
        List<BigDecimal> shares = splitEvenly(targets.size(), remaining);
        if (shares.size() != targets.size()) {
            log.warn("[Import] 额度 {}% 不足以给 {} 项各保底 {}%，本次不自动分配",
                    remaining.stripTrailingZeros().toPlainString(),
                    targets.size(), MIN_WEIGHT.toPlainString());
            return;
        }
        for (int i = 0; i < targets.size(); i++) {
            indicatorsWeightUpdate(targets.get(i), shares.get(i));
        }
        log.info("[Import] 权重自动分配完成：planId={} 方式={} 项数={} 额度={}%",
                plan.getId(), planAllocation.mode(), targets.size(),
                remaining.stripTrailingZeros().toPlainString());
    }

    private void indicatorsWeightUpdate(Indicator indicator, BigDecimal weight) {
        BigDecimal safe = weight == null ? MIN_WEIGHT : weight.max(MIN_WEIGHT);
        indicator.setWeightPercent(safe);
        indicatorRepository.save(indicator);
    }

    /** 取该计划下参与权重合计的指标：战略链取父指标，学院链取子指标。 */
    private List<Indicator> loadPlanIndicatorsForPlan(Plan plan, ImportType type) {
        List<Long> taskIds = taskRepository.findByPlanId(plan.getId()).stream()
                .map(StrategicTask::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (taskIds.isEmpty()) {
            return List.of();
        }
        Long targetOrgId = plan.getTargetOrgId();
        return indicatorRepository.findByTaskIds(taskIds).stream()
                .filter(indicator -> isInWeightScope(indicator, plan, type))
                .sorted(Comparator.comparing(Indicator::getId))
                .collect(Collectors.toList());
    }

    /**
     * 判���指标是否属于「计划内权重合计」的参与集合（2026-10-28）。
     *
     * <p>口径必须与校验侧（BasicTaskWeightValidationService）和存量迁移侧（V100）完全一致：
     * <ul>
     *   <li>排除软删；</li>
     *   <li>只算归属该计划目标部门（target_org）的指标；</li>
     *   <li>战略链取父指标；</li>
     *   <li>学院链取本部门创建（owner_org = plan.created_by_org_id）的子指标。</li>
     * </ul>
     */
    static boolean isInWeightScope(Indicator indicator, Plan plan, ImportType type) {
        if (Boolean.TRUE.equals(indicator.getIsDeleted())) {
            return false;
        }
        if (indicator.getTargetOrg() == null) {
            return false;
        }
        Long targetOrgId = plan.getTargetOrgId();
        if (targetOrgId != null && !targetOrgId.equals(indicator.getTargetOrg().getId())) {
            return false;
        }
        if (type == ImportType.STRATEGIC_TASK) {
            return indicator.getParentIndicatorId() == null;
        }
        if (indicator.getParentIndicatorId() == null) {
            return false;
        }
        Long createdByOrgId = plan.getCreatedByOrgId();
        if (createdByOrgId == null) {
            return true;
        }
        return indicator.getOwnerOrg() != null
                && createdByOrgId.equals(indicator.getOwnerOrg().getId());
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
                                               Map<Integer, Long> sequenceMap,
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
                sequenceMap == null ? Map.of() : Map.copyOf(sequenceMap),
                response
        ));
        return response;
    }

    private List<ImportRowPreview> enrichStrategicRows(List<ImportRowPreview> rows,
                                                       Long cycleId,
                                                       SysOrg targetOrg,
                                                       Map<Integer, Long> sequenceMap,
                                                       CurrentUser currentUser) {
        Plan plan = findPlan(cycleId, PlanLevel.STRAT_TO_FUNC, currentUser.getOrgId(), targetOrg.getId())
                .orElse(null);
        Map<String, Indicator> existing = existingStrategicIndicators(plan);
        // 2026-10-19：序号映射的合法性校验必须用「计划范围内全部指标ID」——
        // existing 是按业务键去重的 Map（同名指标会被合并），不能作为ID集合来源，
        // 否则同名指标中未入 Map 的那条会被误判为"已变化"。
        Set<Long> relatedIndicatorIds = plan == null
                ? Set.of()
                : indicatorsInPlanScope(plan, null).stream()
                        .map(Indicator::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

        return rows.stream().map(row -> {
            List<String> errors = new ArrayList<>(row.errors());
            List<String> warnings = new ArrayList<>(row.warnings());
            NormalizedImportRow normalized = row.normalized();

            if (!isBlank(normalized.department()) && !sameName(normalized.department(), targetOrg.getName())) {
                errors.add("文件中的职能部门与当前选择不一致");
            }

            ImportAction action;
            String businessKey;
            // 2026-10-19 用户定案：序号 → 内部ID 映射匹配（同名指标各按各的序号归属，不做名称合并）。
            // 映射非空（前端按导出同款排序生成）时严格按序号：命中的更新、超出范围的作为新行创建；
            // 映射为空（老客户端兜底）时退回名称匹配。
            Integer seq = normalized.seq();
            Long mappedId = (seq == null || sequenceMap == null) ? null : sequenceMap.get(seq);
            if (sequenceMap != null && !sequenceMap.isEmpty()) {
                if (mappedId != null && relatedIndicatorIds.contains(mappedId)) {
                    businessKey = "seq:" + seq + ":" + mappedId;
                    action = ImportAction.UPDATE;
                } else if (mappedId != null) {
                    businessKey = "seq:" + seq;
                    errors.add("序号 " + seq + " 对应的指标已变化（可能已被删除），请重新导出表格后再导入");
                    action = ImportAction.ERROR;
                } else {
                    businessKey = "seq:" + seq;
                    action = ImportAction.CREATE;
                }
            } else {
                businessKey = strategicBusinessKey(cycleId, targetOrg.getId(), normalized);
                action = existing.containsKey(businessKey) ? ImportAction.UPDATE : ImportAction.CREATE;
            }
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
                                                          SysOrg targetCollege,
                                                          Map<Integer, Long> sequenceMap) {
        Map<String, List<Indicator>> parentByName = indicatorRepository.findByTargetOrgId(sourceOrg.getId()).stream()
                .filter(indicator -> Boolean.FALSE.equals(indicator.getIsDeleted()))
                .collect(Collectors.groupingBy(indicator -> normalizeKey(indicator.getIndicatorDesc())));

        Plan plan = findPlan(cycleId, PlanLevel.FUNC_TO_COLLEGE, sourceOrg.getId(), targetCollege.getId()).orElse(null);
        Map<String, Indicator> existingChildren = existingDistributionIndicators(plan, targetCollege);
        // 同上：用计划+学院范围内全部子指标ID，避免同名去重导致误判
        Set<Long> relatedChildrenIds = plan == null
                ? Set.of()
                : indicatorsInPlanScope(plan, targetCollege.getId()).stream()
                        .map(Indicator::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

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

            ImportAction action;
            String businessKey;
            // 2026-10-19：与战略任务导入同口径——序号 → 内部ID 映射优先，映射为空退回名称匹配
            Integer seq = normalized.seq();
            Long mappedId = (seq == null || sequenceMap == null) ? null : sequenceMap.get(seq);
            if (sequenceMap != null && !sequenceMap.isEmpty()) {
                if (mappedId != null && relatedChildrenIds.contains(mappedId)) {
                    businessKey = "seq:" + seq + ":" + mappedId;
                    action = ImportAction.UPDATE;
                } else if (mappedId != null) {
                    businessKey = "seq:" + seq;
                    errors.add("序号 " + seq + " 对应的子指标已变化（可能已被删除），请重新导出表格后再导入");
                    action = ImportAction.ERROR;
                } else {
                    businessKey = "seq:" + seq;
                    action = ImportAction.CREATE;
                }
            } else {
                businessKey = distributionBusinessKey(cycleId, sourceOrg.getId(), targetCollege.getId(), normalized);
                action = existingChildren.containsKey(businessKey) ? ImportAction.UPDATE : ImportAction.CREATE;
            }
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

    private CommitCounters commitStrategic(PreviewContext context,
                                           ConflictMode conflictMode,
                                           CurrentUser currentUser) {
        SysOrg currentOrg = requireOrg(currentUser.getOrgId(), "当前组织不存在");
        SysOrg targetOrg = requireOrg(context.targetOrgId(), "目标职能部门不存在");
        Plan plan = findOrCreatePlan(context.cycleId(), PlanLevel.STRAT_TO_FUNC, currentOrg.getId(), targetOrg.getId());
        CommitCounter counter = new CommitCounter(plan);

        Set<Long> coveredIds = new HashSet<>();
        for (ImportRowPreview row : context.response().rows()) {
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

            // 2026-10-19 用户定案：序号 → 内部ID 映射决定更新目标（同名指标各按各的序号归属）。
            // 映射为空（老客户端兜底）时退回「任务+名称」匹配。
            Indicator target = null;
            if (row.action() == ImportAction.UPDATE) {
                target = resolveUpdateTarget(context, normalized, task.getId(), null);
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
                        // 2026-10-28：更新既有指标时若本次留空权重，同样落「待分配」标记，
                        // 否则 updateIndicator 会保留旧权重，「留空由系统自动分配」对既有行完全无效。
                        defaultWeight(normalized.weight()),
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
                        null,
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
     * 解析 UPDATE 行的更新目标：
     * ① 序号映射（上下文缓存的 序号→内部ID）命中且指标仍在 → 命中目标；
     * ② 映射为空（老客户端兜底）→ 退回「任务+名称（+父指标）」匹配。
     */
    private Indicator resolveUpdateTarget(PreviewContext context,
                                          NormalizedImportRow normalized,
                                          Long taskId,
                                          Long parentIndicatorId) {
        Map<Integer, Long> sequenceMap = context.sequenceMap();
        if (sequenceMap != null && !sequenceMap.isEmpty()) {
            Long mappedId = normalized.seq() == null ? null : sequenceMap.get(normalized.seq());
            if (mappedId == null) {
                return null;
            }
            return indicatorRepository.findById(mappedId)
                    .filter(indicator -> Boolean.FALSE.equals(indicator.getIsDeleted()))
                    .orElse(null);
        }
        return findIndicatorByTaskAndName(taskId, normalized.indicatorName(), parentIndicatorId).orElse(null);
    }

    private CommitCounters commitDistribution(PreviewContext context,
                                             ConflictMode conflictMode) {
        SysOrg currentOrg = requireOrg(context.sourceOrgId(), "来源职能部门不存在");
        SysOrg targetCollege = requireOrg(context.targetOrgId(), "目标学院不存在");
        Plan plan = findOrCreatePlan(context.cycleId(), PlanLevel.FUNC_TO_COLLEGE, currentOrg.getId(), targetCollege.getId());
        CommitCounter counter = new CommitCounter(plan);
        Set<Long> coveredIds = new HashSet<>();

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

            // 2026-10-19 用户定案：与战略任务导入同口径——序号映射决定更新目标，映射为空退回名称匹配
            Indicator target = null;
            if (row.action() == ImportAction.UPDATE) {
                target = resolveUpdateTarget(context, normalized, task.getId(), normalized.parentIndicatorId());
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
                        // 2026-10-28：学院链更新既有指标时若本次留空权重，同样落「待分配」标记，
                        // 否则 updateIndicator 会保留旧权重，「留空由系统自动分配」对既有行完全无效。
                        defaultWeight(normalized.weight()),
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
                        null,
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
                                            null,
                                            task == null ? "" : taskTypeLabel(task.getTaskType()),
                                            task == null ? "" : task.getName(),
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
                row.indicatorName(),
                row.indicatorType(),
                row.weight(),
                row.remark(),
                parentIndicatorId);
    }



    private TaskType toTaskType(String value) {
        return value != null && value.contains("基础") ? TaskType.BASIC : TaskType.DEVELOPMENT;
    }

    private String taskTypeLabel(TaskType taskType) {
        return taskType == TaskType.BASIC ? "基础性" : "发展性";
    }

    /**
     * 2026-10-28：权重留空时不再默认 100，而是落一个「待分配」极小值（0.01）。
     * Indicator.validate() 要求权重必须为正数，所以不能用 0；随后
     * {@link #normalizePlanWeights} 会把这类待分配指标统一纳入 100% 的分配中。
     */
    private static final BigDecimal PENDING_WEIGHT = new BigDecimal("0.01");

    /**
     * 2026-10-28：权重写入的最小值。表上有 {@code CHECK (weight_percent > 0)} 约束，
     * 任何写入都不能为 0 或负数，否则整条语句失败。
     */
    private static final BigDecimal MIN_WEIGHT = new BigDecimal("0.01");

    /** 包级可见以便单测：留空权重落「待分配」标记。 */
    static BigDecimal defaultWeight(BigDecimal weight) {
        return weight == null ? PENDING_WEIGHT : weight;
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
            // 2026-10-19：序号→内部ID 映射（前端按导出同款排序生成，预览时传入并缓存，确认导入时复用）
            Map<Integer, Long> sequenceMap,
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
