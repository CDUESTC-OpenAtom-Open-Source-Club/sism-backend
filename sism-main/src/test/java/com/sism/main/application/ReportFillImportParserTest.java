package com.sism.main.application;

import com.sism.main.application.ReportFillImportParser.ParsedRow;
import com.sism.main.application.ReportFillImportParser.ParsedWorkbook;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillLevel;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-25（指标填报导入式上报）：解析器单测。
 *
 * <p>口径来源 = 标准模板 04/05/06：
 * <ol>
 *   <li>表头归一化——新模板表头带换行与括号说明（「责任部门\n（来源·须与登录学院一致）」「目标部门\n（归口职能部门）」），
 *       归一化后要能命中别名；</li>
 *   <li>「（枚举来源，勿动）」列<b>不参与映射</b>；</li>
 *   <li>自评等级严格白名单「超前完成 / 正常 / 滞后」；</li>
 *   <li>责任部门 / 目标部门 / 子指标名称 / 等级 / 说明 必填；序号不校验；</li>
 *   <li>权重列只读参考——格式异常只警告不阻断。</li>
 * </ol>
 */
class ReportFillImportParserTest {

    private static ParsedRow row(String seq, String responsibleDept, String targetDept, String name,
                                 String weight, String level, String remark) {
        return new ParsedRow(2, seq, responsibleDept, targetDept, name, weight, level, remark);
    }

    private static ParsedRow validRow() {
        return row("1", "计算机学院", "什邡管委会", "计算机学院-信息化运维绩效管理", "60", "正常",
                "运维工单响应时效达标率 96%");
    }

    // ==================== 表头归一化 ====================

    @Test
    @DisplayName("表头归一化：新模板表头（换行 + 括号说明）剥掉提示后命中别名")
    void normalizeHeaderStripsNewTemplateHints() {
        assertThat(ReportFillImportParser.normalizeHeader("责任部门\n（来源·须与登录学院一致）"))
                .isEqualTo("责任部门");
        assertThat(ReportFillImportParser.normalizeHeader("责任部门\n（来源·须与登录部门一致）"))
                .isEqualTo("责任部门");
        assertThat(ReportFillImportParser.normalizeHeader("目标部门\n（归口职能部门）"))
                .isEqualTo("目标部门");
        assertThat(ReportFillImportParser.normalizeHeader("上报目标\n（战略发展部）"))
                .isEqualTo("上报目标");
        assertThat(ReportFillImportParser.normalizeHeader("权重\n（系统只读）")).isEqualTo("权重");
        assertThat(ReportFillImportParser.normalizeHeader("自评等级\n（点选填写）")).isEqualTo("自评等级");
        assertThat(ReportFillImportParser.normalizeHeader("进度说明\n（请填写）")).isEqualTo("进度说明");
    }

    @Test
    @DisplayName("表头归一化：枚举来源列整体被括号剥离 → 空串（因此不参与映射）")
    void normalizeHeaderHandlesEnumColumnAndLegacyNames() {
        // 「（枚举来源，勿动）」整格都在括号里 → 剥离后为空，matchHeader 会跳过它，
        // 这正是「解析时完全忽略该列」的实现方式。
        assertThat(ReportFillImportParser.normalizeHeader("（枚举来源，勿动）")).isEmpty();
        assertThat(ReportFillImportParser.normalizeHeader("学院\n（须与登录学院一致）")).isEqualTo("学院");
        assertThat(ReportFillImportParser.normalizeHeader("归口职能部门")).isEqualTo("归口职能部门");
    }

    @Test
    @DisplayName("表头归一化：半角括号、全角空格、多余空白")
    void normalizeHeaderHandlesHalfWidthAndFullWidthSpace() {
        assertThat(ReportFillImportParser.normalizeHeader("权重(系统只读)")).isEqualTo("权重");
        assertThat(ReportFillImportParser.normalizeHeader("  责任部门\u3000 ")).isEqualTo("责任部门");
        assertThat(ReportFillImportParser.normalizeHeader("序号")).isEqualTo("序号");
        assertThat(ReportFillImportParser.normalizeHeader(null)).isEmpty();
    }

    // ==================== 等级白名单 ====================

    @Test
    @DisplayName("等级白名单：超前完成 / 正常 / 滞后 三个合法值")
    void legalLevelsAccepted() {
        assertThat(ReportFillLevel.fromLabel("超前完成")).isEqualTo(ReportFillLevel.AHEAD);
        assertThat(ReportFillLevel.fromLabel("正常")).isEqualTo(ReportFillLevel.NORMAL);
        assertThat(ReportFillLevel.fromLabel("滞后")).isEqualTo(ReportFillLevel.DELAYED);
    }

