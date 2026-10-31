package com.sism.strategy.infrastructure;

import com.sism.strategy.application.PlanApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 指标异动终态收尾：按异动类型决定「归档 / 转正 / 只解锁」。
 *
 * 2026-10-22 用户定案：新增指标通过异动审批后要变成已下发（DISTRIBUTED），
 * 否则新增行落库的 DRAFT 状态会一直留着，之后连修改/删除异动都发不起来。
 */
@ExtendWith(MockitoExtension.class)
class StrategyWorkflowBusinessStatusAdapterTest {

    private static final Long INDICATOR_ID = 2062L;

    @Mock
    private PlanApplicationService planApplicationService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private com.sism.shared.domain.notification.DataChangeBroadcaster dataChangeBroadcaster;

    private StrategyWorkflowBusinessStatusAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new StrategyWorkflowBusinessStatusAdapter(
                planApplicationService, jdbcTemplate, dataChangeBroadcaster);
    }

    @Test
    @DisplayName("新增指标审批通过：指标转正为 DISTRIBUTED，不归档")
    void createApproved_shouldPromoteIndicatorToDistributed() {
        stubMutationType("CREATE");
        stubPlanStatus("DISTRIBUTED");
        // 无批次快照（历史 CREATE 场景）→ 值落库跳过，仅转正
        stubBatchSnapshot("INDICATOR", null);

        adapter.syncBusinessStatus("INDICATOR", INDICATOR_ID, "APPROVED", 188L, null, null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce())
                .update(sqlCaptor.capture(), eq(INDICATOR_ID));
        String allSql = String.join("\n", sqlCaptor.getAllValues());
        assertTrue(allSql.contains("SET status = 'DISTRIBUTED'"), "应把新增指标提升为已下发: " + allSql);
        assertTrue(allSql.contains("mutation_status = NULL"), "必须同时解除异动锁: " + allSql);
        assertFalse(allSql.contains("is_deleted = true"), "通过的异动不能归档指标: " + allSql);
    }

    @Test
    @DisplayName("新增指标审批驳回：指标归档，避免 DRAFT 残留继续污染权重合计")
    void createRejected_shouldArchiveIndicator() {
        stubMutationType("CREATE");

        adapter.syncBusinessStatus("INDICATOR", INDICATOR_ID, "REJECTED", 188L, "驳回", null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq(INDICATOR_ID));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("is_deleted = true"), "驳回的新增指标必须归档: " + sql);
        assertFalse(sql.contains("SET status = 'DISTRIBUTED'"), "驳回不能转正: " + sql);
    }

    @Test
    @DisplayName("删除指标审批通过：指标归档")
    void deleteApproved_shouldArchiveIndicator() {
        stubMutationType("DELETE");

        adapter.syncBusinessStatus("INDICATOR", INDICATOR_ID, "APPROVED", 188L, null, null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq(INDICATOR_ID));
        assertTrue(sqlCaptor.getValue().contains("is_deleted = true"));
    }

    @Test
    @DisplayName("删除指标审批驳回：指标保持原样，只解除异动锁")
    void deleteRejected_shouldOnlyUnlock() {
        stubMutationType("DELETE");

        adapter.syncBusinessStatus("INDICATOR", INDICATOR_ID, "REJECTED", 188L, "驳回", null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq(INDICATOR_ID));
        String sql = sqlCaptor.getValue();
        assertFalse(sql.contains("is_deleted = true"), "驳回的删除异动不能归档: " + sql);
        assertFalse(sql.contains("SET status"), "驳回的删除异动不改指标状态: " + sql);
        assertTrue(sql.contains("mutation_status = NULL"));
    }

    @Test
    @DisplayName("修改指标通过且计划已下发：指标状态对齐为 DISTRIBUTED（按整个 Plan 记录）")
    void modifyApproved_onDistributedPlan_shouldPromoteStatus() {
        stubMutationType("MODIFY");
        stubPlanStatus("DISTRIBUTED");
        // 无批次快照（历史 MODIFY 场景）→ 值落库跳过，仅转正
        stubBatchSnapshot("INDICATOR", null);

        adapter.syncBusinessStatus("INDICATOR", INDICATOR_ID, "APPROVED", 188L, null, null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce())
                .update(sqlCaptor.capture(), eq(INDICATOR_ID));
        String allSql = String.join("\n", sqlCaptor.getAllValues());
        assertFalse(allSql.contains("is_deleted = true"), "通过的修改异动不能归档: " + allSql);
        assertTrue(allSql.contains("SET status = 'DISTRIBUTED'"),
                "已下发计划上的指标通过异动后应转正: " + allSql);
    }

    @Test
    @DisplayName("新增异动被最终拒绝：归档，避免 DRAFT 残留污染权重合计")
    void createFinalRejected_shouldArchiveIndicator() {
        stubMutationType("CREATE");
        // workflowInstanceId 为 null 表示不是「退回重改」，而是终态拒绝/取消

        adapter.syncBusinessStatus("INDICATOR", INDICATOR_ID, "REJECTED", 188L, "驳回", null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq(INDICATOR_ID));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("is_deleted = true"), "终拒的新增指标必须归档: " + sql);
        assertFalse(sql.contains("SET status = 'DISTRIBUTED'"), "终拒不能转正: " + sql);
    }

    @Test
    @DisplayName("审批中间步（IN_REVIEW）不解锁，异动标记继续保留")
    void inReview_shouldKeepMutationLock() {
        adapter.syncBusinessStatus("INDICATOR", INDICATOR_ID, "IN_REVIEW", 188L, null, null);

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("修改指标审批通过：按快照 after 值落库（发起不落值，通过才更新）")
    void modifyApproved_withBatchSnapshot_shouldApplyAfterValues() {
        stubMutationType("MODIFY");
        stubPlanStatus("DISTRIBUTED");
        stubBatchSnapshot("INDICATOR",
                "{\"indicator_desc\":{\"before\":\"旧描述\",\"after\":\"新描述\"},"
                        + "\"weight_percent\":{\"before\":10,\"after\":20}}");

        // 终审通过时同步方可能传 null 实例 ID（退回步已随重提转 APPROVED）——落库不能依赖它
        adapter.syncBusinessStatus("INDICATOR", INDICATOR_ID, "APPROVED", 188L, null, null);

        verify(jdbcTemplate).update(
                contains("SET indicator_desc = COALESCE"),
                eq("新描述"), eq(new java.math.BigDecimal("20")),
                org.mockito.ArgumentMatchers.isNull(), eq(INDICATOR_ID));
    }

    @Test
    @DisplayName("任务改名审批通过：快照里的新任务名此刻落库")
    void taskApproved_shouldApplyRenamedName() {
        stubBatchSnapshot("TASK",
                "{\"name\":{\"before\":\"旧任务名\",\"after\":\"新任务名\"}}");

        adapter.syncBusinessStatus("TASK", 3100L, "APPROVED", 9L, null, null);

        verify(jdbcTemplate).update(
                contains("UPDATE public.sys_task SET name ="), eq("新任务名"), eq(3100L));
        verify(jdbcTemplate).update(
                contains("UPDATE public.sys_task SET mutation_status = NULL"), eq(3100L));
    }

    @Test
    @DisplayName("2026-10-31 用户定案（任务E）：取消异动终结含新增行的批次 → 指标软删除（界面不可见、审批历史可查快照）")
    void cancelledCreate_shouldSoftDeleteIndicator() {
        stubMutationType("CREATE");
        // 取消异动（cancelReturned）后实例 CANCELLED、退回提交步骤留痕 → 同步方传实例 ID
        adapter.syncBusinessStatus("INDICATOR", INDICATOR_ID, "CANCELLED", 188L, null, 9001L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq(INDICATOR_ID));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("is_deleted = true"), "取消异动的新增指标必须软删除: " + sql);
        assertTrue(sql.contains("mutation_status = NULL"), "软删除必须同时解除异动锁: " + sql);
        assertFalse(sql.contains("SET status = 'DISTRIBUTED'"), "取消不能转正: " + sql);
    }

    @Test
    @DisplayName("取消异动 + 修改型条目：维持现状只解锁，不软删除（2026-10-31 定案只动 CREATE）")
    void cancelledModify_shouldOnlyUnlock() {
        stubMutationType("MODIFY");

        adapter.syncBusinessStatus("INDICATOR", INDICATOR_ID, "CANCELLED", 188L, null, 9001L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq(INDICATOR_ID));
        String sql = sqlCaptor.getValue();
        assertFalse(sql.contains("is_deleted = true"), "取消的修改异动不能归档: " + sql);
        assertTrue(sql.contains("mutation_status = NULL"), "取消后只解除异动锁: " + sql);
    }

    @SuppressWarnings("unchecked")
    private void stubBatchSnapshot(String entityType, String changedFieldsJson) {
        when(jdbcTemplate.query(
                contains("JOIN public.audit_instance ai ON ai.batch_id = al.batch_id"),
                any(ResultSetExtractor.class),
                eq(entityType), any()))
                .thenReturn(changedFieldsJson);
    }

    @SuppressWarnings("unchecked")
    private void stubMutationType(String mutationType) {
        // resolveLatestMutationType 通过 ResultSetExtractor 读最近一条快照；
        // 这里直接桩出解析结果，避免把 JSON 解析逻辑重复到测试里。
        when(jdbcTemplate.query(
                contains("FROM public.audit_log"),
                any(ResultSetExtractor.class),
                eq(INDICATOR_ID)))
                .thenReturn(mutationType);
    }

    @SuppressWarnings("unchecked")
    private void stubPlanStatus(String planStatus) {
        // releaseMutationAndSyncPlanStatus 通过 ResultSetExtractor 读所属计划状态
        when(jdbcTemplate.query(
                contains("JOIN public.plan p"),
                any(ResultSetExtractor.class),
                eq(INDICATOR_ID)))
                .thenReturn(planStatus);
    }
}
