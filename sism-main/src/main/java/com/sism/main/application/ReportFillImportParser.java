package com.sism.main.application;

import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillLevel;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 2026-11-25（指标填报导入式上报）：Excel 解析器。
 *
 * <p><b>标准模板以需求方提供的「SISM-导入模板-04/05/06」为准</b>（2026-11-25 定稿）：
 * 三份上报模板共用同一套 8 列结构，只有「第 3 列」的语义因流向不同而不同：
 * <pre>
 * 序号 ｜ 责任部门（来源·须与登录学院/部门一致） ｜ 目标部门（归口职能部门）/ 上报目标（战略发展部）
 *      ｜ 子指标名称 ｜ 权重（系统只读）｜ 自评等级（点选填写）｜ 进度说明（请填写）｜（枚举来源，勿动）
 * </pre>
 *
 * <ul>
 *   <li><b>责任部门</b>（来源）= 登录人所在组织，必须与登录身份一致（04 是部门、05/06 是学院）；</li>
 *   <li><b>目标部门 / 上报目标</b> = 这条填报流向上报到哪（05/06 = 归口职能部门；04 = 战略发展部）；</li>
 *   <li><b>（枚举来源，勿动）</b> = 给 Excel 下拉做数据源的辅助列，解析时<b>完全忽略</b>
 *       （2026-11-26 起判空扫描也排除该列：该列预填的枚举占行不再被误判为数据行——幻影行修复）。</li>
 * </ul>
 *
 * <p><b>与 {@link ExcelBusinessImportParser}（导入下发）刻意分开</b>——下发落 {@code plan}/{@code indicator}、
 * 按父级核心指标分组、序号是组号；填报落 {@code plan_report_indicator}、按目标部门拆线、序号只是行号。
 */
@Component
public class ReportFillImportParser {

    /** 括号说明（全角/半角）——表头归一化时整体剥离。 */
    private static final Pattern PARENTHESIZED_HINT = Pattern.compile("[（(][^（()）]*[）)]");
    /** 表头归一化后的空白（含换行/制表符/多空格）。 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * 字段别名表（键 = 内部字段名，值 = 归一化后的合法表头文案）。
     *
     * <p>2026-11-25（新模板定稿）：列名改为「责任部门 / 目标部门（上报目标）」。
     * 旧模板（「学院 / 归口职能部门」）作为别名保留，历史文件照样能解析。
     *
     * <p>注意「（枚举来源，勿动）」列<b>不在</b>别名表里——匹配不到即为忽略列。
     */
    private static final Map<String, List<String>> FIELD_ALIASES = new LinkedHashMap<>();

    static {
        FIELD_ALIASES.put("seq", List.of("序号", "编号", "行号"));
        // 责任部门（来源）= 登录人所在组织
        FIELD_ALIASES.put("responsibleDept", List.of(
                "责任部门", "责任学院", "部门", "学院", "填报部门", "填报学院", "来源部门", "来源学院"));
        // 目标部门 / 上报目标 = 上报流向的组织（05/06 归口职能部门，04 战略发展部）
        FIELD_ALIASES.put("targetDept", List.of(
                "目标部门", "上报目标", "目标组织", "归口职能部门", "归口部门", "职能部门", "上报部门"));
        FIELD_ALIASES.put("indicatorName", List.of("子指标名称", "子指标", "指标名称", "指标"));
        FIELD_ALIASES.put("weight", List.of("权重", "指标权重", "子指标权重"));
        FIELD_ALIASES.put("selfRating", List.of("自评等级", "自评进度", "自评进度等级", "进度等级"));
        FIELD_ALIASES.put("remark", List.of("进度说明", "填报说明", "完成情况描述", "说明", "备注"));
    }

    /** 解析出的一行（原始文本，不含校验结论）。 */
    public record ParsedRow(
            int rowNo,
            String seq,
            /** 责任部门（来源）：登录人所在组织 */
            String responsibleDept,
            /** 目标部门 / 上报目标：这条填报上报到哪 */
            String targetDept,
            String indicatorName,
            String weight,
            String selfRating,
            String remark
    ) {
    }

    /** 解析出的整份文件。 */
    public record ParsedWorkbook(
            String sheetName,
            Map<String, Integer> fieldColumns,
            List<ParsedRow> rows
    ) {
    }

    /**
     * 表头归一化：剥离换行/多余空白与括号说明，并把全角空格转半角。
     *
     * <p>「责任部门\n（来源·须与登录学院一致）」→「责任部门」；「权重（系统只读）」→「权重」。
     */
    public static String normalizeHeader(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace('\u3000', ' ')
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ');
        text = PARENTHESIZED_HINT.matcher(text).replaceAll(" ");
        text = WHITESPACE.matcher(text).replaceAll(" ");
        return text.trim();
    }

