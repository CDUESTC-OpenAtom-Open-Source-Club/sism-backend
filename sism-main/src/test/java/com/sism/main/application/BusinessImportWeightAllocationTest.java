package com.sism.main.application;

import com.sism.main.application.BusinessImportApplicationService.WeightAllocationMode;
import com.sism.main.application.BusinessImportApplicationService.WeightAllocationPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导入时的权重自动分配规则（2026-10-28 用户定案：等分，但保留手动覆盖）。
 *
 * <p>覆盖需求方确认的四种情形：权重全空、部分手填、已满、超额。
 */
@DisplayName("Business Import Weight Allocation Tests")
class BusinessImportWeightAllocationTest {

    private static final BigDecimal TARGET = BigDecimal.valueOf(100);
    private static final BigDecimal PENDING = new BigDecimal("0.01");

    @Test
    @DisplayName("All weights blank -> split evenly across all indicators")
    void shouldSplitEvenlyWhenAllBlank() {
        WeightAllocationPlan plan = BusinessImportApplicationService.decideAllocation(
                Arrays.asList(null, PENDING, null), TARGET);

        assertEquals(WeightAllocationMode.EVEN_ALL, plan.mode());
        assertEquals(3, plan.blankCount());
        assertEquals(3, plan.totalCount());
        assertEquals(0, TARGET.compareTo(plan.remaining()));
    }

    @Test
    @DisplayName("Partially filled -> keep manual values, share the remainder among blanks")
    void shouldKeepManualAndShareRemainderAmongBlanks() {
        WeightAllocationPlan plan = BusinessImportApplicationService.decideAllocation(
                Arrays.asList(new BigDecimal("50"), null, null), TARGET);

        assertEquals(WeightAllocationMode.EVEN_BLANKS, plan.mode());
        assertEquals(2, plan.blankCount());
        assertEquals(0, new BigDecimal("50").compareTo(plan.remaining()));
    }

    @Test
    @DisplayName("Manual total already 100 -> do not touch manual values")
    void shouldNotTouchWhenManualTotalAlreadyFull() {
        WeightAllocationPlan plan = BusinessImportApplicationService.decideAllocation(
                Arrays.asList(new BigDecimal("50"), new BigDecimal("50"), null), TARGET);

        assertEquals(WeightAllocationMode.KEEP_MANUAL, plan.mode());
        assertEquals(1, plan.blankCount());
    }

    @Test
    @DisplayName("Manual total above 100 -> do not rewrite manual values")
    void shouldNotRewriteWhenManualTotalExceeds100() {
        WeightAllocationPlan plan = BusinessImportApplicationService.decideAllocation(
                Arrays.asList(new BigDecimal("60"), new BigDecimal("60"), null), TARGET);

        assertEquals(WeightAllocationMode.KEEP_MANUAL, plan.mode());
        assertEquals(1, plan.blankCount());
    }

    @Test
    @DisplayName("All manual and sum not 100 -> do not rewrite manual values")
    void shouldNotRewriteWhenAllManualAndSumNot100() {
        WeightAllocationPlan plan = BusinessImportApplicationService.decideAllocation(
                Arrays.asList(new BigDecimal("50"), new BigDecimal("80")), TARGET);

        assertEquals(WeightAllocationMode.KEEP_MANUAL, plan.mode());
        assertEquals(0, plan.blankCount());
    }

    @Test
    @DisplayName("All manual and sum exactly 100 -> nothing to do")
    void shouldDoNothingWhenAllManualAndSumExactly100() {
        WeightAllocationPlan plan = BusinessImportApplicationService.decideAllocation(
                Arrays.asList(new BigDecimal("40"), new BigDecimal("60")), TARGET);

        assertEquals(WeightAllocationMode.KEEP_MANUAL, plan.mode());
    }

    @Test
    @DisplayName("Empty input -> no allocation")
    void shouldNotAllocateForEmptyInput() {
        WeightAllocationPlan plan = BusinessImportApplicationService.decideAllocation(
                Collections.emptyList(), TARGET);

        assertEquals(WeightAllocationMode.KEEP_MANUAL, plan.mode());
        assertEquals(0, plan.totalCount());
    }

    @Test
    @DisplayName("Even split keeps the total exactly at target and every share positive")
    void shouldSplitEvenlyWithExactTotal() {
        List<BigDecimal> shares = BusinessImportApplicationService.splitEvenly(3, TARGET);

        assertEquals(3, shares.size());
        BigDecimal total = shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, TARGET.compareTo(total));
        for (BigDecimal share : shares) {
            assertTrue(share.compareTo(BigDecimal.ZERO) > 0, "权重必须大于 0（表上有 CHECK 约束）");
        }
    }

    @Test
    @DisplayName("Even split puts the remainder on the last share")
    void shouldPutRemainderOnLastShare() {
        List<BigDecimal> shares = BusinessImportApplicationService.splitEvenly(3, TARGET);

        assertEquals(0, new BigDecimal("33.33").compareTo(shares.get(0)));
        assertEquals(0, new BigDecimal("33.33").compareTo(shares.get(1)));
        assertEquals(0, new BigDecimal("33.34").compareTo(shares.get(2)));
    }

    @Test
    @DisplayName("Even split of 24 indicators matches the observed distribution")
    void shouldSplit24Indicators() {
        List<BigDecimal> shares = BusinessImportApplicationService.splitEvenly(24, TARGET);

        assertEquals(24, shares.size());
        for (int i = 0; i < 23; i++) {
            assertEquals(0, new BigDecimal("4.16").compareTo(shares.get(i)));
        }
        assertEquals(0, new BigDecimal("4.32").compareTo(shares.get(23)));
        BigDecimal total = shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, TARGET.compareTo(total));
    }

    @Test
    @DisplayName("Even split refuses when the target cannot guarantee the minimum per share")
    void shouldRefuseWhenTargetTooSmallForMinimum() {
        // 每项保底 0.01，20000 项需要 200%，100% 不够
        List<BigDecimal> shares = BusinessImportApplicationService.splitEvenly(20000, TARGET);

        assertTrue(shares.isEmpty(), "额度不足时不应分配（避免写入 0 或负数）");
    }

    @Test
    @DisplayName("Blank weight falls back to the pending marker so it takes part in allocation")
    void shouldFallBackToPendingMarkerForBlankWeight() {
        assertEquals(0, PENDING.compareTo(BusinessImportApplicationService.defaultWeight(null)));
        assertEquals(0, new BigDecimal("30").compareTo(
                BusinessImportApplicationService.defaultWeight(new BigDecimal("30"))));
    }
}
