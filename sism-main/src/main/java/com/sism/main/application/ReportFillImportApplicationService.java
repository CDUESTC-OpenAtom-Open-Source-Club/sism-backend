package com.sism.main.application;

import com.sism.execution.application.ReportApplicationService;
import com.sism.execution.domain.report.PlanReport;
import com.sism.execution.domain.report.PlanReportRepository;
import com.sism.execution.domain.report.ReportOrgType;
import com.sism.execution.interfaces.dto.UpdatePlanReportIndicatorDetailRequest;
import com.sism.main.application.ReportFillImportParser.ParsedRow;
import com.sism.main.application.ReportFillImportParser.ParsedWorkbook;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillCommitRequest;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillCommitResponse;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillCommitSummary;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillGroup;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillGroupResult;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillGroupStatus;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillLevel;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillPreviewResponse;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillRow;
import com.sism.organization.domain.OrgType;
import com.sism.organization.domain.OrganizationRepository;
import com.sism.organization.domain.SysOrg;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import com.sism.strategy.domain.repository.IndicatorRepository;
import com.sism.strategy.domain.repository.PlanRepository;
import com.sism.strategy.infrastructure.StrategyOrgProperties;
import com.sism.task.domain.repository.TaskRepository;
import com.sism.task.domain.task.StrategicTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 2026-11-25（指标填报导入式上报）：编排服务。
 *
 * <p><b>覆盖两条上报流向</b>（标准模板 04/05/06，2026-11-25 定稿）：
 * <ul>
 *   <li><b>04 职能部门上报</b>：责任部门（职能部门）→ 上报目标（战略发展部）。只有一条线；
 *       定位键 = 责任部门 + 子指标名称（目标部门不参与，因为职能部门→战略部恒为一条）；
 *       计划 = {@code STRAT_TO_FUNC}（战略部 → 该部门）；填报对象 = 该计划下的<b>核心指标</b>
 *       （{@code parentIndicatorId == null}，targetOrg = 该部门）。</li>
 *   <li><b>05/06 学院上报</b>：责任部门（学院）→ 目标部门（归口职能部门）。按目标部门拆线，
 *       每条线独立；定位键 = 责任部门 + 目标部门 + 子指标名称；
 *       计划 = {@code FUNC_TO_COLLEGE}（线身份含归口部门）；填报对象 = 该计划下的<b>子指标</b>
 *       （{@code parentIndicatorId != null}，targetOrg = 该学院）。</li>
 * </ul>
 *
 * <p><b>与既有导入下发链路（{@link BusinessImportApplicationService}）完全独立</b>：
 * 本服务只写 {@code plan_report_indicator}（自评等级 + 进度说明），不建线、不下发指标。
 *
 * <p><b>阻断粒度</b>：下沉到组级——某条线有错只让该线不可提交，其余线照常提交
 * （对齐模板「一条线失败不影响其他线」）。整单只在<b>没有任何可提交组</b>时阻断。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReportFillImportApplicationService {

    /** 2026-11-05：战略发展部 org id 兜底值（与既有导入服务同口径）。 */
    private static final Long DEFAULT_STRATEGY_ORG_ID = 35L;

    /** 上报流向（由登录人组织类型决定）。 */
    private enum ReportFlow {
        /** 04：职能部门 → 战略发展部 */
        FUNC_DEPT,
        /** 05/06：学院 → 归口职能部门 */
        COLLEGE
    }

    /**
     * 预览批次的存活时间。
     *
     * <p>批次保存在单例内存里，只有 commit 成功才移除；用户解析后不提交的批次若不清理，
     * 会随使用缓慢累积（每条还持有整份文件的行对象）。这里给 TTL 并在每次 preview / commit
     * 时顺带清理过期条目。
     */
    private static final long PREVIEW_TTL_MINUTES = 30L;

    private final ReportFillImportParser parser;
    private final OrganizationRepository organizationRepository;
    private final PlanRepository planRepository;
    private final TaskRepository taskRepository;
    private final IndicatorRepository indicatorRepository;
    private final StrategyOrgProperties strategyOrgProperties;
    private final ReportApplicationService reportApplicationService;
    /** 同月同线已有填报查询（覆盖/跳过冲突模式用）。 */
    private final PlanReportRepository planReportRepository;
    private final TransactionTemplate transactionTemplate;

    /** 预览批次上下文（batchId → 上下文）。 */
    private final Map<String, ReportFillPreviewContext> previews = new ConcurrentHashMap<>();

    /**
     * 预览批次上下文。
     *
     * <p>记录 {@code currentUserId} 用于 commit 时的<b>批次归属校验</b>——与既有导入下发链路
     * 「只能确认自己上传的导入批次」同口径，否则任何持 REPORTER 角色的用户拿到
     * batchId + confirmToken 就能代别人落库并提交审批。
     */
    private record ReportFillPreviewContext(
            String batchId,
            String confirmToken,
            Long currentUserId,
            ReportFlow flow,
            Long cycleId,
            String reportMonth,
            Long responsibleOrgId,
            String responsibleOrgName,
            List<ReportFillGroup> groups,
            boolean submittable,
            LocalDateTime createdAt
    ) {
        boolean expired(LocalDateTime now) {
            return createdAt != null && createdAt.plusMinutes(PREVIEW_TTL_MINUTES).isBefore(now);
        }
    }

    // ==================== 预览 ====================

    /**
     * 解析预览。
     *
     * @param file              上传的填报导入 Excel
     * @param cycleId           考核周期 id
     * @param reportMonth       归属月份（yyyyMM 或 yyyy-MM）——模板不含月份列，由界面选择
     * @param mode              {@code "single"} = 单归口入口（必须给 targetOrgId，只提交该线）；
     *                          {@code "all"}（默认）= 批量 / 职能部门单点入口
     * @param targetOrgId       单归口入口选中的目标部门（归口职能部门）id
     * @param sheetName         工作表名（可空）
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ReportFillPreviewResponse preview(MultipartFile file,
                                             Long cycleId,
                                             String reportMonth,
                                             String mode,
                                             Long targetOrgId,
                                             String sheetName,
                                             CurrentUser currentUser) {
        if (currentUser == null || currentUser.getId() == null || currentUser.getOrgId() == null) {
            throw new SecurityException("未登录或登录已过期");
        }
        if (cycleId == null) {
            throw new IllegalArgumentException("考核周期不能为空");
        }
        boolean singleLineMode = isSingleLineMode(mode);
        String month = normalizeReportMonth(reportMonth);
        clearExpiredPreviews();

        SysOrg responsibleOrg = resolveResponsibleOrg(currentUser);
        ReportFlow flow = resolveFlow(responsibleOrg);
        if (flow == ReportFlow.COLLEGE && singleLineMode && targetOrgId == null) {
            // 服务端防线：前端隐藏/置灰不能替代服务端校验
            throw new IllegalArgumentException(
                    "单归口导入必须指定目标部门（所选归属部门尚未加载完成，请刷新后重试）");
        }

        ParsedWorkbook workbook = parser.parse(file, sheetName);
        if (workbook.rows() == null || workbook.rows().isEmpty()) {
            throw new IllegalArgumentException(
                    "文件内没有可导入的数据行（只有表头），请按模板填写自评等级与进度说明后再上传");
        }

        // 责任部门列必须等于登录人所在组织（防代他人填报），不一致整单拒绝
        assertResponsibleDeptMatches(workbook.rows(), responsibleOrg.getName());

        List<ReportFillGroup> groups = flow == ReportFlow.COLLEGE
                ? buildCollegeGroups(workbook.rows(), cycleId, responsibleOrg,
                        singleLineMode ? targetOrgId : null, month)
                : buildFunctionalGroups(workbook.rows(), cycleId, responsibleOrg, month);
        boolean hasSubmittableGroup = groups.stream().anyMatch(ReportFillGroup::willSubmit);

        String batchId = UUID.randomUUID().toString();
        String confirmToken = UUID.randomUUID().toString();
        previews.put(batchId, new ReportFillPreviewContext(
                batchId,
                confirmToken,
                currentUser.getId(),
                flow,
                cycleId,
                month,
                responsibleOrg.getId(),
                responsibleOrg.getName(),
                groups,
                hasSubmittableGroup,
                LocalDateTime.now()));

        return new ReportFillPreviewResponse(
                flow.name(),
                batchId,
                cycleId,
                month,
                responsibleOrg.getId(),
                responsibleOrg.getName(),
                groups,
                hasSubmittableGroup,
                !hasSubmittableGroup,
                confirmToken);
    }

    /**
     * 解析导入模式。
     *
     * <p><b>必须白名单校验</b>：旧实现只认 {@code "single"}，其余任何字符串（含把 single 拼错成 singel）
     * 都静默按 {@code all} 处理——而 all 会把文件里出现的全部线一起提交。
     */
    private boolean isSingleLineMode(String mode) {
        String normalized = mode == null ? "" : mode.replace('\u3000', ' ').trim().toLowerCase();
        if (normalized.isEmpty() || "all".equals(normalized)) {
            return false;
        }
        if ("single".equals(normalized)) {
            return true;
        }
        throw new IllegalArgumentException("导入模式只能是 single 或 all，收到：" + mode);
    }

    // ==================== 提交 ====================

    /**
     * 确认导入：逐线独立事务 —— 落填报明细 + 提交该线的上报审批。
     */
    public ReportFillCommitResponse commit(String batchId,
                                           ReportFillCommitRequest request,
                                           CurrentUser currentUser) {
        clearExpiredPreviews();
        ReportFillPreviewContext context = previews.get(batchId);
        if (context == null) {
            throw new IllegalArgumentException("导入批次不存在或已过期，请重新上传文件");
        }
        if (request == null || request.confirmToken() == null
                || !request.confirmToken().equals(context.confirmToken())) {
            throw new IllegalArgumentException("确认令牌无效或已过期，请重新解析预览");
        }
        // 批次归属校验——与既有导入下发链路「只能确认自己上传的导入批次」同口径
        if (currentUser == null
                || !Objects.equals(currentUser.getId(), context.currentUserId())
                || !Objects.equals(currentUser.getOrgId(), context.responsibleOrgId())) {
            throw new SecurityException("只能确认自己上传的导入批次");
        }
        // 服务端也要守「没有任何可提交的线」这条线（前端置灰不能替代服务端校验）
        if (!context.submittable()) {
            throw new IllegalArgumentException(
                    "当前批次的全部上报线均不可提交，请按预览中的错误提示修正后重新上传");
        }

        List<ReportFillGroupResult> results = new ArrayList<>();
        int success = 0;
        int skipped = 0;
        int failed = 0;
        String conflictMode = normalizeConflictMode(request.conflictMode());

        for (ReportFillGroup group : context.groups()) {
            ReportFillGroupResult result;
            try {
                result = transactionTemplate.execute(status -> commitGroup(group, context, request, currentUser, conflictMode));
            } catch (Exception ex) {
                log.warn("[ReportFillImport] 上报线提交失败: target={}, message={}",
                        group.targetOrgName(), ex.getMessage());
                result = new ReportFillGroupResult(
                        group.targetOrgId(),
                        group.targetOrgName(),
                        ReportFillGroupStatus.FAILED,
                        null,
                        group.rowsCount(),
                        "导入失败：" + safeMessage(ex));
            }
            if (result == null) {
                result = new ReportFillGroupResult(
                        group.targetOrgId(),
                        group.targetOrgName(),
                        ReportFillGroupStatus.FAILED,
                        null,
                        group.rowsCount(),
                        "导入失败：事务未返回结果");
            }
            results.add(result);
            switch (result.status()) {
                case SUCCESS -> success++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
        }

        // 只有全部成功/跳过才移除批次；有失败时保留（受 TTL 约束），便于按结果排查
        if (failed == 0) {
            previews.remove(batchId);
        }

        return new ReportFillCommitResponse(results, new ReportFillCommitSummary(success, skipped, failed));
    }

    /** 单条上报线的落库 + 提交（在一个事务内）。 */
    private ReportFillGroupResult commitGroup(ReportFillGroup group,
                                               ReportFillPreviewContext context,
                                               ReportFillCommitRequest request,
                                               CurrentUser currentUser,
                                               String conflictMode) {
        if (!group.willSubmit() || group.planId() == null) {
            String reason = group.groupError() != null
                    ? group.groupError()
                    : (group.skipReason() != null ? group.skipReason() : "该上报线不可导入，已跳过");
            return new ReportFillGroupResult(
                    group.targetOrgId(),
                    group.targetOrgName(),
                    ReportFillGroupStatus.SKIPPED,
                    null,
                    group.rowsCount(),
                    reason);
        }

        List<UpdatePlanReportIndicatorDetailRequest> details = group.rows().stream()
                .filter(row -> row.indicatorId() != null && !row.hasErrors())
                .map(row -> {
                    UpdatePlanReportIndicatorDetailRequest detail = new UpdatePlanReportIndicatorDetailRequest();
                    detail.setIndicatorId(row.indicatorId());
                    // 等级制填报：百分比恒为 0（与既有填报弹窗一致）
                    detail.setProgress(0);
                    detail.setSelfRating(row.level());
                    detail.setContent(row.remark());
                    return detail;
                })
                .toList();
        if (details.isEmpty()) {
            return new ReportFillGroupResult(
                    group.targetOrgId(),
                    group.targetOrgName(),
                    ReportFillGroupStatus.SKIPPED,
                    null,
                    group.rowsCount(),
                    "该上报线没有可导入的有效行，已跳过");
        }

        ReportOrgType reportOrgType = context.flow() == ReportFlow.COLLEGE
                ? ReportOrgType.COLLEGE
                : ReportOrgType.FUNC_DEPT;
        // 学院：月报按 (月, 学院, 归口部门) 隔离；职能部门：无归口概念，functionalOrgId 传 null
        Long functionalOrgId = context.flow() == ReportFlow.COLLEGE ? group.targetOrgId() : null;

        // 1) 同月同线已有填报：按冲突模式决定覆盖 / 跳过（默认 SKIP，不覆盖）
        PlanReport existing = findExistingLineReport(
                group.planId(), context.reportMonth(), reportOrgType,
                context.responsibleOrgId(), functionalOrgId);
        if (existing != null && "SKIP".equals(conflictMode)) {
            return new ReportFillGroupResult(
                    group.targetOrgId(),
                    group.targetOrgName(),
                    ReportFillGroupStatus.SKIPPED,
                    null,
                    group.rowsCount(),
                    "本月该上报线已有填报（report " + existing.getId() + "，状态 "
                            + existing.getStatus() + "），已跳过；如需覆盖请勾选「覆盖已有数据」");
        }
        if (existing != null && existing.isSubmitted()) {
            return new ReportFillGroupResult(
                    group.targetOrgId(),
                    group.targetOrgName(),
                    ReportFillGroupStatus.SKIPPED,
                    null,
                    group.rowsCount(),
                    "该线上报审批中（report " + existing.getId() + "），请先撤回后再覆盖");
        }

        // 2) 建/取本月月报草稿：有已有填报且要求覆盖时复用该 report（重置草稿后重新提交审批）
        PlanReport report;
        if (existing != null) {
            existing.resetToDraft(currentUser.getId());
            existing.setUpdatedAt(LocalDateTime.now());
            report = planReportRepository.save(existing);
        } else {
            report = reportApplicationService.createReport(
                    context.reportMonth(),
                    context.responsibleOrgId(),
                    reportOrgType,
                    group.planId(),
                    currentUser.getId(),
                    functionalOrgId);
        }

        // 2) 落填报明细（不触碰附件关联）
        reportApplicationService.upsertFillDetails(report.getId(), details, currentUser.getId());

        // 3) 提交该线的上报审批（走系统既有的上报审批流，不自动审批通过）
        reportApplicationService.submitReport(
                report.getId(),
                currentUser.getId(),
                request.comment(),
                currentUser);

        return new ReportFillGroupResult(
                group.targetOrgId(),
                group.targetOrgName(),
                ReportFillGroupStatus.SUCCESS,
                report.getId(),
                details.size(),
                "已导入 " + details.size() + " 条填报数据并提交上报审批");
    }

    // ==================== 学院上报（05/06）：按目标部门拆线 ====================

    private List<ReportFillGroup> buildCollegeGroups(List<ParsedRow> rows,
                                                     Long cycleId,
                                                     SysOrg college,
                                                     Long selectedTargetOrgId,
                                                     String reportMonth) {
        Map<String, List<ParsedRow>> byTargetName = new LinkedHashMap<>();
        for (ParsedRow row : rows) {
            String key = row.targetDept() == null ? "" : row.targetDept().trim();
            byTargetName.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }

        List<ReportFillGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<ParsedRow>> entry : byTargetName.entrySet()) {
            String targetName = entry.getKey();
            List<ParsedRow> targetRows = entry.getValue();

            List<ReportFillRow> enriched = new ArrayList<>();
            List<SysOrg> matches = matchFunctionalOrgs(targetName);
            Long targetOrgId = matches.size() == 1 ? matches.get(0).getId() : null;

            Plan linePlan = null;
            Map<String, List<Indicator>> indicatorByName = Map.of();
            String groupError = null;
            String skipReason = null;

            if (targetName.isEmpty()) {
                groupError = "目标部门（归口职能部门）不能为空，无法确定导入到哪条线";
            } else if (matches.isEmpty()) {
                groupError = "找不到目标部门「" + targetName + "」，请核对名称";
            } else if (matches.size() > 1) {
                groupError = "目标部门「" + targetName + "」在系统中有多个同名组织，无法确定上报线，请联系管理员核对";
            } else if (selectedTargetOrgId != null && !Objects.equals(selectedTargetOrgId, targetOrgId)) {
                skipReason = "不属于所选的目标部门（本次不提交）";
            } else {
                linePlan = findCollegeLinePlan(cycleId, college.getId(), targetOrgId);
                if (linePlan == null) {
                    groupError = "本考核周期未找到（" + college.getName() + " × " + targetName
                            + "）的下发计划，请先确认指标已下发给该学院";
                } else {
                    indicatorByName = loadLineIndicators(linePlan, college.getId(), true);
                }
            }

            Long existingReportId = null;
            String existingReportStatus = null;
            if (groupError == null && skipReason == null && linePlan != null) {
                PlanReport existing = findExistingLineReport(
                        linePlan.getId(), reportMonth, ReportOrgType.COLLEGE, college.getId(), targetOrgId);
                if (existing != null) {
                    if (existing.isSubmitted()) {
                        groupError = "该线上报审批中（report " + existing.getId() + "），请先撤回后再导入";
                    } else {
                        existingReportId = existing.getId();
                        existingReportStatus = existing.getStatus();
                    }
                }
            }

            boolean skipLocate = groupError != null || skipReason != null;
            for (ParsedRow row : targetRows) {
                enriched.add(toPreviewRow(row, indicatorByName, !skipLocate));
            }
            markDuplicateIndicatorRows(enriched);

            boolean hasRowError = enriched.stream().anyMatch(ReportFillRow::hasErrors);
            boolean willSubmit = groupError == null && skipReason == null && !hasRowError && linePlan != null;
            if (groupError == null && skipReason == null && hasRowError) {
                groupError = "该上报线存在无法定位或不合法的行，请修正后重新上传";
            }

            groups.add(new ReportFillGroup(
                    targetOrgId,
                    targetName,
                    linePlan == null ? null : linePlan.getId(),
                    targetRows.size(),
                    willSubmit,
                    groupError,
                    skipReason,
                    existingReportId,
                    existingReportStatus,
                    enriched));
        }
        return groups;
    }

    // ==================== 职能部门上报（04）：单线 ====================

    private List<ReportFillGroup> buildFunctionalGroups(List<ParsedRow> rows,
                                                        Long cycleId,
                                                        SysOrg dept,
                                                        String reportMonth) {
        String strategyName = resolveOrgName(strategyOrgId());
        List<ReportFillRow> enriched = new ArrayList<>();

        Plan plan = planRepository
                .findByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgId(
                        cycleId, PlanLevel.STRAT_TO_FUNC, strategyOrgId(), dept.getId())
                .orElse(null);

        String groupError = null;
        Map<String, List<Indicator>> indicatorByName = Map.of();
        if (plan == null) {
            groupError = "本考核周期未找到（战略发展部 → " + dept.getName() + "）的下发计划，请先确认指标已下发";
        } else {
            // 职能部门填报的是「核心指标」（无父级）
            indicatorByName = loadLineIndicators(plan, dept.getId(), false);
        }

        Long existingReportId = null;
        String existingReportStatus = null;
        if (groupError == null && plan != null) {
            PlanReport existing = findExistingLineReport(
                    plan.getId(), reportMonth, ReportOrgType.FUNC_DEPT, dept.getId(), null);
            if (existing != null) {
                if (existing.isSubmitted()) {
                    groupError = "该线上报审批中（report " + existing.getId() + "），请先撤回后再导入";
                } else {
                    existingReportId = existing.getId();
                    existingReportStatus = existing.getStatus();
                }
            }
        }

        // 目标部门校验：职能部门只能上报到战略发展部
        for (ParsedRow row : rows) {
            String target = row.targetDept() == null ? "" : row.targetDept().trim();
            if (!target.isEmpty() && !normalizeKey(target).equals(normalizeKey(strategyName))) {
                enriched.add(withTargetError(
                        toPreviewRow(row, indicatorByName, false),
                        "上报目标应为「" + strategyName + "」，实际填写「" + target + "」"));
                continue;
            }
            enriched.add(toPreviewRow(row, indicatorByName, groupError == null));
        }
        markDuplicateIndicatorRows(enriched);

        boolean hasRowError = enriched.stream().anyMatch(ReportFillRow::hasErrors);
        boolean willSubmit = groupError == null && !hasRowError;
        if (groupError == null && hasRowError) {
            groupError = "该上报线存在无法定位或不合法的行，请修正后重新上传";
        }

        List<ReportFillGroup> groups = new ArrayList<>();
        groups.add(new ReportFillGroup(
                strategyOrgId(),
                strategyName,
                plan == null ? null : plan.getId(),
                rows.size(),
                willSubmit,
                groupError,
                null,
                existingReportId,
                existingReportStatus,
                enriched));
        return groups;
    }

    /** 给一行追加一条错误（用于「上报目标不是战略发展部」这种行级校验）。 */
    private ReportFillRow withTargetError(ReportFillRow row, String message) {
        List<String> errors = new ArrayList<>(row.errors());
        errors.add(message);
        return new ReportFillRow(
                row.rowNo(),
                row.responsibleDept(),
                row.targetDept(),
                row.indicatorName(),
                row.weight(),
                row.selfRating(),
                row.level(),
                row.remark(),
                row.indicatorId(),
                row.matchedName(),
                errors,
                row.warnings());
    }

    // ==================== 行组装与定位 ====================

    /**
     * 把一行解析结果组装成预览行：列级校验 + 系统侧定位。
     *
     * @param locate false = 不做指标定位（组级已判错或该组将跳过），避免给出「找不到子指标」的误导性错误
     */
    private ReportFillRow toPreviewRow(ParsedRow row,
                                       Map<String, List<Indicator>> indicatorByName,
                                       boolean locate) {
        List<String> errors = new ArrayList<>(ReportFillImportParser.validateRowContent(row));
        List<String> warnings = new ArrayList<>(ReportFillImportParser.validateWeightHint(row));

        ReportFillLevel level = ReportFillLevel.fromLabel(row.selfRating());

        if (!errors.isEmpty() || !locate) {
            return new ReportFillRow(
                    row.rowNo(),
                    row.responsibleDept(),
                    row.targetDept(),
                    row.indicatorName(),
                    row.weight(),
                    row.selfRating(),
                    level == null ? null : level.name(),
                    row.remark(),
                    null,
                    null,
                    errors,
                    warnings);
        }

        Long indicatorId = null;
        String matchedName = null;
        List<Indicator> candidates = indicatorByName.get(normalizeKey(row.indicatorName()));
        if (candidates == null || candidates.isEmpty()) {
            errors.add("系统里没有找到子指标「" + row.indicatorName() + "」，请核对名称是否与下发的指标完全一致");
        } else if (candidates.size() > 1) {
            errors.add("子指标「" + row.indicatorName() + "」上报到同一目标下存在多个同名项，无法定位，请改用唯一名称");
        } else {
            indicatorId = candidates.get(0).getId();
            matchedName = candidates.get(0).getIndicatorDesc();
        }

        return new ReportFillRow(
                row.rowNo(),
                row.responsibleDept(),
                row.targetDept(),
                row.indicatorName(),
                row.weight(),
                row.selfRating(),
                level == null ? null : level.name(),
                row.remark(),
                indicatorId,
                matchedName,
                errors,
                warnings);
    }

    /** 同一上报线内同一子指标出现多行 → 给第二行及以后追加行级错误（避免静默覆盖）。 */
    private void markDuplicateIndicatorRows(List<ReportFillRow> rows) {
        Map<Long, Integer> firstRowNoByIndicator = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            ReportFillRow row = rows.get(i);
            if (row.indicatorId() == null) {
                continue;
            }
            Integer firstRowNo = firstRowNoByIndicator.putIfAbsent(row.indicatorId(), row.rowNo());
            if (firstRowNo == null) {
                continue;
            }
            List<String> errors = new ArrayList<>(row.errors());
            errors.add("与第 " + firstRowNo + " 行重复填报同一子指标，请删除其中一行");
            rows.set(i, new ReportFillRow(
                    row.rowNo(),
                    row.responsibleDept(),
                    row.targetDept(),
                    row.indicatorName(),
                    row.weight(),
                    row.selfRating(),
                    row.level(),
                    row.remark(),
                    row.indicatorId(),
                    row.matchedName(),
                    errors,
                    row.warnings()));
        }
    }

    /** 学院线：按 (cycle, FUNC_TO_COLLEGE, 战略部, 学院, 归口部门) 查。 */
    private Plan findCollegeLinePlan(Long cycleId, Long collegeOrgId, Long functionalOrgId) {
        return planRepository
                .findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgIdAndFunctionalOrgId(
                        cycleId,
                        PlanLevel.FUNC_TO_COLLEGE,
                        strategyOrgId(),
                        collegeOrgId,
                        functionalOrgId)
                .orElse(null);
    }

    /**
     * 查同月同线已存在的填报（用于冲突模式：覆盖 / 跳过）。
     *
     * <p>学院线按 (plan, 月, 学院, 归口部门) 四元组隔离；职能部门线无归口概念
     * （functionalOrgId 传 null，与既有 {@code createReport} 口径一致）。
     */
    private PlanReport findExistingLineReport(Long planId,
                                              String reportMonth,
                                              ReportOrgType reportOrgType,
                                              Long reportOrgId,
                                              Long functionalOrgId) {
        if (planId == null) {
            return null;
        }
        PlanReport existing = planReportRepository
                .findLatestByMonthlyScope(planId, reportMonth, reportOrgType.name(), reportOrgId, functionalOrgId)
                .filter(report -> !report.isDeleted())
                .orElse(null);
        log.debug("[ReportFillImport] existing lookup plan={}, month={}, type={}, org={}, func={} -> {}",
                planId, reportMonth, reportOrgType, reportOrgId, functionalOrgId,
                existing == null ? "none" : existing.getId() + "/" + existing.getStatus());
        return existing;
    }

    /**
     * 规范化冲突模式：空白 / 未传 = {@code SKIP}（不覆盖），其它值白名单校验后拒绝。
     */
    private static String normalizeConflictMode(String mode) {
        String normalized = mode == null ? "" : mode.replace('\u3000', ' ').trim().toUpperCase();
        if (normalized.isEmpty() || "SKIP".equals(normalized)) {
            return "SKIP";
        }
        if ("OVERWRITE".equals(normalized)) {
            return "OVERWRITE";
        }
        throw new IllegalArgumentException("冲突处理模式只能是 OVERWRITE 或 SKIP，收到：" + mode);
    }

    /**
     * 取该计划下归属该组织的指标，按名称归一化分组。
     *
     * @param childIndicators true = 取子指标（{@code parentIndicatorId != null}，学院上报）；
     *                        false = 取核心指标（{@code parentIndicatorId == null}，职能部门上报）
     */
    private Map<String, List<Indicator>> loadLineIndicators(Plan plan, Long targetOrgId, boolean childIndicators) {
        List<StrategicTask> tasks = taskRepository.findByPlanId(plan.getId());
        if (tasks.isEmpty()) {
            return Map.of();
        }
        List<Long> taskIds = tasks.stream()
                .map(StrategicTask::getId)
                .filter(Objects::nonNull)
                .toList();
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return indicatorRepository.findByTaskIds(taskIds).stream()
                .filter(indicator -> childIndicators
                        ? indicator.getParentIndicatorId() != null
                        : indicator.getParentIndicatorId() == null)
                .filter(indicator -> indicator.getTargetOrg() != null
                        && Objects.equals(indicator.getTargetOrg().getId(), targetOrgId))
                .filter(indicator -> !Boolean.TRUE.equals(indicator.getIsDeleted()))
                .collect(Collectors.groupingBy(
                        indicator -> normalizeKey(indicator.getIndicatorDesc()),
                        Collectors.toList()));
    }

    /** 目标部门名 → 候选归口职能部门（只认 functional 类型；同名多条原样返回，由调用方判错）。 */
    private List<SysOrg> matchFunctionalOrgs(String name) {
        if (name == null || name.trim().isEmpty()) {
            return List.of();
        }
        return organizationRepository.findByNameContaining(name.trim()).stream()
                .filter(org -> !Boolean.TRUE.equals(org.getIsDeleted()))
                .filter(org -> normalizeKey(org.getName()).equals(normalizeKey(name)))
                .filter(org -> org.getType() == OrgType.functional)
                .toList();
    }

    // ==================== 守卫与工具 ====================

    /**
     * 解析责任部门：<b>恒为登录人所在组织</b>，且必须是二级学院或职能部门。
     *
     * <p>模板「填写说明」明确「责任部门必须填写登录账号对应的部门，不一致整单拒绝导入
     * （防止代他部门/学院填报）」——所以不提供任何代填入口（管理员也不行）。
     */
    private SysOrg resolveResponsibleOrg(CurrentUser currentUser) {
        SysOrg org = organizationRepository.findById(currentUser.getOrgId())
                .orElseThrow(() -> new IllegalArgumentException("当前登录账号未关联组织，无法导入填报数据"));
        if (org.getType() != OrgType.academic && org.getType() != OrgType.functional) {
            throw new SecurityException("只有二级学院或职能部门填报人可以使用导入式上报");
        }
        return org;
    }

    private ReportFlow resolveFlow(SysOrg org) {
        return org.getType() == OrgType.academic ? ReportFlow.COLLEGE : ReportFlow.FUNC_DEPT;
    }

    /**
     * 责任部门列必须等于登录人所在组织，不一致 → <b>整单拒绝</b>。
     *
     * <p>空值不在此处整单拒（降级为行级错误「责任部门不能为空」，该行所在组因此不可提交），
     * 目的是让填报人一眼看到是「漏填」而不是「填错部门」。
     */
    private void assertResponsibleDeptMatches(List<ParsedRow> rows, String orgName) {
        List<String> mismatched = new ArrayList<>();
        for (ParsedRow row : rows) {
            String value = row.responsibleDept() == null ? "" : row.responsibleDept().trim();
            if (value.isEmpty()) {
                continue;
            }
            if (!normalizeKey(value).equals(normalizeKey(orgName))) {
                mismatched.add("第 " + row.rowNo() + " 行「" + value + "」");
            }
        }
        if (!mismatched.isEmpty()) {
            throw new IllegalArgumentException(
                    "「责任部门」列必须填写登录人所在部门/学院（" + orgName
                            + "），以下行与登录账号不一致，整单拒绝导入：" + String.join("、", mismatched));
        }
    }

    private String resolveOrgName(Long orgId) {
        return organizationRepository.findById(orgId)
                .map(SysOrg::getName)
                .orElse("");
    }

    private String normalizeReportMonth(String reportMonth) {
        String normalized = PlanReport.normalizeReportMonth(reportMonth);
        if (normalized == null) {
            throw new IllegalArgumentException("归属月份必须提供（yyyyMM 或 yyyy-MM）");
        }
        return normalized;
    }

    /** 清理过期预览批次（避免无界内存增长）。 */
    private void clearExpiredPreviews() {
        LocalDateTime now = LocalDateTime.now();
        previews.entrySet().removeIf(entry -> entry.getValue().expired(now));
    }

    private Long strategyOrgId() {
        Long configured = strategyOrgProperties.getStrategyOrgId();
        return configured == null ? DEFAULT_STRATEGY_ORG_ID : configured;
    }

    /**
     * 名称归一化：全角空格 → 半角、去全部空白、转小写。
     *
     * <p>{@code \s} 与 {@code trim()} 都不处理全角空格 U+3000，
     * 用户从网页/文档粘贴的「计算机学院　」会与系统名不一致 → 整单拒绝且看不见不可见字符。
     */
    private static String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u3000', ' ').trim().replaceAll("\\s+", "").toLowerCase();
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
