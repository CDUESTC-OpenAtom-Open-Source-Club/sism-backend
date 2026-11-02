package com.sism.strategy.domain.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 计划显示名拼装口径单测（2026-10-31 用户定案）。
 *
 * 用例与前端 frontend/src/3-features/approval/lib/__tests__/approval-utils.test.ts
 * 的 buildPlanReferenceLabel 逐条镜像：同样的输入 → 逐字符一致的输出，
 * 任何分支不得出现 "Plan N" 式占位名或裸数字 ID。
 */
class PlanDisplayNameTest {

    @Test
    @DisplayName("年份 + 双向部门名齐全：标准口径「{年份}年度 计划（源 -> 目标）」")
    void fullFields_shouldBuildYearAndRouteLabel() {
        assertEquals("2026年度 计划（战略发展部 -> 科技处）",
                PlanDisplayName.planReferenceLabel(2026, "战略发展部", "科技处"));
    }

    @Test
    @DisplayName("名字带首尾空白：先裁剪再拼装，与前端 normalizeDisplayName 一致")
    void namesWithWhitespace_shouldBeTrimmed() {
        assertEquals("2026年度 计划（教务处 -> 计算机学院）",
                PlanDisplayName.planReferenceLabel(2026, " 教务处 ", " 计算机学院 "));
    }

    @Test
    @DisplayName("年份缺：只写「计划（源 -> 目标）」，不写年度段")
    void missingYear_shouldOmitYearSegment() {
        assertEquals("计划（教务处 -> 计算机学院）",
                PlanDisplayName.planReferenceLabel(null, "教务处", "计算机学院"));
    }

    @Test
    @DisplayName("单侧部门名缺：只写另一侧「（科技处）」")
    void singleSideOnly_shouldWriteThatSideOnly() {
        assertEquals("2026年度 计划（科技处）",
                PlanDisplayName.planReferenceLabel(2026, null, "科技处"));
        assertEquals("2026年度 计划（战略发展部）",
                PlanDisplayName.planReferenceLabel(2026, "战略发展部", "  "));
    }

    @Test
    @DisplayName("全缺：裸「计划」，给了 fallback 且确实全缺才回退")
    void allMissing_shouldFallBackOnlyWhenLabelIsBare() {
        assertEquals("计划", PlanDisplayName.planReferenceLabel(null, null, null));
        assertEquals("当前计划", PlanDisplayName.planReferenceLabel(null, null, null, "当前计划"));
        // 非全缺（有年份/有流向）不回退 fallback
        assertEquals("2026年度 计划", PlanDisplayName.planReferenceLabel(2026, " ", null, "当前计划"));
    }

    @Test
    @DisplayName("任何输入组合都不得输出 Plan N 占位")
    void neverOutputsPlaceholder() {
        assertEquals("计划", PlanDisplayName.planReferenceLabel(0, null, null));
        assertEquals("计划", PlanDisplayName.planReferenceLabel(-1, "", ""));
    }

    @Test
    @DisplayName("toYear 镜像前端 Number() 语义：纯数字串可用，其余视为无年份")
    void toYearShouldMirrorFrontendNumberSemantics() {
        assertEquals(2026, PlanDisplayName.toYear("2026"));
        assertEquals(2026, PlanDisplayName.toYear(" 2026 "));
        assertNull(PlanDisplayName.toYear(null));
        assertNull(PlanDisplayName.toYear(""));
        assertNull(PlanDisplayName.toYear("abc"));
        assertNull(PlanDisplayName.toYear("2026年"));
    }
}
