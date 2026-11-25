package com.sism.main.application;

import com.sism.main.application.ReportFillImportParser.ParsedRow;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
