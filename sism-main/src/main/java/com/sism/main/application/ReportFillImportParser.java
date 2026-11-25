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
 * 2026-11-25（指标填报导入式上报 · 学院侧）：Excel 解析器。
 *
 * <p><b>与 {@link ExcelBusinessImportParser}（导入下发）刻意分开</b>——两条链路的语义、表头、
 * 分组键、序号含义全不相同：
 * <ul>
 *   <li>下发：落 {@code plan}/{@code indicator}，按「父级核心指标」分组，序号 = <b>组号</b>（sticky 继承）；</li>
 *   <li>填报（本类）：落 {@code plan_report_indicator}，按「归口职能部门」分组，序号 = <b>普通行号</b>。</li>
 * </ul>
 *
 * <p>表头识别采用<b>归一化后全等匹配</b>：先剥掉换行/多余空白与括号说明
 * （如「学院\n（须与登录学院一致）」→「学院」），再与别名表比对。
 * 这样系统发出的<b>带括号说明的模板</b>与用户自行整理的<b>干净表头</b>都能识别。
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
     * <p>与既有下发模板<b>刻意区分</b>：下发模板的「职能部门」列含义是「该行指标由哪个职能部门下发」，
     * 而本模板的「归口职能部门」列含义是「本行填报归属哪条归口线」——名字相近但语义不同，
     * 不能混用同一套别名，否则会把下发文件误认成填报文件。
     */
    private static final Map<String, List<String>> FIELD_ALIASES = new LinkedHashMap<>();

    static {
        FIELD_ALIASES.put("seq", List.of("序号", "序 号", "编号", "行号"));
        FIELD_ALIASES.put("college", List.of("学院", "学院名称", "责任学院", "填报学院"));
        FIELD_ALIASES.put("functionalDept", List.of(
                "归口职能部门", "归口部门", "归口职能部门名称", "归口部门名称"));
        FIELD_ALIASES.put("indicatorName", List.of("子指标名称", "子指标", "指标名称", "指标"));
        FIELD_ALIASES.put("weight", List.of("权重", "指标权重", "子指标权重"));
        FIELD_ALIASES.put("selfRating", List.of("自评等级", "自评进度", "自评进度等级", "进度等级"));
        FIELD_ALIASES.put("remark", List.of("进度说明", "填报说明", "完成情况描述", "说明", "备注"));
    }

    /** 解析出的一行（原始文本，不含校验结论）。 */
    public record ParsedRow(
            int rowNo,
            String seq,
            String college,
            String functionalDept,
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
     * 表头归一化：剥离换行/多余空白与括号说明。
     *
     * <p>「学院\n（须与登录学院一致）」→「学院」；「权重（系统只读）」→「权重」。
     */
    public static String normalizeHeader(String raw) {
        if (raw == null) {
            return "";
        }
        // 2026-11-25（审查修正）：Java 的 \s 与 String.trim() 都不处理全角空格 U+3000，
        // 用户从网页/文档粘贴的「学院　（须与登录学院一致）」会归一化失败，先统一替换。
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
     * @param sheetName 指定工作表名（空 = 取第一个有内容的工作表）
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
     * <p>2026-11-25（审查修正）：用户按说明删掉全部示例行、只留表头时，
     * {@code getLastRowNum() == 0}，旧的「跳过空页」逻辑会把数据页跳过、
     * 转而解析第二个「填写说明」页，报「未识别到有效表头」——提示完全跑偏。
     */
    private Sheet resolveSheet(Workbook workbook, String sheetName, DataFormatter formatter) {
        if (sheetName != null && !sheetName.isBlank()) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                throw new IllegalStateException("Excel 文件中没有名为「" + sheetName + "」的工作表");
            }
            return sheet;
        }
        // 1) 优先找表头能命中的页（说明页没有表头，自然被跳过）
        for (Sheet sheet : workbook) {
            if (hasRecognizableHeader(sheet, formatter)) {
                return sheet;
            }
        }
        // 2) 退化为第一个非空页；都没有则报错（由调用方给出「没有可解析工作表」）
        for (Sheet sheet : workbook) {
            if (sheet.getPhysicalNumberOfRows() > 0) {
                return sheet;
            }
        }
        return workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
    }

    /** 该页是否存在能命中「子指标名称」的表头行。 */
    private boolean hasRecognizableHeader(Sheet sheet, DataFormatter formatter) {
        FormulaEvaluator evaluator = sheet.getWorkbook().getCreationHelper().createFormulaEvaluator();
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            if (matchHeader(row, formatter, evaluator).containsKey("indicatorName")) {
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
            if (candidate.containsKey("indicatorName")) {
                headerRowIndex = rowIndex;
                fieldColumns = candidate;
                break;
            }
        }
        if (headerRowIndex < 0) {
            throw new IllegalStateException(
                    "未识别到有效表头，请使用最新导出的表格或下载最新模板后重试");
        }

        List<ParsedRow> rows = new ArrayList<>();
        for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isRowBlank(row, formatter, evaluator)) {
                continue;
            }
            // 2026-11-25：rowNo 记 Excel 物理行号（1-based），
            // 报错文案直接对得上用户在 Excel 里看到的行，不差表头那一行。
            rows.add(new ParsedRow(
                    rowIndex + 1,
                    cellText(row, fieldColumns.get("seq"), formatter, evaluator),
                    cellText(row, fieldColumns.get("college"), formatter, evaluator),
                    cellText(row, fieldColumns.get("functionalDept"), formatter, evaluator),
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
     * <p>同一字段命中多列时取第一列（与既有下发解析器的取值口径一致）。
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

    private boolean isRowBlank(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        int lastCell = row.getLastCellNum();
        for (int columnIndex = 0; columnIndex < lastCell; columnIndex++) {
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
     * <p>口径来自模板「填写说明」：
     * <ul>
     *   <li>第 6 条：自评等级下拉三选一「超前完成 / 正常 / 滞后」，手输其他内容会被拒绝 → 严格白名单；</li>
     *   <li>第 7 条：权重列系统只读参考 → 权重不参与落库，格式异常只警告不阻断；</li>
     *   <li>第 5 条：按「学院 + 归口职能部门 + 子指标名称」定位 → 三者缺一即无法定位。</li>
     * </ul>
     *
     * @return 错误文案列表（空 = 通过）
     */
    public static List<String> validateRowContent(ParsedRow row) {
        List<String> errors = new ArrayList<>();
        // 2026-11-25（审查修正）：序号只是行标识，不是定位键（定位键 = 学院 + 归口 + 子指标名称）。
        // 旧实现强制必填且必须正整数，用户删掉「序号」列或单元格格式为 0.0（DataFormatter 给 "1.0"）
        // 都会整份文件全行失败。现在完全不校验序号——它不参与定位，也不落库。
        if (isBlank(row.college())) {
            errors.add("学院不能为空");
        }
        if (isBlank(row.functionalDept())) {
            errors.add("归口职能部门不能为空");
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
        return value == null || value.trim().isEmpty();
    }
}