    @Test
    @DisplayName("等级白名单：全角空格包裹的合法值也能识别")
    void levelToleratesFullWidthSpace() {
        assertThat(ReportFillLevel.fromLabel("正常\u3000")).isEqualTo(ReportFillLevel.NORMAL);
        assertThat(ReportFillLevel.fromLabel("\u3000超前完成")).isEqualTo(ReportFillLevel.AHEAD);
    }

    @Test
    @DisplayName("等级白名单：「超前」（旧写法）与未知值一律不认")
    void illegalLevelsRejected() {
        assertThat(ReportFillLevel.fromLabel("超前")).isNull();
        assertThat(ReportFillLevel.fromLabel("AHEAD")).isNull();
        assertThat(ReportFillLevel.fromLabel("")).isNull();
        assertThat(ReportFillLevel.fromLabel(null)).isNull();
    }

    @Test
    @DisplayName("合法值清单文案用于错误提示")
    void legalLabelsText() {
        assertThat(ReportFillLevel.legalLabels()).isEqualTo("超前完成 / 正常 / 滞后");
    }

    // ==================== 行级校验 ====================

    @Test
    @DisplayName("合法行 → 无错误")
    void validRowHasNoErrors() {
        assertThat(ReportFillImportParser.validateRowContent(validRow())).isEmpty();
    }

    @Test
    @DisplayName("等级非法 → 行级报错并列出合法值")
    void illegalLevelIsRowError() {
        List<String> errors = ReportFillImportParser.validateRowContent(
                row("1", "计算机学院", "什邡管委会", "指标A", "60", "超前", "说明"));
        assertThat(errors).isNotEmpty();
        assertThat(String.join("；", errors)).contains("超前完成 / 正常 / 滞后");
    }

    @Test
    @DisplayName("责任部门 / 目标部门 / 子指标名称 / 等级 / 说明 缺一即报错")
    void missingRequiredFieldsAreRowErrors() {
        assertThat(ReportFillImportParser.validateRowContent(
                row("1", "", "什邡管委会", "指标A", "60", "正常", "说明")))
                .anyMatch(message -> message.contains("责任部门"));
        assertThat(ReportFillImportParser.validateRowContent(
                row("1", "计算机学院", "", "指标A", "60", "正常", "说明")))
                .anyMatch(message -> message.contains("目标部门"));
        assertThat(ReportFillImportParser.validateRowContent(
                row("1", "计算机学院", "什邡管委会", "", "60", "正常", "说明")))
                .anyMatch(message -> message.contains("子指标名称"));
        assertThat(ReportFillImportParser.validateRowContent(
                row("1", "计算机学院", "什邡管委会", "指标A", "60", "", "说明")))
                .anyMatch(message -> message.contains("自评等级"));
        assertThat(ReportFillImportParser.validateRowContent(
                row("1", "计算机学院", "什邡管委会", "指标A", "60", "正常", "  ")))
                .anyMatch(message -> message.contains("进度说明"));
    }

    @Test
    @DisplayName("全角空格视为空值（责任部门/说明）")
    void fullWidthSpaceCountsAsBlank() {
        assertThat(ReportFillImportParser.validateRowContent(
                row("1", "\u3000", "什邡管委会", "指标A", "60", "正常", "说明")))
                .anyMatch(message -> message.contains("责任部门不能为空"));
    }

    @Test
    @DisplayName("序号不参与定位：可留空、非整数也不阻断")
    void seqIsNotRequired() {
        assertThat(ReportFillImportParser.validateRowContent(
                row("", "计算机学院", "什邡管委会", "指标A", "60", "正常", "说明")))
                .isEmpty();
        assertThat(ReportFillImportParser.validateRowContent(
                row("abc", "计算机学院", "什邡管委会", "指标A", "60", "正常", "说明")))
                .isEmpty();
    }

    // ==================== 权重只读提示 ====================

    @Test
    @DisplayName("权重列只读参考：非数字只警告不阻断")
    void weightIsWarningOnly() {
        assertThat(ReportFillImportParser.validateWeightHint(
                row("1", "计算机学院", "什邡管委会", "指标A", "六成", "正常", "说明")))
                .isNotEmpty();
        assertThat(ReportFillImportParser.validateWeightHint(
                row("1", "计算机学院", "什邡管委会", "指标A", "60", "正常", "说明")))
                .isEmpty();
        assertThat(ReportFillImportParser.validateWeightHint(
                row("1", "计算机学院", "什邡管委会", "指标A", "", "正常", "说明")))
                .isNotEmpty();
    }

    // ==================== 整簿解析：枚举列幻影行（2026-11-26 R3 修复） ====================

    /** 05/06 学院模板 8 列表头（与标准模板一致，第 8 列 = 枚举来源）。 */
    private static final String[] COLLEGE_HEADERS = {
            "序号",
            "责任部门\n（来源·须与登录学院一致）",
            "目标部门\n（归口职能部门）",
            "子指标名称",
            "权重\n（系统只读）",
            "自评等级\n（点选填写）",
            "进度说明\n（请填写）",
            "（枚举来源，勿动）"
    };

