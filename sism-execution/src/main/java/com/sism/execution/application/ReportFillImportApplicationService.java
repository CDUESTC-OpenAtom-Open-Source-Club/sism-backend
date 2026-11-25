package com.sism.execution.application;

import com.sism.execution.domain.report.PlanReport;
import com.sism.execution.domain.report.PlanReportIndicatorRepository;
import com.sism.execution.domain.report.PlanReportIndicatorSnapshot;
import com.sism.execution.domain.report.PlanReportRepository;
import com.sism.execution.domain.report.ReportOrgType;
import com.sism.execution.interfaces.dto.ReportFillImportDtos;
import com.sism.execution.interfaces.dto.UpdatePlanReportIndicatorDetailRequest;
import com.sism.organization.domain.OrgType;
import com.sism.organization.domain.OrganizationRepository;
import com.sism.organization.domain.SysOrg;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.plan.Plan;
import com.sism.strategy.domain.plan.PlanLevel;
import com.sism.strategy.domain.repository.IndicatorRepository;
import com.sism.strategy.domain.repository.PlanRepository;
import com.sism.task.domain.repository.TaskRepository;
import com.sism.task.domain.task.StrategicTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2026-11-25（二级学院填报导入）：学院填报页「导入 / 批量导入」的应用服务。
 *
 * <p>导入只允许写「自评等级」与「进度说明」两列；其余列（序号/学院/归口职能部门/
 * 子指标名称/权重）解析时仅做校验。学院取自登录身份，模板里的「学院」列必须与之一致，
 * 避免一份模板被拿去改别的学院的数据。
 *
 * <p>预览阶段按「归口职能部门」分组，每组对应「学院 × 归口」的一条计划线；
 * 提交阶段逐组定位计划、创建（或复用）月度草稿，再批量写入指标明细。
 * 只落草稿，不发起审批——是否上报仍由填报人点「一键提交」决定。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportFillImportApplicationService {

    /** 战略发展部（代发通道）固定为计划创建方 */
    private static final Long STRATEGY_ORG_ID = 35L;

    /** 单次导入的最大数据行数，防止一份超大表把预览拖死 */
    private static final int MAX_DATA_ROWS = 500;

    /** 预览缓存有效期：超时后必须重新上传解析 */
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(30);

    /** 模板里自评等级允许的三种写法（含「延期」兼容老模板） */
    private static final String LEVEL_AHEAD = "AHEAD";
    private static final String LEVEL_NORMAL = "NORMAL";
    private static final String LEVEL_DELAYED = "DELAYED";

    private final OrganizationRepository organizationRepository;
    private final PlanRepository planRepository;
    private final TaskRepository taskRepository;
    private final IndicatorRepository indicatorRepository;
    private final PlanReportRepository planReportRepository;
    private final PlanReportIndicatorRepository planReportIndicatorRepository;
    private final ReportApplicationService reportApplicationService;

    /** batchId -> 已解析的预览结果（提交时只认这里，避免前端二次拼装） */
    private final Map<String, CachedPreview> previewCache = new ConcurrentHashMap<>();

    // ==================== 预览 ====================

    public ReportFillImportDtos.PreviewResponse preview(
            CurrentUser currentUser,
            MultipartFile file,
            Long cycleId,
            String reportMonth
    ) {
        SysOrg college = requireCollegeOperator(currentUser);
        Long resolvedCycleId = requireCycleId(cycleId);
        String month = normalizeReportMonth(reportMonth);
        ParsedSheet parsed = parseWorkbook(file);

        long startedAt = System.nanoTime();
        List<ReportFillImportDtos.PreviewGroup> groups = new ArrayList<>();
        List<ResolvedGroup> resolvedGroups = new ArrayList<>();
        List<String> fileErrors = new ArrayList<>();
        int validRows = 0;
        int skipRows = 0;
        int errorRows = 0;

        Map<String, SysOrg> functionalByName = loadFunctionalOrgsByName();
        Map<String, List<ParsedRow>> rowsByDept = groupRowsByFunctionalDept(parsed.rows());

        for (Map.Entry<String, List<ParsedRow>> entry : rowsByDept.entrySet()) {
            String deptKey = entry.getKey();
            List<ParsedRow> deptRows = entry.getValue();
            SysOrg dept = functionalByName.get(deptKey);

            List<ReportFillImportDtos.PreviewRow> previewRows = new ArrayList<>();
            List<ResolvedRow> resolvedRows = new ArrayList<>();
            List<String> groupErrors = new ArrayList<>();
            Long planId = null;
            String planStatus = null;
            Long existingReportId = null;
            String existingReportStatus = null;

            if (dept == null) {
                groupErrors.add("归口职能部门「" + displayName(deptKey) + "」不存在或不是职能部门");
            } else {
                Optional<Plan> plan = planRepository
                        .findActiveByCycleIdAndPlanLevelAndCreatedByOrgIdAndTargetOrgIdAndFunctionalOrgId(
                                resolvedCycleId, PlanLevel.FUNC_TO_COLLEGE, STRATEGY_ORG_ID,
                                college.getId(), dept.getId());
                if (plan.isEmpty()) {
                    groupErrors.add("该归口部门在 " + resolvedCycleId + " 考核年度没有已下发的计划线");
                } else {
                    planId = plan.get().getId();
                    planStatus = plan.get().getStatus();
                }
            }

            Map<String, Indicator> indicatorsByName = planId == null
                    ? Map.of()
                    : loadDistributedIndicatorsByName(planId, college.getId());

            Map<Long, PlanReportIndicatorSnapshot> existingDetails = Map.of();
            if (planId != null && dept != null) {
                Optional<PlanReport> existing = findScopeReports(planId, month, college.getId(), dept.getId())
                        .stream()
                        .max(Comparator.comparing(PlanReport::getId));
                if (existing.isPresent()) {
                    existingReportId = existing.get().getId();
                    existingReportStatus = existing.get().getStatus();
                    existingDetails = loadExistingDetails(existingReportId);
                }
            }

            for (ParsedRow row : deptRows) {
                List<String> rowErrors = new ArrayList<>();
                List<String> rowWarnings = new ArrayList<>();
                Long indicatorId = null;

                String collegeError = validateCollege(row, college);
                if (collegeError != null) {
                    rowErrors.add(collegeError);
                }

                String rating = normalizeRating(row.ratingRaw());
                if (rating == null) {
                    rowErrors.add("自评等级只能填「超前」「正常」「滞后」");
                }

                String description = row.description() == null ? "" : row.description().trim();
                if (description.isEmpty()) {
                    rowErrors.add("进度说明不能为空");
                } else if (description.length() > 500) {
                    rowErrors.add("进度说明超过 500 字");
                }

                Indicator matched = null;
                if (planId == null) {
                    rowErrors.add("所在归口未定位到计划线，无法匹配子指标");
                } else {
                    List<Indicator> candidates = indicatorsByName.get(normalizeKey(row.indicatorName())) == null
                            ? List.of()
                            : List.of(indicatorsByName.get(normalizeKey(row.indicatorName())));
                    if (candidates.isEmpty()) {
                        rowErrors.add("该归口线下未找到已下发子指标「" + safeText(row.indicatorName()) + "」");
                    } else {
                        matched = candidates.get(0);
                        indicatorId = matched.getId();
                    }
                }

                PlanReportIndicatorSnapshot existingDetail = indicatorId == null
                        ? null
                        : existingDetails.get(indicatorId);

                boolean unchanged = existingDetail != null
                        && Objects.equals(existingDetail.selfRating(), rating)
                        && Objects.equals(nullToEmpty(existingDetail.description()), description);

                ReportFillImportDtos.RowAction action;
                if (!rowErrors.isEmpty()) {
                    action = ReportFillImportDtos.RowAction.ERROR;
                    errorRows++;
                } else if (unchanged) {
                    action = ReportFillImportDtos.RowAction.SKIP;
                    skipRows++;
                } else {
                    action = ReportFillImportDtos.RowAction.UPDATE;
                    validRows++;
                }

                if (existingDetail != null && !unchanged && existingReportStatus != null) {
                    rowWarnings.add("将覆盖该月草稿中已填写的「" + safeText(existingDetail.description()) + "」");
                }

                previewRows.add(new ReportFillImportDtos.PreviewRow(
                        row.rowNo(),
                        indicatorId,
                        safeText(row.indicatorName()),
                        rating,
                        ratingLabel(rating),
                        description,
                        existingDetail == null ? null : existingDetail.selfRating(),
                        existingDetail == null ? null : existingDetail.description(),
                        safeText(row.weightRaw()),
                        action,
                        List.copyOf(rowErrors),
                        List.copyOf(rowWarnings)
                ));

                if (rowErrors.isEmpty() && action != ReportFillImportDtos.RowAction.SKIP) {
                    resolvedRows.add(new ResolvedRow(indicatorId, safeText(row.indicatorName()), rating, description));
                }
            }

            if (!groupErrors.isEmpty()) {
                for (ReportFillImportDtos.PreviewRow row : previewRows) {
                    if (row.action() != ReportFillImportDtos.RowAction.ERROR) {
                        errorRows++;
                    }
                }
                validRows = 0;
                skipRows = 0;
            }

            groups.add(new ReportFillImportDtos.PreviewGroup(
                    dept == null ? null : dept.getId(),
                    dept == null ? displayName(deptKey) : dept.getName(),
                    planId,
                    planStatus,
                    existingReportId,
                    existingReportStatus,
                    List.copyOf(previewRows),
                    List.copyOf(groupErrors)
            ));

            if (groupErrors.isEmpty() && !resolvedRows.isEmpty()) {
                resolvedGroups.add(new ResolvedGroup(
                        dept.getId(), dept.getName(), planId, existingReportId, List.copyOf(resolvedRows)));
            }
        }

        if (parsed.rows().isEmpty()) {
            fileErrors.add("表格里没有可导入的数据行");
        }
        if (parsed.rows().size() > MAX_DATA_ROWS) {
            fileErrors.add("单次最多导入 " + MAX_DATA_ROWS + " 行，请拆成多次导入");
        }

        List<ReportFillImportDtos.PreviewGroup> finalGroups = groups;
        int finalValid = validRows;
        int finalSkip = skipRows;
        int finalError = errorRows;
        if (!fileErrors.isEmpty()) {
            finalValid = 0;
            finalSkip = 0;
            finalError = parsed.rows().size();
        }

        String batchId = "RFI-" + UUID.randomUUID().toString().substring(0, 8);
        String confirmToken = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        previewCache.put(batchId, new CachedPreview(batchId, confirmToken, now, resolvedCycleId, month,
                college.getId(), college.getName(), List.copyOf(resolvedGroups),
                List.copyOf(fileErrors), now.plus(PREVIEW_TTL)));
        purgeExpiredPreviews(now);

        log.info("填报导入预览完成：batchId={}, college={}, cycle={}, month={}, rows={}, groups={}, valid={}, skip={}, error={}, cost={}ms",
                batchId, college.getName(), resolvedCycleId, month, parsed.rows().size(), finalGroups.size(),
                finalValid, finalSkip, finalError, (System.nanoTime() - startedAt) / 1_000_000);

        return new ReportFillImportDtos.PreviewResponse(
                batchId,
                parsed.fileName(),
                resolvedCycleId,
                month,
                college.getId(),
                college.getName(),
                new ReportFillImportDtos.PreviewSummary(parsed.rows().size(), finalValid, finalSkip, finalError),
                finalGroups,
                confirmToken
        );
    }

    // ==================== 提交 ====================

    public ReportFillImportDtos.CommitResponse commit(
            CurrentUser currentUser,
            String batchId,
            ReportFillImportDtos.CommitRequest request
    ) {
        SysOrg college = requireCollegeOperator(currentUser);
        CachedPreview cached = batchId == null ? null : previewCache.get(batchId);
        if (cached == null) {
            throw new IllegalArgumentException("导入批次不存在或已过期，请重新上传解析");
        }
        if (cached.expiresAt().isBefore(Instant.now())) {
            previewCache.remove(batchId);
            throw new IllegalArgumentException("导入批次已过期，请重新上传解析");
        }
        if (request != null && request.confirmToken() != null
                && !Objects.equals(request.confirmToken(), cached.confirmToken())) {
            throw new IllegalArgumentException("导入凭证不匹配，请重新上传解析");
        }
        if (!Objects.equals(cached.collegeOrgId(), college.getId())) {
            throw new AccessDeniedException("只能为自己所属学院导入填报数据");
        }
        if (!cached.fileErrors().isEmpty()) {
            throw new IllegalArgumentException("当前批次存在解析错误，无法提交：" + String.join("；", cached.fileErrors()));
        }
        if (cached.groups().isEmpty()) {
            throw new IllegalArgumentException("没有可提交的数据行，请检查自评等级与进度说明后再试");
        }

        List<ReportFillImportDtos.CommitGroupResult> results = new ArrayList<>();
        int totalSaved = 0;
        int blocked = 0;

        for (ResolvedGroup group : cached.groups()) {
            try {
                // 2026-11-25：已有草稿就续写，不新建——避免同线同月堆出多份草稿（列表只读最新一份，
                // 其余变孤儿）。已批准/已驳回的历史轮次仍交给 createReport 开新一轮。
                Optional<PlanReport> reusableDraft = findScopeReports(
                                group.planId(), cached.reportMonth(), college.getId(), group.functionalOrgId())
                        .stream()
                        .filter(candidate -> PlanReport.STATUS_DRAFT.equals(candidate.getStatus()))
                        .max(Comparator.comparing(PlanReport::getId));
                PlanReport report = reusableDraft.orElseGet(() -> reportApplicationService.createReport(
                        cached.reportMonth(),
                        college.getId(),
                        ReportOrgType.COLLEGE,
                        group.planId(),
                        currentUser.getId(),
                        group.functionalOrgId()
                ));

                List<UpdatePlanReportIndicatorDetailRequest> details = new ArrayList<>();
                for (ResolvedRow row : group.rows()) {
                    UpdatePlanReportIndicatorDetailRequest detail = new UpdatePlanReportIndicatorDetailRequest();
                    detail.setIndicatorId(row.indicatorId());
                    detail.setProgress(0);
                    detail.setContent(row.description());
                    detail.setSelfRating(row.selfRating());
                    details.add(detail);
                }

                reportApplicationService.updateReportBatch(
                        report.getId(), null, null, null, null, null, null, currentUser.getId(), details);

                totalSaved += details.size();
                results.add(new ReportFillImportDtos.CommitGroupResult(
                        group.functionalOrgId(),
                        group.functionalOrgName(),
                        group.planId(),
                        report.getId(),
                        details.size(),
                        "COMMITTED",
                        "已写入 " + details.size() + " 条填报草稿"
                ));
            } catch (RuntimeException ex) {
                blocked++;
                log.warn("填报导入分组写入失败：batchId={}, planId={}, dept={}, error={}",
                        batchId, group.planId(), group.functionalOrgName(), ex.getMessage());
                results.add(new ReportFillImportDtos.CommitGroupResult(
                        group.functionalOrgId(),
                        group.functionalOrgName(),
                        group.planId(),
                        null,
                        0,
                        "BLOCKED",
                        ex.getMessage() == null ? "写入失败" : ex.getMessage()
                ));
            }
        }

        previewCache.remove(batchId);
        String status = blocked == 0 ? "COMMITTED" : (totalSaved == 0 ? "COMMIT_BLOCKED" : "PARTIALLY_COMMITTED");
        log.info("填报导入提交完成：batchId={}, college={}, saved={}, blocked={}, status={}",
                batchId, college.getName(), totalSaved, blocked, status);

        return new ReportFillImportDtos.CommitResponse(batchId, status, totalSaved, blocked, List.copyOf(results));
    }

    // ==================== 解析 ====================

    private ParsedSheet parseWorkbook(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要导入的 Excel 文件");
        }
        String fileName = safeText(file.getOriginalFilename());
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("仅支持 .xlsx 格式的 Excel 文件");
        }

        try (InputStream in = file.getInputStream(); Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            int headerRowIndex = findHeaderRow(sheet, formatter);
            if (headerRowIndex < 0) {
                throw new IllegalArgumentException("表头不正确：未找到「子指标名称」列，请使用下载的模板");
            }
            Map<String, Integer> columns = resolveColumns(sheet.getRow(headerRowIndex), formatter);

            List<ParsedRow> rows = new ArrayList<>();
            for (int i = headerRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                String indicatorName = readCell(row, columns.get("indicator"), formatter);
                if (indicatorName.isEmpty()) {
                    continue;
                }
                rows.add(new ParsedRow(
                        i + 1,
                        readCell(row, columns.get("college"), formatter),
                        readCell(row, columns.get("functional"), formatter),
                        readCell(row, columns.get("indicator"), formatter),
                        readCell(row, columns.get("rating"), formatter),
                        readCell(row, columns.get("description"), formatter),
                        readCell(row, columns.get("weight"), formatter)
                ));
                if (rows.size() > MAX_DATA_ROWS) {
                    break;
                }
            }
            return new ParsedSheet(fileName, List.copyOf(rows));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("解析 Excel 失败：" + ex.getMessage(), ex);
        }
    }

    private int findHeaderRow(Sheet sheet, DataFormatter formatter) {
        int limit = Math.min(sheet.getLastRowNum(), 10);
        for (int i = 0; i <= limit; i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                if (formatter.formatCellValue(cell).contains("子指标名称")) {
                    return i;
                }
            }
        }
        return -1;
    }

    private Map<String, Integer> resolveColumns(Row header, DataFormatter formatter) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (Cell cell : header) {
            String text = formatter.formatCellValue(cell).replace("\n", "").trim();
            if (text.isEmpty()) {
                continue;
            }
            int index = cell.getColumnIndex();
            if (text.startsWith("序号")) {
                columns.putIfAbsent("seq", index);
            } else if (text.startsWith("学院")) {
                columns.putIfAbsent("college", index);
            } else if (text.contains("归口")) {
                columns.putIfAbsent("functional", index);
            } else if (text.contains("子指标名称")) {
                columns.putIfAbsent("indicator", index);
            } else if (text.startsWith("权重")) {
                columns.putIfAbsent("weight", index);
            } else if (text.contains("自评")) {
                columns.putIfAbsent("rating", index);
            } else if (text.contains("进度说明")) {
                columns.putIfAbsent("description", index);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String required : List.of("college", "functional", "indicator", "rating", "description")) {
            if (!columns.containsKey(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("模板缺少必要的列，请使用下载的模板（缺少：" + String.join(", ", missing) + "）");
        }
        return columns;
    }

    private String readCell(Row row, Integer columnIndex, DataFormatter formatter) {
        if (columnIndex == null) {
            return "";
        }
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell).replace("\r", " ").replace("\n", " ").trim();
    }

    // ==================== 校验与解析辅助 ====================

    private SysOrg requireCollegeOperator(CurrentUser currentUser) {
        Long orgId = currentUser == null ? null : currentUser.getOrgId();
        if (orgId == null) {
            throw new AccessDeniedException("当前账号没有绑定组织，无法填报");
        }
        SysOrg org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new AccessDeniedException("当前账号所属组织不存在"));
        if (org.getType() != OrgType.academic) {
            throw new AccessDeniedException("填报导入仅对二级学院开放，其余角色请在指标填报页逐条填报");
        }
        return org;
    }

    private Long requireCycleId(Long cycleId) {
        if (cycleId == null || cycleId <= 0) {
            throw new IllegalArgumentException("缺少必填参数 cycleId（考核周期）");
        }
        return cycleId;
    }

    private String normalizeReportMonth(String reportMonth) {
        String value = reportMonth == null ? "" : reportMonth.trim();
        if (!value.matches("\\d{6}")) {
            throw new IllegalArgumentException("缺少必填参数 reportMonth（填报月份，格式 yyyyMM）");
        }
        int month = Integer.parseInt(value.substring(4));
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("填报月份不合法：" + value);
        }
        return value;
    }

    private String validateCollege(ParsedRow row, SysOrg college) {
        String value = safeText(row.collegeName());
        if (value.isEmpty()) {
            return "学院不能为空（须与登录学院一致）";
        }
        if (!normalizeKey(value).equals(normalizeKey(college.getName()))) {
            return "学院「" + value + "」与登录学院「" + college.getName() + "」不一致";
        }
        return null;
    }

    private String normalizeRating(String raw) {
        String value = safeText(raw);
        if (value.isEmpty()) {
            return null;
        }
        if (value.contains("超前")) {
            return LEVEL_AHEAD;
        }
        if (value.contains("正常")) {
            return LEVEL_NORMAL;
        }
        if (value.contains("滞后") || value.contains("延期")) {
            return LEVEL_DELAYED;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        if (List.of(LEVEL_AHEAD, LEVEL_NORMAL, LEVEL_DELAYED).contains(upper)) {
            return upper;
        }
        return null;
    }

    private String ratingLabel(String rating) {
        if (LEVEL_AHEAD.equals(rating)) {
            return "超前完成";
        }
        if (LEVEL_DELAYED.equals(rating)) {
            return "滞后";
        }
        return "正常";
    }

    private Map<String, SysOrg> loadFunctionalOrgsByName() {
        Map<String, SysOrg> result = new LinkedHashMap<>();
        for (SysOrg org : organizationRepository.findByType(OrgType.functional)) {
            if (Boolean.TRUE.equals(org.getIsDeleted())) {
                continue;
            }
            result.putIfAbsent(normalizeKey(org.getName()), org);
        }
        return result;
    }

    private Map<String, List<ParsedRow>> groupRowsByFunctionalDept(List<ParsedRow> rows) {
        Map<String, List<ParsedRow>> grouped = new LinkedHashMap<>();
        for (ParsedRow row : rows) {
            grouped.computeIfAbsent(normalizeKey(row.functionalName()), key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private Map<String, Indicator> loadDistributedIndicatorsByName(Long planId, Long collegeOrgId) {
        List<StrategicTask> tasks = taskRepository.findByPlanId(planId);
        if (tasks.isEmpty()) {
            return Map.of();
        }
        List<Long> taskIds = tasks.stream().map(StrategicTask::getId).filter(Objects::nonNull).toList();
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Indicator> result = new LinkedHashMap<>();
        List<Indicator> indicators = indicatorRepository.findByTaskIds(taskIds);
        for (Indicator indicator : indicators) {
            if (Boolean.TRUE.equals(indicator.getIsDeleted())) {
                continue;
            }
            if (indicator.getTargetOrg() == null
                    || !Objects.equals(indicator.getTargetOrg().getId(), collegeOrgId)) {
                continue;
            }
            if (!isDistributed(indicator)) {
                continue;
            }
            result.putIfAbsent(normalizeKey(indicator.getIndicatorDesc()), indicator);
        }
        return result;
    }

    private boolean isDistributed(Indicator indicator) {
        return indicator.getStatus() != null
                && "DISTRIBUTED".equals(String.valueOf(indicator.getStatus()));
    }

    /**
     * 2026-11-25（填报导入）：同一条计划线 + 同一月的全部报告。
     *
     * <p>不用 {@code PlanReportRepository#findLatestByMonthlyScope(...)} 的 native 版：那条 SQL 把
     * {@code ReportOrgType} 枚举当参数绑定，Hibernate 6 对枚举参数默认按序数绑定，
     * 与库里存的 {@code COLLEGE} 文本不相等 → 永远查不到，导致每次导入都新建一份草稿。
     */
    private List<PlanReport> findScopeReports(Long planId, String month, Long collegeOrgId, Long functionalOrgId) {
        if (planId == null) {
            return List.of();
        }
        return planReportRepository
                .findByPlanIdAndReportMonthAndReportOrgTypeAndReportOrgIdAndFunctionalOrgIdAndIsDeletedFalse(
                        planId, month, ReportOrgType.COLLEGE, collegeOrgId, functionalOrgId);
    }

    private Map<Long, PlanReportIndicatorSnapshot> loadExistingDetails(Long reportId) {
        Map<Long, PlanReportIndicatorSnapshot> result = new LinkedHashMap<>();
        for (PlanReportIndicatorSnapshot snapshot : planReportIndicatorRepository.findByReportId(reportId)) {
            if (snapshot.indicatorId() != null) {
                result.put(snapshot.indicatorId(), snapshot);
            }
        }
        return result;
    }

    private void purgeExpiredPreviews(Instant now) {
        previewCache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String displayName(String key) {
        return key == null || key.isEmpty() ? "（空）" : key;
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    // ==================== 内部数据结构 ====================

    private record ParsedSheet(String fileName, List<ParsedRow> rows) {
    }

    private record ParsedRow(
            int rowNo,
            String collegeName,
            String functionalName,
            String indicatorName,
            String ratingRaw,
            String description,
            String weightRaw
    ) {
    }

    private record ResolvedRow(Long indicatorId, String indicatorName, String selfRating, String description) {
    }

    private record ResolvedGroup(
            Long functionalOrgId,
            String functionalOrgName,
            Long planId,
            Long existingReportId,
            List<ResolvedRow> rows
    ) {
    }

    private record CachedPreview(
            String batchId,
            String confirmToken,
            Instant createdAt,
            Long cycleId,
            String reportMonth,
            Long collegeOrgId,
            String collegeName,
            List<ResolvedGroup> groups,
            List<String> fileErrors,
            Instant expiresAt
    ) {
    }
}
