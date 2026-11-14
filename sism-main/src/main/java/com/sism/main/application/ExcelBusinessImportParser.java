package com.sism.main.application;

import com.sism.main.interfaces.dto.BusinessImportDtos.FieldMapping;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportAction;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportRowPreview;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportType;
import com.sism.main.interfaces.dto.BusinessImportDtos.NormalizedImportRow;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class ExcelBusinessImportParser {


    private static final Map<String, List<String>> STRATEGIC_ALIASES = Map.ofEntries(
            Map.entry("department", List.of("职能部门", "责任部门", "部门")),
            // 2026-10-31 终版表头：「任务类型」=战略任务整体类型（基础性/发展性，落库 taskCategory），
            // 任务级字段随组合并、组内继承；「任务类别」别名保留（页面与旧文件口径）
            Map.entry("taskType", List.of("任务类型", "类型", "任务类别")),
            Map.entry("strategicTask", List.of("战略任务", "任务名称", "任务内容")),
            Map.entry("indicatorName", List.of("核心指标", "指标名称", "指标内容")),
            Map.entry("indicatorType", List.of("指标类型", "计量方式")),
            // 2026-10-20：导出/模板表头带格式提示「权重（可填 10 / 10% / 0.1）」，需兼容识别。
            // 2026-11-12 前端方案A模板/导出列头改为「权重（纯数字）」——注意：前端模板表头文案演进时
            // 必须同步本别名表，否则 matchField 全等匹配失败导致整列不映射（weight=null 按默认 100）
            Map.entry("weight", List.of("权重", "指标权重", "权重（可填 10 / 10% / 0.1）", "权重(可填 10 / 10% / 0.1)",
                    "权重（纯数字）", "权重(纯数字)")),
            Map.entry("remark", List.of("下发备注", "备注", "说明")),
            // 2026-10-31 层级化定案：序号=组号（组首行必填，组内留空=同上），不再做行身份匹配；
            // 列别名保留（向后兼容旧导出表）
            Map.entry("seq", List.of("序号", "序 号"))
    );

    private static final Map<String, List<String>> DISTRIBUTION_ALIASES = Map.ofEntries(
            Map.entry("college", List.of("学院", "下级部门", "责任学院")),
            // 2026-10-31 终版表头加列（学院模板）：「任务类型」=父级核心指标所属任务的类型
            // （基础性/发展性），系统预填、随组合并、组内继承；锚点级只读校验——与系统父级所属
            // 任务类型不一致→阻断点名（BusinessImportApplicationService.applyDistributionAnchor）。
            // 该列为系统预填信息，不新增落库字段：子指标任务类别仍由父级继承链决定；
            // 「任务类别」别名兼容。旧导出表无该列时取空串，锚点校验自动跳过（回导兼容）。
            Map.entry("taskType", List.of("任务类型", "任务类别")),
            // 2026-10-31 层级化定案：新模板已删除「父级战略任务」列（父级指标所属任务由系统按名称反查），
            // 别名保留用于兼容读取旧导出表；新模板文件不含该列时此字段为空，落库取父级指标自身任务
            Map.entry("parentStrategicTask", List.of("父级战略任务", "战略任务", "任务名称")),
            // 2026-10-31 终版表头：「父级核心指标」列改名「核心指标」（别名保留兼容旧文件）
            Map.entry("parentIndicator", List.of("核心指标", "父级核心指标", "上级指标", "来源指标")),
            // 2026-10-31 终版表头：「核心指标类型」=父指标自身类型（定量/定性），系统预填、只读校验、不落库
            Map.entry("parentIndicatorType", List.of("核心指标类型", "父级指标类型")),
            Map.entry("indicatorName", List.of("子指标名称", "指标名称", "学院指标")),
            Map.entry("indicatorType", List.of("指标类型", "计量方式")),
            // 2026-11-12 同步职能部门模板：权重列头新增「权重（纯数字）」（半角括号兜底手写文件）
            Map.entry("weight", List.of("权重", "指标权重", "子指标权重", "权重（可填 10 / 10% / 0.1）", "权重(可填 10 / 10% / 0.1)",
                    "权重（纯数字）", "权重(纯数字)")),
            Map.entry("remark", List.of("备注", "说明")),
            // 2026-10-31 层级化定案：序号=父级核心指标组号（组首行必填，组内留空=同上）；别名向后兼容
            Map.entry("seq", List.of("序号", "序 号"))
    );

    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd")
                    .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
                    .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
                    .toFormatter(),
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyy/M/d")
                    .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
                    .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
                    .toFormatter()
    );

    private final DataFormatter formatter = new DataFormatter(Locale.CHINA);

    public ParsedWorkbook parse(MultipartFile file, ImportType type, String sheetName) {
        try (InputStream inputStream = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = resolveSheet(workbook, sheetName)
                    .orElseThrow(() -> new IllegalStateException("Excel 文件中没有可解析的工作表"));
            return parseSheet(sheet, type);
        } catch (Exception ex) {
            // 2026-10-19：解析失败的用户提示需要透传（IllegalStateException 的 message 不被全局处理器吞掉）
            throw new IllegalStateException("Excel 解析失败: " + ex.getMessage(), ex);
        }
    }

    ParsedWorkbook parseSheet(Sheet sheet, ImportType type) {
        Map<String, List<String>> aliases = aliasesFor(type);
        HeaderMatch header = findHeader(sheet, aliases)
                .orElseThrow(() -> new IllegalStateException("未识别到有效表头，请使用最新导出的表格或下载模板"));

        // 2026-10-19 用户定案：序号列必填——没有序号列的文件直接拦截（格式规范才能导入）
        if (!header.fieldColumns().containsKey("seq")) {
            throw new IllegalStateException(
                    "导入文件缺少「序号」列（1、2、3…），请使用最新导出的表格或下载最新模板后重试");
        }

        List<FieldMapping> mappings = header.fieldColumns().entrySet().stream()
                .map(entry -> new FieldMapping(header.sourceHeaders().get(entry.getValue()), entry.getKey(), "HIGH"))
                .toList();

        List<ImportRowPreview> rows = new ArrayList<>();
        // 2026-10-31 层级化模板定案：序号=组号（职能部门=战略任务组、学院=父级核心指标组），
        // 组内后续行组列（序号/战略任务+任务类型；序号/任务类型+核心指标+核心指标类型）留空
        // = 延续上一组（sticky 向下继承）。合并单元格的非锚点格读取为空，与纯留空同等走 sticky 继承。
        Integer currentGroupSeq = null;
        String currentGroupKey = null;
        String currentGroupValue = null;
        // 2026-10-31 终版表头（学院模板加「任务类型」列）：组级只读列从单个扩展为按字段跟踪——
        // 职能部门=任务类型；学院=任务类型+核心指标类型。键=NormalizedImportRow 字段名，值=组首行归一值。
        Map<String, String> currentGroupTypes = new LinkedHashMap<>();
        Map<Integer, String> usedGroupNumbers = new LinkedHashMap<>();

        for (int rowIndex = header.rowIndex() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isRowBlank(row)) {
                continue;
            }
            Map<String, String> source = readSource(sheet, row, header.sourceHeaders());
            NormalizedImportRow normalized = normalize(source, header.fieldColumns(), type);
            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            // —— sticky 组归属解析（先于逐行校验）——
            String groupValue = type == ImportType.STRATEGIC_TASK
                    ? normalized.strategicTask()
                    : normalized.parentIndicator();
            String groupKey = groupKey(groupValue);
            Integer declaredSeq = normalized.seq();
            // 组级类型（任务类型/核心指标类型）冲突检查：延续行显式填写且与组首行不同 → 警告并归一
            boolean inherited = false;
            if (groupKey.isEmpty()) {
                // 留空=同上：继承上一组的组号、任务/父级名称与组级类型；
                // 首行无组可继承时由 validate 给出组首行必填错误
                if (currentGroupKey != null) {
                    inherited = true;
                }
            } else if (currentGroupKey == null || !groupKey.equals(currentGroupKey)) {
                // 新组开启：组首行必须带组号，且组号不能与其他任务/父级重复
                if (declaredSeq != null) {
                    String previousOwner = usedGroupNumbers.get(declaredSeq);
                    if (previousOwner != null && !groupKey(previousOwner).equals(groupKey)) {
                        errors.add("序号 " + declaredSeq + " 已被「" + previousOwner + "」使用，同一文件内组号（序号）不能重复");
                    }
                    usedGroupNumbers.putIfAbsent(declaredSeq, groupValue);
                }
                currentGroupSeq = declaredSeq;
                currentGroupKey = groupKey;
                currentGroupValue = groupValue;
                currentGroupTypes = new LinkedHashMap<>();
                for (Map.Entry<String, String> typeField : groupTypeFields(type)) {
                    currentGroupTypes.put(typeField.getKey(), groupTypeValue(normalized, typeField.getKey()));
                }
            } else {
                // 同组延续：序号与组级类型以组首行为准
                inherited = true;
            }
            if (inherited) {
                for (Map.Entry<String, String> typeField : groupTypeFields(type)) {
                    String rowTypeValue = groupTypeValue(normalized, typeField.getKey());
                    String headTypeValue = currentGroupTypes.get(typeField.getKey());
                    if (!rowTypeValue.isEmpty() && headTypeValue != null && !rowTypeValue.equals(headTypeValue)) {
                        warnings.add("组内" + typeField.getValue() + "填写为 " + rowTypeValue
                                + "，与组首行（" + headTypeValue + "）不一致，已按组首行为准");
                    }
                }
                normalized = withInheritedGroup(normalized, currentGroupSeq, currentGroupValue, currentGroupTypes, type);
            }

            errors.addAll(validate(normalized, type));
            warnings.addAll(validateWarnings(normalized, type));
            // 2026-10-31：预览行的行号展示为「序号」（组号）；组号缺失时退回物理行号便于定位
            Integer seqValue = normalized.seq();
            int displayNo = (seqValue != null && seqValue > 0) ? seqValue : rowIndex + 1;
            rows.add(new ImportRowPreview(
                    displayNo,
                    errors.isEmpty() ? ImportAction.CREATE : ImportAction.ERROR,
                    "",
                    normalized,
                    source,
                    errors,
                    warnings
            ));
        }

        // 2026-10-31 层级化定案：权重按组校验——职能部门模板组内基础性合计=100、
        // 学院模板组内子指标合计=100；合计不符在预览中给出警告（下发时仍会按最终权重校验拦截）
        appendGroupWeightWarnings(rows, type);

        return new ParsedWorkbook(sheet.getSheetName(), mappings, rows, digest(rows));
    }

    /** 组名比较键：去空白、转小写（用于判断两行是否属于同一任务/父级组） */
    private String groupKey(String value) {
        return clean(value).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 留空延续（sticky）：把组号、任务/父级名称与组级类型（任务类型/核心指标类型）
     * 回填到延续行，其余内容列保持本行填写值。
     * 2026-10-31 终版表头（学院模板加列）：组级类型改为按字段映射传入——
     * 职能部门继承「任务类型」；学院同时继承「任务类型」与「核心指标类型」。
     */
    private NormalizedImportRow withInheritedGroup(NormalizedImportRow row,
                                                   Integer groupSeq,
                                                   String groupValue,
                                                   Map<String, String> groupTypes,
                                                   ImportType type) {
        if (type == ImportType.STRATEGIC_TASK) {
            return new NormalizedImportRow(
                    groupSeq,
                    row.department(),
                    row.college(),
                    groupTypes.getOrDefault("taskType", ""),
                    groupValue,
                    row.parentStrategicTask(),
                    row.parentIndicator(),
                    row.parentIndicatorType(),
                    row.indicatorName(),
                    row.indicatorType(),
                    row.weight(),
                    row.remark(),
                    row.parentIndicatorId()
            );
        }
        return new NormalizedImportRow(
                groupSeq,
                row.department(),
                row.college(),
                groupTypes.getOrDefault("taskType", ""),
                row.strategicTask(),
                row.parentStrategicTask(),
                groupValue,
                groupTypes.getOrDefault("parentIndicatorType", ""),
                row.indicatorName(),
                row.indicatorType(),
                row.weight(),
                row.remark(),
                row.parentIndicatorId()
        );
    }

    /**
     * 组级只读列清单（随组合并、组内继承、延续行与组首行冲突时警告归一）：
     * 键=NormalizedImportRow 字段名，值=表头文案（用于警告点名）。List 保序，警告顺序确定。
     * 2026-10-31 终版表头（学院模板加「任务类型」列）：学院模板为 任务类型+核心指标类型 两列。
     */
    private List<Map.Entry<String, String>> groupTypeFields(ImportType type) {
        if (type == ImportType.STRATEGIC_TASK) {
            return List.of(Map.entry("taskType", "任务类型"));
        }
        return List.of(
                Map.entry("taskType", "任务类型"),
                Map.entry("parentIndicatorType", "核心指标类型"));
    }

    /** 读取行内组级只读列的归一值（normalizeTaskType/normalizeIndicatorType 产出的空串或标准值） */
    private String groupTypeValue(NormalizedImportRow row, String field) {
        return "taskType".equals(field) ? row.taskType() : row.parentIndicatorType();
    }

    /**
     * 2026-10-31 层级化定案：按组校验权重合计。
     * 职能部门模板：组内「基础性」行合计=100（沿用现行口径，只换分组）；
     * 学院模板：组内子指标合计=100。空权重按默认值 100 参与合计。
     * 合计不符 → 组内每行追加警告（不阻断预览；自动下发时仍有最终权重校验兜底）。
     */
    private void appendGroupWeightWarnings(List<ImportRowPreview> rows, ImportType type) {
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Integer seq = rows.get(i).normalized().seq();
            if (seq != null) {
                groups.computeIfAbsent(seq, key -> new ArrayList<>()).add(i);
            }
        }
        BigDecimal required = BigDecimal.valueOf(100);
        for (Map.Entry<Integer, List<Integer>> entry : groups.entrySet()) {
            List<Integer> indexes = entry.getValue();
            List<Integer> weighted = indexes.stream()
                    .filter(index -> type != ImportType.STRATEGIC_TASK
                            || "基础性".equals(rows.get(index).normalized().taskType()))
                    // 2026-10-31 用户拍板（父级行=上级定死锚点）：未使用的父级预填行（子指标三列全空）
                    // 不参与组权重合计（其空权重不再按默认 100 计入，避免「未用行」制造假性权重警告）
                    .filter(index -> !isParentOnlyDistributionRow(rows.get(index).normalized(), type))
                    .toList();
            if (weighted.isEmpty()) {
                continue;
            }
            BigDecimal total = weighted.stream()
                    .map(index -> {
                        BigDecimal weight = rows.get(index).normalized().weight();
                        return weight == null ? BigDecimal.valueOf(100) : weight;
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(required) == 0) {
                continue;
            }
            NormalizedImportRow head = rows.get(indexes.get(0)).normalized();
            String scope = type == ImportType.STRATEGIC_TASK
                    ? "战略任务「" + head.strategicTask() + "」内基础性指标"
                    : "核心指标「" + head.parentIndicator() + "」内子指标";
            String message = "第 " + entry.getKey() + " 组（" + scope + "）权重合计为 "
                    + total.stripTrailingZeros().toPlainString() + "，应为 100，请核对组内权重";
            for (Integer index : indexes) {
                ImportRowPreview row = rows.get(index);
                List<String> warnings = new ArrayList<>(row.warnings());
                warnings.add(message);
                rows.set(index, new ImportRowPreview(
                        row.rowNo(),
                        row.action(),
                        row.businessKey(),
                        row.normalized(),
                        row.source(),
                        row.errors(),
                        warnings
                ));
            }
        }
    }

    private Optional<Sheet> resolveSheet(Workbook workbook, String sheetName) {
        if (sheetName != null && !sheetName.isBlank()) {
            Sheet named = workbook.getSheet(sheetName.trim());
            return Optional.ofNullable(named);
        }
        for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
            Sheet sheet = workbook.getSheetAt(index);
            if (sheet != null && sheet.getPhysicalNumberOfRows() > 0) {
                return Optional.of(sheet);
            }
        }
        return Optional.empty();
    }

    private Optional<HeaderMatch> findHeader(Sheet sheet, Map<String, List<String>> aliases) {
        HeaderMatch best = null;
        int lastCandidate = Math.min(sheet.getLastRowNum(), 9);
        for (int rowIndex = 0; rowIndex <= lastCandidate; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            Map<Integer, String> headers = readHeaders(row);
            Map<String, Integer> fieldColumns = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> header : headers.entrySet()) {
                String field = matchField(header.getValue(), aliases);
                if (field != null && !fieldColumns.containsKey(field)) {
                    fieldColumns.put(field, header.getKey());
                }
            }
            int score = score(fieldColumns);
            if (best == null || score > best.score()) {
                best = new HeaderMatch(rowIndex, score, headers, fieldColumns);
            }
        }
        return best != null && best.score() >= 2 ? Optional.of(best) : Optional.empty();
    }

    private int score(Map<String, Integer> fieldColumns) {
        Set<String> fields = fieldColumns.keySet();
        int score = fields.size();
        if (fields.contains("indicatorName")) {
            score += 2;
        }
        if (fields.contains("parentIndicator") || fields.contains("strategicTask")) {
            score += 2;
        }
        return score;
    }

    private Map<Integer, String> readHeaders(Row row) {
        Map<Integer, String> headers = new LinkedHashMap<>();
        short lastCellNum = row.getLastCellNum();
        for (int cellIndex = 0; cellIndex < lastCellNum; cellIndex++) {
            String value = cellText(row.getCell(cellIndex));
            if (!value.isBlank()) {
                headers.put(cellIndex, normalizeHeader(value));
            }
        }
        return headers;
    }

    private Map<String, String> readSource(Sheet sheet, Row row, Map<Integer, String> headers) {
        Map<String, String> source = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> header : headers.entrySet()) {
            int columnIndex = header.getKey();
            String value = cellText(row.getCell(columnIndex));
            // 合并单元格的值只存于区域左上角，从属行回填首行值（2026-10-18 修复：导出表「战略任务」列
            // 与界面同构做了 rowSpan 合并，回导时从属行不再误报“战略任务不能为空”）
            if (value.isBlank()) {
                value = mergedRegionValue(sheet, row.getRowNum(), columnIndex);
            }
            source.put(header.getValue(), value);
        }
        return source;
    }

    /** 若 (rowNum, columnIndex) 落在某个合并区域内，返回区域左上角单元格文本；否则返回空串 */
    private String mergedRegionValue(Sheet sheet, int rowNum, int columnIndex) {
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            CellRangeAddress region = sheet.getMergedRegion(i);
            if (region.isInRange(rowNum, columnIndex)) {
                Row firstRow = sheet.getRow(region.getFirstRow());
                if (firstRow != null) {
                    return cellText(firstRow.getCell(region.getFirstColumn()));
                }
            }
        }
        return "";
    }

    private NormalizedImportRow normalize(Map<String, String> source,
                                          Map<String, Integer> fieldColumns,
                                          ImportType type) {
        Map<String, List<String>> aliases = aliasesFor(type);
        String department = value(source, aliases, "department");
        String college = value(source, aliases, "college");
        String taskType = normalizeTaskType(value(source, aliases, "taskType"));
        String strategicTask = clean(value(source, aliases, "strategicTask"));
        String parentStrategicTask = clean(value(source, aliases, "parentStrategicTask"));
        String parentIndicator = clean(value(source, aliases, "parentIndicator"));
        // 2026-10-31 终版表头：「核心指标类型」=父指标自身类型，只读校验用（不落库）
        String parentIndicatorType = normalizeIndicatorType(value(source, aliases, "parentIndicatorType"));
        String indicatorName = clean(value(source, aliases, "indicatorName"));
        String indicatorType = normalizeIndicatorType(value(source, aliases, "indicatorType"));
        BigDecimal weight = parseWeight(value(source, aliases, "weight")).orElse(null);
        String remark = clean(value(source, aliases, "remark"));
        Integer seq = parsePositiveInt(value(source, aliases, "seq"));
        return new NormalizedImportRow(
                seq,
                department,
                college,
                taskType,
                strategicTask,
                parentStrategicTask,
                parentIndicator,
                parentIndicatorType,
                indicatorName,
                indicatorType,
                weight,
                remark,
                null
        );
    }

    /** 解析正整数（序号列）；空白或非正整数返回 null，由 validate 给出明确错误 */
    private Integer parsePositiveInt(String raw) {
        String cleaned = clean(raw);
        if (cleaned.isEmpty() || !cleaned.matches("\\d+")) {
            return null;
        }
        try {
            return Integer.valueOf(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private List<String> validate(NormalizedImportRow row, ImportType type) {
        List<String> errors = new ArrayList<>();
        // 2026-10-31 层级化定案：序号=组号，组首行必填（延续行由 sticky 继承补齐，不在此报错）
        if (row.seq() == null) {
            errors.add("组首行「序号」（组号）不能为空或不合法（应为 1、2、3… 的正整数）");
        }
        if (type == ImportType.STRATEGIC_TASK) {
            if (isBlank(row.taskType())) {
                // 2026-10-31 终版表头：任务类型为任务级字段（组首行必填，组内继承）
                errors.add("任务类型（组首行）不能为空或无法识别（基础性/发展性）");
            }
            if (isBlank(row.strategicTask())) {
                errors.add("战略任务（组首行）不能为空；组内延续行请留空组列（留空=同上）");
            }
        } else {
            if (isBlank(row.parentIndicator())) {
                errors.add("核心指标（组首行）不能为空；组内延续行请留空组列（留空=同上）");
            }
        }
        // 2026-10-31 用户拍板（父级行=上级定死锚点）：学院模板中「子指标名称/指标类型/权重」
        // 全空的行是未使用的父级预填行——不再因缺子指标内容报阻断错误；是否跳过由
        // BusinessImportApplicationService.enrichDistributionRows 按锚点一致性判定（父级被改=阻断）
        if (!isParentOnlyDistributionRow(row, type)) {
            if (isBlank(row.indicatorName())) {
                errors.add(type == ImportType.STRATEGIC_TASK ? "核心指标不能为空" : "子指标名称不能为空");
            }
            if (isBlank(row.indicatorType())) {
                errors.add("指标类型不能为空或无法识别");
            }
        }
        if (row.weight() != null
                && (row.weight().compareTo(BigDecimal.ZERO) < 0
                || row.weight().compareTo(BigDecimal.valueOf(100)) > 0)) {
            errors.add("权重必须在 0 到 100 之间");
        }
        return errors;
    }

    /**
     * 2026-10-31 用户拍板（父级行=上级定死锚点）：学院模板的「父级预填行」识别口径——
     * 子指标名称、指标类型、权重三列全空即视为未填任何子指标（仅父级组列有值）。
     * 此类行不再按缺子指标内容阻断，改由 enrich 阶段按「父级行是否被改动」决定跳过或按锚点不一致阻断。
     */
    private boolean isParentOnlyDistributionRow(NormalizedImportRow row, ImportType type) {
        return type == ImportType.DISTRIBUTION
                && isBlank(row.indicatorName())
                && isBlank(row.indicatorType())
                && row.weight() == null;
    }

    private List<String> validateWarnings(NormalizedImportRow row, ImportType type) {
        List<String> warnings = new ArrayList<>();
        // 未使用的父级预填行不落库，不提示默认权重（避免对将跳过的行产生误导性警告）
        if (row.weight() == null && !isParentOnlyDistributionRow(row, type)) {
            // P-16 修复（2026-10-31）：默认值就是 100（BusinessImportApplicationService.defaultWeight），
            // 文案直说，不再让用户猜「系统默认值」是多少
            warnings.add("权重为空，将按默认值 100 处理");
        }
        return warnings;
    }

    private String matchField(String header, Map<String, List<String>> aliases) {
        for (Map.Entry<String, List<String>> entry : aliases.entrySet()) {
            for (String alias : entry.getValue()) {
                if (normalizeHeader(alias).equals(header)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private String value(Map<String, String> source, Map<String, List<String>> aliases, String field) {
        List<String> candidates = aliases.getOrDefault(field, List.of());
        for (String candidate : candidates) {
            String value = source.get(normalizeHeader(candidate));
            if (value != null && !value.isBlank()) {
                return clean(value);
            }
        }
        return "";
    }

    private Map<String, List<String>> aliasesFor(ImportType type) {
        return type == ImportType.STRATEGIC_TASK ? STRATEGIC_ALIASES : DISTRIBUTION_ALIASES;
    }

    private String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.FORMULA) {
            return formatter.formatCellValue(cell).trim();
        }
        return formatter.formatCellValue(cell).trim();
    }

    private boolean isRowBlank(Row row) {
        for (int cellIndex = 0; cellIndex < row.getLastCellNum(); cellIndex++) {
            if (!cellText(row.getCell(cellIndex)).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String normalizeHeader(String value) {
        return clean(value).replace(" ", "").replace("\n", "");
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replace('\u00A0', ' ');
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalizeTaskType(String raw) {
        String value = clean(raw).toUpperCase(Locale.ROOT);
        if (value.isBlank()) {
            return "";
        }
        if (value.contains("基础") || value.equals("BASIC")) {
            return "基础性";
        }
        if (value.contains("发展") || value.equals("DEVELOPMENT")) {
            return "发展性";
        }
        return "";
    }

    private String normalizeIndicatorType(String raw) {
        String value = clean(raw).toUpperCase(Locale.ROOT);
        if (value.isBlank()) {
            return "";
        }
        if (value.contains("定量") || value.equals("QUANTITATIVE")) {
            return "定量";
        }
        if (value.contains("定性") || value.equals("QUALITATIVE")) {
            return "定性";
        }
        return "";
    }

    private Optional<BigDecimal> parseWeight(String raw) {
        String value = clean(raw);
        if (value.isBlank()) {
            return Optional.empty();
        }
        value = value.replace("%", "").replace("％", "").trim();
        try {
            BigDecimal parsed = new BigDecimal(value);
            if (parsed.compareTo(BigDecimal.ZERO) > 0 && parsed.compareTo(BigDecimal.ONE) <= 0) {
                parsed = parsed.multiply(BigDecimal.valueOf(100));
            }
            return Optional.of(parsed.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros());
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }


    private Optional<Integer> parseProgress(String raw) {
        String value = clean(raw).replace("%", "").replace("％", "");
        if (value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(value).setScale(0, RoundingMode.HALF_UP).intValue());
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private Optional<LocalDateTime> parseDateTime(String raw) {
        String value = clean(raw).replace('T', ' ');
        if (value.isBlank()) {
            return Optional.empty();
        }
        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                if (formatter == DateTimeFormatter.ISO_LOCAL_DATE_TIME) {
                    return Optional.of(LocalDateTime.parse(raw.trim(), formatter).withSecond(0).withNano(0));
                }
                return Optional.of(LocalDateTime.parse(value, formatter).withSecond(0).withNano(0));
            } catch (DateTimeParseException ignored) {
                try {
                    return Optional.of(LocalDate.parse(value, formatter).atStartOfDay());
                } catch (DateTimeParseException ignoredAgain) {
                    // Try next formatter.
                }
            }
        }
        return Optional.empty();
    }

    private String digest(List<ImportRowPreview> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ImportRowPreview row : rows) {
                digest.update((row.rowNo() + ":" + row.normalized().toMap()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            byte[] bytes = digest.digest();
            StringBuilder builder = new StringBuilder("sha256:");
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create import digest", ex);
        }
    }

    public record ParsedWorkbook(
            String sheetName,
            List<FieldMapping> fieldMappings,
            List<ImportRowPreview> rows,
            String confirmToken
    ) {
    }

    private record HeaderMatch(
            int rowIndex,
            int score,
            Map<Integer, String> sourceHeaders,
            Map<String, Integer> fieldColumns
    ) {
    }
}
