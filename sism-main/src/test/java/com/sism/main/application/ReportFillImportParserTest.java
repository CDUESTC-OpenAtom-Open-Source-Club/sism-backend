package com.sism.main.application;

import com.sism.main.application.ReportFillImportParser.ParsedRow;
import com.sism.main.interfaces.dto.ReportFillImportDtos.ReportFillLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-25（指标填报导入式上报 · 学院侧）：解析器单测。
 *
 * <p>锁三条口径：
 * <ol>
 *   <li>表头归一化——需求方模板的表头带换行与括号说明（「学院\n（须与登录学院一致）」），
 *       归一化后要能命中别名；</li>
 *   <li>自评等级严格白名单——「超前完成 / 正常 / 滞后」，「超前」等其它写法一律行级报错
 *       （后端 {@code ProgressLevel.normalize} 对未知值静默返回 null，会把非法等级拖到提交时才炸）；</li>
 *   <li>权重列只读参考——格式异常只警告不阻断。</li>
 * </ol>
 */
class ReportFillImportParserTest {

    private static ParsedRow row(String seq, String college, String dept, String name,
                                 String weight, String level, String remark) {
        return new ParsedRow(1, seq, college, dept, name, weight, level, remark);
    }

    private static ParsedRow validRow() {
        return row("1", "计算机学院", "什邡管委会", "计算机学院-信息化运维绩效管理", "60", "正常", "运维工单响应时效达标率 96%");
    }

    // ==================== 表头归一化 ====================

    @Test
    @DisplayName("表头归一化：剥掉换行与括号说明后命中别名")
    void normalizeHeaderStripsHintsAndLineBreaks() {
        assertThat(ReportFillImportParser.normalizeHeader("学院\n（须与登录学院一致）")).isEqualTo("学院");
        assertThat(ReportFillImportParser.normalizeHeader("权重\n（系统只读）")).isEqualTo("权重");
        assertThat(ReportFillImportParser.normalizeHeader("自评等级\n（点选填写）")).isEqualTo("自评等级");
        assertThat(ReportFillImportParser.normalizeHeader("进度说明\n（请填写）")).isEqualTo("进度说明");
    }

    @Test
    @DisplayName("表头归一化：半角括号与多余空白同样处理，干净表头保持原样")
    void normalizeHeaderHandlesHalfWidthParentheses() {
        assertThat(ReportFillImportParser.normalizeHeader("权重(系统只读)")).isEqualTo("权重");
        assertThat(ReportFillImportParser.normalizeHeader("  归口职能部门  ")).isEqualTo("归口职能部门");
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
    @DisplayName("等级白名单：「超前」（旧写法）与未知值一律不认")
    void illegalLevelsRejected() {
        assertThat(ReportFillLevel.fromLabel("超前")).isNull();
        assertThat(ReportFillLevel.fromLabel("超前的")).isNull();
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
    @DisplayName("等级 / 说明 / 学院 / 归口 / 子指标名称 / 序号 缺一即报错")
    void missingRequiredFieldsAreRowErrors() {
        assertThat(ReportFillImportParser.validateRowContent(
                row("1", "计算机学院", "什邡管委会", "指标A", "60", "", "说明")))
                .anyMatch(message -> message.contains("自评等级"));
        assertThat(ReportFillImportParser.validateRowContent(
                row("1", "计算机学院", "什邡管委会", "指标A", "60", "正常", "  ")))
                .anyMatch(message -> message.contains("进度说明"));
        assertThat(ReportFillImportParser.validateRowContent(
                row("1", "", "什邡管委会", "指标A", "60", "正常", "说明")))
                .anyMatch(message -> message.contains("学院"));
        assertThat(ReportFillImportParser.validateRowContent(
                row("1", "计算机学院", "", "指标A", "60", "正常", "说明")))
                .anyMatch(message -> message.contains("归口职能部门"));
        assertThat(ReportFillImportParser.validateRowContent(
                row("1", "计算机学院", "什邡管委会", "", "60", "正常", "说明")))
                .anyMatch(message -> message.contains("子指标名称"));
        assertThat(ReportFillImportParser.validateRowContent(
                row("", "计算机学院", "什邡管委会", "指标A", "60", "正常", "说明")))
                .anyMatch(message -> message.contains("序号"));
        assertThat(ReportFillImportParser.validateRowContent(
                row("abc", "计算机学院", "什邡管委会", "指标A", "60", "正常", "说明")))
                .anyMatch(message -> message.contains("正整数"));
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
