package com.sism.main.application;

import com.sism.execution.application.ReportApplicationService;
import com.sism.execution.domain.report.PlanReport;
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
 * 2026-11-25（指标填报导入式上报 · 学院侧）：编排服务。
 *
 * <p><b>业务定位</b>：学院填报人不再逐条点「填报」弹窗，而是把 Excel 填好后一次上传，
 * 由本服务完成「解析 → 定位 → 落填报明细 → 提交该归口线的上报审批」。
 *
 * <p><b>与既有导入下发链路（{@link BusinessImportApplicationService}）是两条独立链路</b>：
 * <ul>
 *   <li>下发：建线、下发子指标，落 {@code plan}/{@code indicator}；</li>
 *   <li>填报（本服务）：写本月月报的明细，落 {@code plan_report_indicator}（自评等级 + 进度说明）。</li>
 * </ul>
 *
 * <p><b>分组模型</b>：按文件内「归口职能部门」列拆组，每组 = 一份月报 = 一条归口线。
 * 这与系统既有的「学院上报按归口部门分批提交」模型一致（{@code plan_report} 的
 * {@code functional_org_id} 四元组唯一性），因此不新造数据模型。
 *
 * <p><b>阻断粒度</b>（2026-11-25 审查修正）：阻断下沉到<b>组级</b>——某条线有错只让该线不可提交，
 * 其余线照常提交，对齐模板「填写说明」第 4 条「一条线失败不影响其他线」与第 9 条
 * 「按归口线分组展示将提交 / 将跳过 / 错误组」。整单 only 在<b>没有任何可提交组</b>时阻断。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReportFillImportApplicationService {

    /** 2026-11-05：战略发展部 org id 兜底值（与既有导入服务同口径）。 */
    private static final Long DEFAULT_STRATEGY_ORG_ID = 35L;

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
    private final TransactionTemplate transactionTemplate;

    /** 预览批次上下文（batchId → 上下文）。 */
    private final Map<String, ReportFillPreviewContext> previews = new ConcurrentHashMap<>();

    /**
     * 预览批次上下文。
     *
     * <p>记录 {@code currentUserId} 用于 commit 时的<b>批次归属校验</b>——与既有导入下发链路
     * （{@code BusinessImportApplicationService} 的「只能确认自己上传的导入批次」）同口径，
     * 否则任何持 REPORTER 角色的用户拿到 batchId + confirmToken 就能代别人学院落库并提交审批。
     */
    private record ReportFillPreviewContext(
            String batchId,
            String confirmToken,
            Long currentUserId,
            Long cycleId,
            String reportMonth,
            Long collegeOrgId,
            String collegeName,
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
     * @param cycleId           考核周期 id（用于定位「学院 × 归口」的已下发计划线）
     * @param reportMonth       归属月份（yyyyMM 或 yyyy-MM）——模板不含月份列，由界面选择
     * @param mode              {@code "single"} = 单归口入口（必须给 functionalOrgId，只提交该线）；
     *                          {@code "all"}（默认）= 批量入口（文件内出现的归口线全部提交）。
     *                          2026-11-25（审查修正）：不再用「functionalOrgId 是否为 null」隐式表达模式
     *                          ——null 与「没传」无法区分，会让单归口静默退化成批量。
     * @param functionalOrgId   单归口入口选中的归口部门 id
     * @param sheetName         工作表名（可空）
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ReportFillPreviewResponse preview(MultipartFile file,
                                             Long cycleId,
                                             String reportMonth,
                                             String mode,
                                             Long functionalOrgId,
                                             String sheetName,
                                             CurrentUser currentUser) {
        if (currentUser == null || currentUser.getId() == null || currentUser.getOrgId() == null) {
            throw new SecurityException("未登录或登录已过期");
        }
        if (cycleId == null) {
            throw new IllegalArgumentException("考核周期不能为空");
        }
        boolean singleLineMode = isSingleLineMode(mode);
        if (singleLineMode && functionalOrgId == null) {
            // 服务端防线：前端隐藏/置灰不能替代服务端校验
            throw new IllegalArgumentException(
                    "单归口导入必须指定归属部门（所选归属部门尚未加载完成，请刷新后重试）");
        }
        String month = normalizeReportMonth(reportMonth);
        clearExpiredPreviews();

        SysOrg college = resolveTargetCollege(currentUser);
        ParsedWorkbook workbook = parser.parse(file, sheetName);
        if (workbook.rows() == null || workbook.rows().isEmpty()) {
            throw new IllegalArgumentException(
                    "文件内没有可导入的数据行（只有表头），请按模板填写自评等级与进度说明后再上传");
        }

        // 模板「填写说明」第 3 条：学院列必须等于登录人所在学院，不一致整单拒绝。
        assertCollegeColumnMatches(workbook.rows(), college.getName());

        List<ReportFillGroup> groups = buildGroups(
                workbook.rows(), cycleId, college, singleLineMode ? functionalOrgId : null);
        boolean hasSubmittableGroup = groups.stream().anyMatch(ReportFillGroup::willSubmit);

        String batchId = UUID.randomUUID().toString();
        String confirmToken = UUID.randomUUID().toString();
        previews.put(batchId, new ReportFillPreviewContext(
                batchId,
                confirmToken,
                currentUser.getId(),
                cycleId,
                month,
                college.getId(),
                college.getName(),
                groups,
                hasSubmittableGroup,
                LocalDateTime.now()));

        return new ReportFillPreviewResponse(
                batchId,
                cycleId,
                month,
                college.getId(),
                college.getName(),
                groups,
                hasSubmittableGroup,
                // 整单阻断 = 一条可提交的线都没有（单组出错不阻断整单）
                !hasSubmittableGroup,
                confirmToken);
    }

    /**
     * 解析导入模式。
     *
     * <p>2026-11-25（审查修正）：<b>必须白名单校验</b>。旧实现只认 {@code "single"}，
     * 其余任何字符串（含把 single 拼错成 singel）都静默按 {@code all} 处理——
     * 而 all 会把文件里出现的全部归口线一起提交，正是「单归口静默退化成批量」那个坑。
     * 未知值一律报错，不默认走更危险的路径。
     */
    private boolean isSingleLineMode(String mode) {
        String normalized = mode == null ? "" : mode.replace('\u3000', ' ').trim().toLowerCase();
        if (normalized.isEmpty() || "all".equals(normalized)) {
            return false;
        }
        if ("single".equals(normalized)) {
            return true;
        }
        throw new IllegalArgumentException(
                "导入模式只能是 single 或 all，收到：" + mode);
    }

    // ==================== 提交 ====================

    /**
     * 确认导入：逐归口线独立事务 —— 落填报明细 + 提交该线的上报审批。
     *
     * <p>一条线失败不影响其他线（与模板「填写说明」第 4/9 条一致）。
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
        // 2026-11-25（审查修正）：批次归属校验——与既有导入下发链路「只能确认自己上传的导入批次」同口径。
        // 否则任何持 REPORTER 角色的用户拿到 batchId + confirmToken 就能把填报写进别人学院的月报并提交审批。
        if (currentUser == null
                || !Objects.equals(currentUser.getId(), context.currentUserId())
                || !Objects.equals(currentUser.getOrgId(), context.collegeOrgId())) {
            throw new SecurityException("只能确认自己上传的导入批次");
        }
        // 2026-11-25（审查修正）：服务端也要守「没有任何可提交的归口线」这条线，
        // 前端置灰不能替代服务端校验（项目既有原则）。
        if (!context.submittable()) {
            throw new IllegalArgumentException(
                    "当前批次的全部归口线均不可提交，请按预览中的错误提示修正后重新上传");
        }

        List<ReportFillGroupResult> results = new ArrayList<>();
        int success = 0;
        int skipped = 0;
        int failed = 0;

        for (ReportFillGroup group : context.groups()) {
            ReportFillGroupResult result;
            try {
                result = transactionTemplate.execute(status -> commitGroup(group, context, request, currentUser));
            } catch (Exception ex) {
                log.warn("[ReportFillImport] 归口线提交失败: org={}, message={}",
                        group.functionalOrgName(), ex.getMessage());
                result = new ReportFillGroupResult(
                        group.functionalOrgId(),
                        group.functionalOrgName(),
                        ReportFillGroupStatus.FAILED,
                        null,
                        group.rowsCount(),
                        "导入失败：" + safeMessage(ex));
            }
            if (result == null) {
                result = new ReportFillGroupResult(
                        group.functionalOrgId(),
                        group.functionalOrgName(),
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

        // 2026-11-25（审查修正）：只有全部成功/跳过才移除批次。
        // 有失败时保留（受 TTL 约束），便于用户按结果排查后重试同一批次。
        if (failed == 0) {
            previews.remove(batchId);
        }

        return new ReportFillCommitResponse(results, new ReportFillCommitSummary(success, skipped, failed));
    }

    /**
     * 单个归口线的落库 + 提交（在一个事务内）。
     */
    private ReportFillGroupResult commitGroup(ReportFillGroup group,
                                               ReportFillPreviewContext context,
                                               ReportFillCommitRequest request,
                                               CurrentUser currentUser) {
        if (!group.willSubmit() || group.planId() == null) {
            String reason = group.groupError() != null
                    ? group.groupError()
                    : (group.skipReason() != null ? group.skipReason() : "该归口线不可导入，已跳过");
            return new ReportFillGroupResult(
                    group.functionalOrgId(),
                    group.functionalOrgName(),
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
                    group.functionalOrgId(),
                    group.functionalOrgName(),
                    ReportFillGroupStatus.SKIPPED,
                    null,
                    group.rowsCount(),
                    "该归口线没有可导入的有效行，已跳过");
        }

        // 1) 建/取本月月报草稿（学院 × 归口 = 一份月报）
        PlanReport report = reportApplicationService.createReport(
                context.reportMonth(),
                context.collegeOrgId(),
                ReportOrgType.COLLEGE,
                group.planId(),
                currentUser.getId(),
                group.functionalOrgId());

        // 2) 落填报明细（不触碰附件关联）
        reportApplicationService.upsertFillDetails(report.getId(), details, currentUser.getId());

        // 3) 提交该线的上报审批（走系统既有的上报审批流，不自动审批通过）
        reportApplicationService.submitReport(
                report.getId(),
                currentUser.getId(),
                request.comment(),
                currentUser);

        return new ReportFillGroupResult(
                group.functionalOrgId(),
                group.functionalOrgName(),
                ReportFillGroupStatus.SUCCESS,
                report.getId(),
                details.size(),
                "已导入 " + details.size() + " 条填报数据并提交上报审批");
    }

    // ==================== 分组与定位 ====================

    /**
     * 按「归口职能部门」列拆组，并在组内定位已下发的子指标。
     *
     * @param selectedFunctionalOrgId 单归口入口选中的归口；非空时其它归口的组只做展示、不提交
     */
    private List<ReportFillGroup> buildGroups(List<ParsedRow> rows,
                                              Long cycleId,
                                              SysOrg college,
                                              Long selectedFunctionalOrgId) {
        Map<String, List<ParsedRow>> byDeptName = new LinkedHashMap<>();
        for (ParsedRow row : rows) {
            String key = row.functionalDept() == null ? "" : row.functionalDept().trim();
            byDeptName.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }

        List<ReportFillGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<ParsedRow>> entry : byDeptName.entrySet()) {
            String deptName = entry.getKey();
            List<ParsedRow> deptRows = entry.getValue();

            List<ReportFillRow> enriched = new ArrayList<>();
            List<SysOrg> deptMatches = matchFunctionalOrgs(deptName);
            Long functionalOrgId = deptMatches.size() == 1 ? deptMatches.get(0).getId() : null;

            Plan linePlan = null;
            Map<String, List<Indicator>> indicatorByName = Map.of();
            String groupError = null;
            String skipReason = null;

            if (deptName.isEmpty()) {
                groupError = "归口职能部门不能为空，无法确定导入到哪条线";
            } else if (deptMatches.isEmpty()) {
                groupError = "找不到归口职能部门「" + deptName + "」，请核对名称";
            } else if (deptMatches.size() > 1) {
                // 同名多个职能部门：不可静默取第一个——选错会把内容写进别条线并提交审批
                groupError = "归口职能部门「" + deptName + "」在系统中有多个同名组织，无法确定归口线，请联系管理员核对";
            } else if (selectedFunctionalOrgId != null
                    && !Objects.equals(selectedFunctionalOrgId, functionalOrgId)) {
                // 单归口入口：只提交选中的那条线，其余线仅展示
                skipReason = "不属于所选的归属部门（本次不提交）";
            } else {
                linePlan = findLinePlan(cycleId, college.getId(), functionalOrgId);
                if (linePlan == null) {
                    groupError = "本考核周期未找到（" + college.getName() + " × " + deptName
                            + "）的下发计划，请先确认指标已下发给该学院";
                } else {
                    indicatorByName = loadLineIndicators(linePlan, college.getId());
                }
            }

            boolean skipLocate = groupError != null || skipReason != null;
            for (ParsedRow row : deptRows) {
                enriched.add(toPreviewRow(row, indicatorByName, !skipLocate));
            }
            // 同一线内同一子指标重复出现（复制粘贴常见）→ 行级点名，避免后者静默覆盖前者
            markDuplicateIndicatorRows(enriched);

            boolean hasRowError = enriched.stream().anyMatch(ReportFillRow::hasErrors);
            boolean willSubmit = groupError == null && skipReason == null && !hasRowError && linePlan != null;
            if (groupError == null && skipReason == null && hasRowError) {
                groupError = "该归口线存在无法定位或不合法的行，请修正后重新上传";
            }

            groups.add(new ReportFillGroup(
                    functionalOrgId,
                    deptName,
                    linePlan == null ? null : linePlan.getId(),
                    deptRows.size(),
                    willSubmit,
                    groupError,
                    skipReason,
                    enriched));
        }
        return groups;
    }

    /**
     * 把一行解析结果组装成预览行：列级校验 + 系统侧定位。
     *
     * @param locate false = 不做指标定位（组级已判错或该组将跳过）。
     *               2026-11-25（审查修正）：旧实现无条件定位，导致「将跳过」的组每行都追加
     *               「系统里没有找到子指标「X」」——用户看到组头灰「将跳过」、组内行却全红失败，
     *               真实原因（不属于所选归口）被淹没。
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
                    row.college(),
                    row.functionalDept(),
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
            errors.add("子指标「" + row.indicatorName() + "」在本归口线下存在多个同名项，无法定位，请改用唯一名称");
        } else {
            indicatorId = candidates.get(0).getId();
            matchedName = candidates.get(0).getIndicatorDesc();
        }

        return new ReportFillRow(
                row.rowNo(),
                row.college(),
                row.functionalDept(),
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

    /**
     * 同一归口线内同一子指标出现多行 → 给第二行及以后追加行级错误。
     *
     * <p>落库是逐条 upsert，重复行会静默覆盖前一行，但结果仍报「已导入 N 条」——用户以为都写进去了。
     */
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
                    row.college(),
                    row.functionalDept(),
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

    /**
     * 按「学院 × 归口职能部门」查已下发的计划线。
     */
    private Plan findLinePlan(Long cycleId, Long collegeOrgId, Long functionalOrgId) {
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
     * 取该计划线下、归属该学院的子指标，按名称归一化分组。
     */
    private Map<String, List<Indicator>> loadLineIndicators(Plan plan, Long collegeOrgId) {
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
                .filter(indicator -> indicator.getParentIndicatorId() != null)
                .filter(indicator -> indicator.getTargetOrg() != null
                        && Objects.equals(indicator.getTargetOrg().getId(), collegeOrgId))
                .filter(indicator -> !Boolean.TRUE.equals(indicator.getIsDeleted()))
                .collect(Collectors.groupingBy(
                        indicator -> normalizeKey(indicator.getIndicatorDesc()),
                        Collectors.toList()));
    }

    /**
     * 归口部门名 → 候选组织（只认职能部门类型；同名多条原样返回，由调用方判错）。
     */
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
     * 解析目标学院：<b>恒为登录人所在组织</b>，且必须是二级学院。
     *
     * <p>2026-11-25 审查修正：原实现允许系统管理员通过入参代其他学院填报，且对管理员跳过
     * 组织类型校验——模板「填写说明」第 1 条明确「导入的填报数据归属 = 登录人所在学院
     * （不可代其他学院填报）」，这不是本轮授权的能力，且会造成「created_by 是管理员、
     * 审批人无法分辨真实填报人」的审计黑洞，故整段删除。
     */
    private SysOrg resolveTargetCollege(CurrentUser currentUser) {
        SysOrg org = organizationRepository.findById(currentUser.getOrgId())
                .orElseThrow(() -> new IllegalArgumentException("当前登录账号未关联组织，无法导入填报数据"));
        if (org.getType() != OrgType.academic) {
            throw new SecurityException("只有二级学院填报人可以使用导入式上报");
        }
        return org;
    }

    /**
     * 模板「填写说明」第 3 条：学院列与登录学院不一致 → <b>整单拒绝</b>。
     *
     * <p>空值不在此处整单拒（降级为行级错误「学院不能为空」，该行所在组因此不可提交），
     * 目的是让填报人一眼看到是「漏填」而不是「填错学院」。
     */
    private void assertCollegeColumnMatches(List<ParsedRow> rows, String collegeName) {
        List<String> mismatched = new ArrayList<>();
        for (ParsedRow row : rows) {
            String value = row.college() == null ? "" : row.college().trim();
            if (value.isEmpty()) {
                continue;
            }
            if (!normalizeKey(value).equals(normalizeKey(collegeName))) {
                mismatched.add("第 " + row.rowNo() + " 行「" + value + "」");
            }
        }
        if (!mismatched.isEmpty()) {
            throw new IllegalArgumentException(
                    "「学院」列必须填写登录人所在学院（" + collegeName + "），以下行与登录账号不一致，整单拒绝导入："
                            + String.join("、", mismatched));
        }
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
     * <p>2026-11-25（审查修正）：{@code \s} 与 {@code trim()} 都不处理全角空格 U+3000，
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