    /**
     * 构造与模板一致的 Excel：表头 + 数据行 + 枚举占行。
     *
     * <p>{@code dataRows} 每行 8 列竖线分隔；{@code enumTail} 模拟模板第 8 列预填的
     * 枚举占行（只有枚举列有值、其余列全空）——用户填报行数少于枚举值个数时出现。
     */
    private static MockMultipartFile workbook(List<String> dataRows, String... enumTailValues) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("月报填报导入模板");
            Row header = sheet.createRow(0);
            for (int i = 0; i < COLLEGE_HEADERS.length; i++) {
                header.createCell(i).setCellValue(COLLEGE_HEADERS[i]);
            }
            int rowIndex = 1;
            for (String dataRow : dataRows) {
                String[] cells = dataRow.split("\\|", -1);
                Row row = sheet.createRow(rowIndex++);
                for (int c = 0; c < cells.length; c++) {
                    row.createCell(c).setCellValue(cells[c]);
                }
            }
            for (String enumValue : enumTailValues) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(7).setCellValue(enumValue);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return new MockMultipartFile("file", "月报填报导入模板.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String filledRow(String seq, String name, String level) {
        return seq + "|计算机学院|什邡管委会|" + name + "|40|" + level + "|说明|" + level;
    }

    @Test
    @DisplayName("幻影行修复：填 2 行时第 3 枚举行（仅枚举列有值）判空跳过，解析出恰好 2 行")
    void enumTailRowAfterFilledRowsIsSkipped() {
        ReportFillImportParser parser = new ReportFillImportParser();
        MockMultipartFile file = workbook(List.of(
                filledRow("1", "指标A", "正常"),
                filledRow("2", "指标B", "滞后")),
                "滞后");
        ParsedWorkbook parsed = parser.parse(file, null);
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows()).extracting(ParsedRow::indicatorName)
                .containsExactly("指标A", "指标B");
    }

    @Test
    @DisplayName("幻影行修复：0 行填报时 3 个枚举占行全部判空跳过，不产生任何数据行")
    void enumTailRowsWithNoFilledRowsAreAllSkipped() {
        ReportFillImportParser parser = new ReportFillImportParser();
        MockMultipartFile file = workbook(List.of(), "超前完成", "正常", "滞后");
        ParsedWorkbook parsed = parser.parse(file, null);
        assertThat(parsed.rows()).isEmpty();
    }

    @Test
    @DisplayName("正常填满 3 行：数据行 + 枚举列同列有值，3 行全部照常解析不受影响")
    void fullyFilledRowsAreKept() {
        ReportFillImportParser parser = new ReportFillImportParser();
        MockMultipartFile file = workbook(List.of(
                filledRow("1", "指标A", "超前完成"),
                filledRow("2", "指标B", "正常"),
                filledRow("3", "指标C", "滞后")),
                "滞后");
        ParsedWorkbook parsed = parser.parse(file, null);
        assertThat(parsed.rows()).hasSize(3);
        assertThat(parsed.rows()).extracting(ParsedRow::indicatorName)
                .containsExactly("指标A", "指标B", "指标C");
        // 数据行自己的枚举列照常读出（该列只在判空时忽略，字段解析不受影响）
        assertThat(parsed.rows().get(0).selfRating()).isEqualTo("超前完成");
    }

    @Test
    @DisplayName("无「（枚举来源，勿动）」列的旧文件：判空口径不变（普通空行仍跳过）")
    void legacyFileWithoutEnumColumnKeepsBlankRowSkipping() {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("旧模板");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("序号");
            header.createCell(1).setCellValue("责任部门");
            header.createCell(2).setCellValue("目标部门");
            header.createCell(3).setCellValue("子指标名称");
            header.createCell(4).setCellValue("权重");
            header.createCell(5).setCellValue("自评等级");
            header.createCell(6).setCellValue("进度说明");
            // 数据行 1 + 纯空行（所有列包括表头外列为空）
            sheet.createRow(1).createCell(0).setCellValue("1");
            sheet.getRow(1).createCell(1).setCellValue("计算机学院");
            sheet.getRow(1).createCell(2).setCellValue("什邡管委会");
            sheet.getRow(1).createCell(3).setCellValue("指标A");
            sheet.getRow(1).createCell(4).setCellValue("40");
            sheet.getRow(1).createCell(5).setCellValue("正常");
            sheet.getRow(1).createCell(6).setCellValue("说明");
            sheet.createRow(2); // 纯空行
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            MockMultipartFile file = new MockMultipartFile("file", "旧模板.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
            ParsedWorkbook parsed = new ReportFillImportParser().parse(file, null);
            assertThat(parsed.rows()).hasSize(1);
            assertThat(parsed.rows().get(0).indicatorName()).isEqualTo("指标A");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