    /**
     * 解析上传的填报导入文件。
     *
     * @param file      上传的 Excel
     * @param sheetName 指定工作表名（空 = 优先取表头能命中的工作表）
     */
    public ParsedWorkbook parse(MultipartFile file, String sheetName) {
        try (InputStream inputStream = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            Sheet sheet = resolveSheet(workbook, sheetName, formatter);
            if (sheet == null) {
                throw new IllegalStateException("Excel 文件中没有可解析的工作表");
            }
            return parseSheet(sheet);
        } catch (IllegalStateException ex) {
            // 2026-10-19：解析失败的用户提示需要透传（消息不被全局处理器吞掉）
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Excel 解析失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 选工作表：优先用「表头能命中」的页，而不是「行数 > 0」的页。
     *
     * <p>用户按说明删掉全部示例行、只留表头时 {@code getLastRowNum() == 0}，
     * 旧的「跳过空页」逻辑会把数据页跳过、转而解析「填写说明」页，报「未识别到有效表头」——提示跑偏。
     */
    private Sheet resolveSheet(Workbook workbook, String sheetName, DataFormatter formatter) {
        if (sheetName != null && !sheetName.isBlank()) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                throw new IllegalStateException("Excel 文件中没有名为「" + sheetName + "」的工作表");
            }
            return sheet;
        }
        for (Sheet sheet : workbook) {
            if (hasRecognizableHeader(sheet, formatter)) {
                return sheet;
            }
        }
        for (Sheet sheet : workbook) {
            if (sheet.getPhysicalNumberOfRows() > 0) {
                return sheet;
            }
        }
        return workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
    }

