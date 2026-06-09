package com.sism.main.application;

import com.sism.main.interfaces.dto.BusinessImportDtos.FieldMapping;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportAction;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportRowPreview;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportType;
import com.sism.main.interfaces.dto.BusinessImportDtos.MilestoneImportValue;
import com.sism.main.interfaces.dto.BusinessImportDtos.NormalizedImportRow;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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

    private static final Pattern LEADING_INDEX_PATTERN = Pattern.compile("^\\s*\\d+[\\.、)]\\s*");
    private static final Pattern MILESTONE_PATTERN = Pattern.compile("^(.*?)\\s*[（(]\\s*(.*?)\\s*[，,]\\s*(\\d+(?:\\.\\d+)?)%?\\s*[）)]\\s*$");

    private static final Map<String, List<String>> STRATEGIC_ALIASES = Map.ofEntries(
            Map.entry("department", List.of("职能部门", "责任部门", "部门")),
            Map.entry("taskType", List.of("任务类型", "类型", "任务类别")),
            Map.entry("strategicTask", List.of("战略任务", "任务名称", "任务内容")),
            Map.entry("indicatorName", List.of("核心指标", "指标名称", "指标内容")),
            Map.entry("indicatorType", List.of("指标类型", "计量方式")),
            Map.entry("weight", List.of("权重", "指标权重")),
            Map.entry("milestoneDetail", List.of("里程碑", "里程碑明细", "阶段任务", "阶段安排")),
            Map.entry("milestoneName", List.of("里程碑名称", "阶段名称")),
            Map.entry("milestoneDueAt", List.of("截止时间", "完成时间", "节点时间")),
            Map.entry("milestoneProgress", List.of("目标进度", "阶段进度")),
            Map.entry("remark", List.of("备注", "说明"))
    );

    private static final Map<String, List<String>> DISTRIBUTION_ALIASES = Map.ofEntries(
            Map.entry("college", List.of("学院", "下级部门", "责任学院")),
            Map.entry("parentStrategicTask", List.of("父级战略任务", "战略任务", "任务名称")),
            Map.entry("parentIndicator", List.of("父级核心指标", "上级指标", "来源指标")),
            Map.entry("indicatorName", List.of("子指标名称", "指标名称", "学院指标")),
            Map.entry("indicatorType", List.of("指标类型", "计量方式")),
            Map.entry("weight", List.of("权重", "子指标权重")),
            Map.entry("milestoneDetail", List.of("里程碑", "里程碑明细", "阶段任务", "阶段安排")),
            Map.entry("milestoneName", List.of("里程碑名称", "阶段名称")),
            Map.entry("milestoneDueAt", List.of("截止时间", "完成时间", "节点时间")),
            Map.entry("milestoneProgress", List.of("目标进度", "阶段进度")),
            Map.entry("remark", List.of("备注", "说明"))
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
                    .orElseThrow(() -> new IllegalArgumentException("Excel 文件中没有可解析的工作表"));
            return parseSheet(sheet, type);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Excel 解析失败: " + ex.getMessage(), ex);
        }
    }

    ParsedWorkbook parseSheet(Sheet sheet, ImportType type) {
        Map<String, List<String>> aliases = aliasesFor(type);
        HeaderMatch header = findHeader(sheet, aliases)
                .orElseThrow(() -> new IllegalArgumentException("未识别到有效表头"));

        List<FieldMapping> mappings = header.fieldColumns().entrySet().stream()
                .map(entry -> new FieldMapping(header.sourceHeaders().get(entry.getValue()), entry.getKey(), "HIGH"))
                .toList();

        List<ImportRowPreview> rows = new ArrayList<>();
        for (int rowIndex = header.rowIndex() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isRowBlank(row)) {
                continue;
            }
            Map<String, String> source = readSource(row, header.sourceHeaders());
            NormalizedImportRow normalized = normalize(source, header.fieldColumns(), type);
            List<String> errors = validate(normalized, type);
            List<String> warnings = validateWarnings(normalized);
            rows.add(new ImportRowPreview(
                    rowIndex + 1,
                    errors.isEmpty() ? ImportAction.CREATE : ImportAction.ERROR,
                    "",
                    normalized,
                    source,
                    errors,
                    warnings
            ));
        }

        return new ParsedWorkbook(sheet.getSheetName(), mappings, rows, digest(rows));
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

    private Map<String, String> readSource(Row row, Map<Integer, String> headers) {
        Map<String, String> source = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> header : headers.entrySet()) {
            source.put(header.getValue(), cellText(row.getCell(header.getKey())));
        }
        return source;
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
        String indicatorName = clean(value(source, aliases, "indicatorName"));
        String indicatorType = normalizeIndicatorType(value(source, aliases, "indicatorType"));
        BigDecimal weight = parseWeight(value(source, aliases, "weight")).orElse(null);
        String remark = clean(value(source, aliases, "remark"));
        List<MilestoneImportValue> milestones = parseMilestones(
                value(source, aliases, "milestoneDetail"),
                value(source, aliases, "milestoneName"),
                value(source, aliases, "milestoneDueAt"),
                value(source, aliases, "milestoneProgress")
        );

        return new NormalizedImportRow(
                department,
                college,
                taskType,
                strategicTask,
                parentStrategicTask,
                parentIndicator,
                indicatorName,
                indicatorType,
                weight,
                remark,
                null,
                milestones
        );
    }

    private List<String> validate(NormalizedImportRow row, ImportType type) {
        List<String> errors = new ArrayList<>();
        if (type == ImportType.STRATEGIC_TASK) {
            if (isBlank(row.taskType())) {
                errors.add("任务类型不能为空或无法识别");
            }
            if (isBlank(row.strategicTask())) {
                errors.add("战略任务不能为空");
            }
        } else {
            if (isBlank(row.parentIndicator())) {
                errors.add("父级核心指标不能为空");
            }
        }
        if (isBlank(row.indicatorName())) {
            errors.add(type == ImportType.STRATEGIC_TASK ? "核心指标不能为空" : "子指标名称不能为空");
        }
        if (isBlank(row.indicatorType())) {
            errors.add("指标类型不能为空或无法识别");
        }
        if (row.weight() != null
                && (row.weight().compareTo(BigDecimal.ZERO) < 0
                || row.weight().compareTo(BigDecimal.valueOf(100)) > 0)) {
            errors.add("权重必须在 0 到 100 之间");
        }
        if (row.milestones() != null) {
            for (int index = 0; index < row.milestones().size(); index++) {
                MilestoneImportValue milestone = row.milestones().get(index);
                if (milestone == null || isBlank(milestone.name())) {
                    continue;
                }
                int displayIndex = index + 1;
                if (milestone.dueAt() == null) {
                    errors.add("第 " + displayIndex + " 个里程碑截止时间无法解析");
                }
                if (milestone.targetProgress() == null
                        || milestone.targetProgress() < 0
                        || milestone.targetProgress() > 100) {
                    errors.add("第 " + displayIndex + " 个里程碑目标进度必须在 0 到 100 之间");
                }
            }
        }
        return errors;
    }

    private List<String> validateWarnings(NormalizedImportRow row) {
        List<String> warnings = new ArrayList<>();
        if (row.weight() == null) {
            warnings.add("权重为空，将按系统默认值处理");
        }
        if (row.milestones() == null || row.milestones().isEmpty()) {
            warnings.add("未识别到里程碑");
        } else {
            MilestoneImportValue last = row.milestones().get(row.milestones().size() - 1);
            if (last.targetProgress() == null || last.targetProgress() != 100) {
                warnings.add("最后一个里程碑目标进度不是 100%");
            }
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

    private List<MilestoneImportValue> parseMilestones(String detail,
                                                       String milestoneName,
                                                       String dueAt,
                                                       String targetProgress) {
        List<MilestoneImportValue> milestones = new ArrayList<>();
        String name = clean(milestoneName);
        if (!name.isBlank()) {
            milestones.add(new MilestoneImportValue(
                    name,
                    parseDateTime(dueAt).orElse(null),
                    parseProgress(targetProgress).orElse(null)
            ));
        }
        String normalizedDetail = clean(detail);
        if (normalizedDetail.isBlank()) {
            return milestones;
        }

        for (String line : normalizedDetail.split("\\R+")) {
            String item = LEADING_INDEX_PATTERN.matcher(clean(line)).replaceFirst("");
            if (item.isBlank()) {
                continue;
            }
            Matcher matcher = MILESTONE_PATTERN.matcher(item);
            if (matcher.matches()) {
                milestones.add(new MilestoneImportValue(
                        clean(matcher.group(1)),
                        parseDateTime(matcher.group(2)).orElse(null),
                        parseProgress(matcher.group(3)).orElse(null)
                ));
            } else {
                milestones.add(new MilestoneImportValue(item, null, null));
            }
        }
        return milestones;
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