    /** 该页是否存在能命中「子指标名称 + 责任部门」的表头行（两者同时命中才认，避免误选说明页）。 */
    private boolean hasRecognizableHeader(Sheet sheet, DataFormatter formatter) {
        FormulaEvaluator evaluator = sheet.getWorkbook().getCreationHelper().createFormulaEvaluator();
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            Map<String, Integer> matched = matchHeader(row, formatter, evaluator);
            if (matched.containsKey("indicatorName") && matched.containsKey("responsibleDept")) {
                return true;
            }
        }
        return false;
    }

    private ParsedWorkbook parseSheet(Sheet sheet) {
        DataFormatter formatter = new DataFormatter(Locale.CHINA);
        FormulaEvaluator evaluator = sheet.getWorkbook().getCreationHelper().createFormulaEvaluator();

        int headerRowIndex = -1;
        Map<String, Integer> fieldColumns = Map.of();
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            Map<String, Integer> candidate = matchHeader(row, formatter, evaluator);
            if (candidate.containsKey("indicatorName") && candidate.containsKey("responsibleDept")) {
                headerRowIndex = rowIndex;
                fieldColumns = candidate;
                break;
            }
        }
        if (headerRowIndex < 0) {
            throw new IllegalStateException(
                    "未识别到有效表头：需要「责任部门」「子指标名称」两列，请使用最新模板后重试");
        }
        // 2026-11-26（R3 幻影行修复）：先定位「（枚举来源，勿动）」辅助列，判空时排除——
        // 该列固定预填 3 个枚举值（超前完成/正常/滞后）当下拉数据源，占行数与用户实际填写行数无关。
        Integer enumSourceColumn = findEnumSourceColumn(sheet.getRow(headerRowIndex), formatter, evaluator);

        List<ParsedRow> rows = new ArrayList<>();
        for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isRowBlank(row, formatter, evaluator, enumSourceColumn)) {
                continue;
            }
            // rowNo 记 Excel 物理行号（1-based），报错文案直接对得上用户在 Excel 里看到的行
            rows.add(new ParsedRow(
                    rowIndex + 1,
                    cellText(row, fieldColumns.get("seq"), formatter, evaluator),
                    cellText(row, fieldColumns.get("responsibleDept"), formatter, evaluator),
                    cellText(row, fieldColumns.get("targetDept"), formatter, evaluator),
                    cellText(row, fieldColumns.get("indicatorName"), formatter, evaluator),
                    cellText(row, fieldColumns.get("weight"), formatter, evaluator),
                    cellText(row, fieldColumns.get("selfRating"), formatter, evaluator),
                    cellText(row, fieldColumns.get("remark"), formatter, evaluator)));
        }
        return new ParsedWorkbook(sheet.getSheetName(), fieldColumns, rows);
    }

    /**
     * 表头匹配：把该行的每个单元格归一化后与别名表比对，返回「字段名 → 列下标」。
     *
     * <p>「（枚举来源，勿动）」列归一化后是「枚举来源」/「勿动」，不在别名表里 → 自动忽略。
     */
    private Map<String, Integer> matchHeader(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        Map<String, Integer> matched = new LinkedHashMap<>();
        int lastCell = row.getLastCellNum();
        for (int columnIndex = 0; columnIndex < lastCell; columnIndex++) {
            String header = normalizeHeader(cellText(row, columnIndex, formatter, evaluator));
            if (header.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, List<String>> entry : FIELD_ALIASES.entrySet()) {
                if (matched.containsKey(entry.getKey())) {
                    continue;
                }
                if (entry.getValue().contains(header)) {
                    matched.put(entry.getKey(), columnIndex);
                    break;
                }
            }
        }
        return matched;
    }

    /**
     * 2026-11-26（R3 幻影行修复）：定位「（枚举来源，勿动）」辅助列下标。
     *
     * <p>识别口径与既有列定位一致（读表头行单元格文本）：该列表头整体在括号内
     * （「（枚举来源，勿动）」），{@link #normalizeHeader} 剥括号后为空串，
     * 因此对归一化前后原文判断「枚举来源 / 勿动」关键字；找不到返回 null（旧文件无该列时判空口径不变）。
     */
    private Integer findEnumSourceColumn(Row headerRow, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (headerRow == null) {
            return null;
        }
        int lastCell = headerRow.getLastCellNum();
        for (int columnIndex = 0; columnIndex < lastCell; columnIndex++) {
            String raw = cellText(headerRow, columnIndex, formatter, evaluator);
            if (raw.isEmpty()) {
                continue;
            }
            if (raw.contains("枚举来源") || raw.contains("勿动")) {
                return columnIndex;
            }
        }
        return null;
    }

    /**
     * 整行判空（决定该行是否作为数据行解析）。
     *
     * <p>2026-11-26（R3 幻影行修复）：扫描时排除「（枚举来源，勿动）」列（{@code enumSourceColumn}）。
     * 此前实现扫描全部列、与类注释「枚举来源列解析时完全忽略」自相矛盾——模板该列固定预填
     * 超前完成/正常/滞后 3 个枚举值当下拉数据源，用户实际填报行数少于 3 时，剩余枚举占行
     * 只有该列非空、其余列全空，却被误判为数据行（幻影行）→ 整行必填校验全挂 → 整单阻断。
     * 修正后「除枚举列外整行为空」的行按空行跳过。
     */
    private boolean isRowBlank(Row row, DataFormatter formatter, FormulaEvaluator evaluator,
                               Integer enumSourceColumn) {
        int lastCell = row.getLastCellNum();
        for (int columnIndex = 0; columnIndex < lastCell; columnIndex++) {
            if (enumSourceColumn != null && columnIndex == enumSourceColumn.intValue()) {
                continue;
            }
            if (!cellText(row, columnIndex, formatter, evaluator).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String cellText(Row row, Integer columnIndex, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null || columnIndex == null || columnIndex < 0) {
            return "";
        }
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return "";
        }
        String text = formatter.formatCellValue(cell, evaluator);
        return text == null ? "" : text.trim();
    }

    // ==================== 行级校验（纯逻辑，可单测） ====================

    /**
     * 校验一行的<b>列级</b>内容（不涉及系统数据定位）。
     *
     * <p>口径来自新模板「填写说明」：
     * <ul>
     *   <li>责任部门必填（解析时与登录身份比对，不一致整单拒绝）；</li>
     *   <li>目标部门必填（校验流转方向）；</li>
     *   <li>子指标名称必填（定位键）；</li>
     *   <li>自评等级严格三选一「超前完成 / 正常 / 滞后」；</li>
     *   <li>进度说明必填；</li>
     *   <li>序号只是行标识，不校验。</li>
     * </ul>
     */
    public static List<String> validateRowContent(ParsedRow row) {
        List<String> errors = new ArrayList<>();
        if (isBlank(row.responsibleDept())) {
            errors.add("责任部门不能为空");
        }
        if (isBlank(row.targetDept())) {
            errors.add("目标部门（上报目标）不能为空");
        }
        if (isBlank(row.indicatorName())) {
            errors.add("子指标名称不能为空");
        }
        if (isBlank(row.selfRating())) {
            errors.add("自评等级不能为空（只能是 " + ReportFillLevel.legalLabels() + "）");
        } else if (ReportFillLevel.fromLabel(row.selfRating()) == null) {
            errors.add("自评等级「" + row.selfRating() + "」无效，只能是 " + ReportFillLevel.legalLabels());
        }
        if (isBlank(row.remark())) {
            errors.add("进度说明不能为空");
        }
        return errors;
    }

    /**
     * 权重列的只读校验：权重不落库，故格式异常只给警告（不阻断导入）。
     */
    public static List<String> validateWeightHint(ParsedRow row) {
        List<String> warnings = new ArrayList<>();
        if (isBlank(row.weight())) {
            warnings.add("权重为空（该列只作参考，不影响导入）");
            return warnings;
        }
        try {
            new java.math.BigDecimal(row.weight().trim());
        } catch (NumberFormatException ex) {
            warnings.add("权重「" + row.weight() + "」不是数字（该列只作参考，不影响导入）");
        }
        return warnings;
    }

    private static boolean isBlank(String value) {
        // 全角空格 U+3000 不被 trim() 处理，先替换再判空
        return value == null || value.replace('\u3000', ' ').trim().isEmpty();
    }
}
