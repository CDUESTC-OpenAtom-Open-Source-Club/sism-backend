package com.sism.strategy.application;

import com.sism.iam.domain.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-10-31 修复（P-06）单测：快照服务取「最新实例」时非终态（IN_REVIEW/PENDING）优先，
 * 撤回-重发循环下不再绑到 WITHDRAWN 旧实例；全是终态时退化为原 started_at/id 口径。
 * <p>
 * 实例选择发生在 SQL 的 ORDER BY 里（DB 侧执行），因此除数据流断言外，
 * 还对两条取「最新实例」的 SQL 做了排序键断言（CASE 排序键必须先于 started_at）。
 * <p>
 * 2026-11-11（驳回退回态撤回放行）：canWithdraw 判定补第四条通路——
 * pending 步骤的前一相邻步骤（pendingNo-1）为 REJECTED（审批人驳回、退回待重报态）时放行，
 * 修复「驳回退回后发起人被 400 永久锁死」的问题。覆盖语义边界：
 * 退回待重报 → true；正常审批中（前一步 APPROVED 提交步）→ true（原口径回归）；
 * 首步 PENDING → false；正常三级审批中（前一步 APPROVED 审批步）→ false；批量路径同口径。
 */
@ExtendWith(MockitoExtension.class)
class PlanWorkflowSnapshotQueryServiceTest {

    private static final Long PLAN_ID = 111L;
    private static final Long INSTANCE_ID = 901L;

    @Mock
    private EntityManager entityManager;

    @Mock
    private UserRepository userRepository;

    private PlanWorkflowSnapshotQueryService service;

    /** 记录传给 createNativeQuery 的全部 SQL，供排序键断言。 */
    private List<String> capturedSqls;

    @BeforeEach
    void setUp() {
        service = new PlanWorkflowSnapshotQueryService(userRepository);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        capturedSqls = new ArrayList<>();
    }

    @Test
    @DisplayName("单实例快照：SQL 按非终态优先排序，且 IN_REVIEW 行正常构建快照")
    void getWorkflowSnapshotShouldOrderNonTerminalFirstAndBindInReviewInstance() {
        stubQueriesReturning(Map.of(
                "instance", new Object[]{INSTANCE_ID, "IN_REVIEW", 8001L,
                        Timestamp.valueOf(LocalDateTime.of(2026, 10, 30, 9, 0)), null}
        ));

        PlanWorkflowSnapshotQueryService.WorkflowSnapshot snapshot = service.getWorkflowSnapshotByPlanId(PLAN_ID);

        assertNotNull(snapshot);
        assertEquals(INSTANCE_ID, snapshot.getWorkflowInstanceId());
        assertEquals("IN_REVIEW", snapshot.getWorkflowStatus());
        assertLatestInstanceSqlOrdersNonTerminalFirst();
    }

    @Test
    @DisplayName("全终态旧行为：SQL 保留 started_at/id 兜底排序，终态行照常返回")
    void getWorkflowSnapshotShouldKeepLegacyOrderingWhenAllInstancesTerminal() {
        stubQueriesReturning(Map.of(
                "instance", new Object[]{INSTANCE_ID, "WITHDRAWN", 8001L,
                        Timestamp.valueOf(LocalDateTime.of(2026, 10, 30, 9, 0)),
                        Timestamp.valueOf(LocalDateTime.of(2026, 10, 30, 10, 0))}
        ));

        PlanWorkflowSnapshotQueryService.WorkflowSnapshot snapshot = service.getWorkflowSnapshotByPlanId(PLAN_ID);

        assertNotNull(snapshot);
        assertEquals("WITHDRAWN", snapshot.getWorkflowStatus());
        assertLatestInstanceSqlOrdersNonTerminalFirst();
    }

    @Test
    @DisplayName("批量快照：DISTINCT ON 查询同样带非终态优先排序键")
    void getWorkflowSnapshotsByPlanIdsShouldOrderNonTerminalFirst() {
        stubQueriesReturning(Map.of(
                "latestBatch", new Object[]{PLAN_ID, INSTANCE_ID, "IN_REVIEW", 8001L,
                        Timestamp.valueOf(LocalDateTime.of(2026, 10, 30, 9, 0)), null}
        ));

        Map<Long, PlanWorkflowSnapshotQueryService.WorkflowSnapshot> snapshots =
                service.getWorkflowSnapshotsByPlanIds(List.of(PLAN_ID));

        assertEquals(1, snapshots.size());
        assertEquals("IN_REVIEW", snapshots.get(PLAN_ID).getWorkflowStatus());

        String batchSql = capturedSqls.stream()
                .filter(sql -> sql.contains("DISTINCT ON (ai.entity_id)"))
                .findFirst()
                .orElseThrow();
        assertTrue(batchSql.contains("CASE WHEN ai.status IN ('IN_REVIEW', 'PENDING') THEN 0 ELSE 1 END"),
                "批量取最新实例必须带非终态优先排序键");
        assertTrue(batchSql.indexOf("CASE WHEN ai.status IN ('IN_REVIEW', 'PENDING')")
                        < batchSql.indexOf("ai.started_at DESC"),
                "非终态排序键必须先于 started_at（否则退化为旧行为）");
    }

    // ==================== canWithdraw 判定（2026-11-11 驳回退回态放行） ====================

    @Test
    @DisplayName("驳回退回态：pending 前一步 REJECTED → canWithdraw=true（修「退回后发起人被锁死」）")
    void shouldAllowWithdrawWhenPreviousStepRejected() {
        Map<String, Object> rejectedParams = new LinkedHashMap<>();
        Query rejectedCountQuery = recordingQuery(1L, rejectedParams);
        stubSingleInstanceWithdrawQueries(
                scalarQuery(4),          // 当前 PENDING 步骤号：驳回续链生成的新步骤 step4
                scalarQuery(null),       // 前一步（step3）非「APPROVED + 提交」
                rejectedCountQuery,      // 前一步（step3）REJECTED 计数 = 1
                scalarQuery("材料口径不符，退回重报"));

        PlanWorkflowSnapshotQueryService.WorkflowSnapshot snapshot = service.getWorkflowSnapshotByPlanId(PLAN_ID);

        assertEquals(Boolean.TRUE, snapshot.getCanWithdraw(),
                "驳回退回待重报态，发起人放弃重来必须可撤回（C1 实测 #9 实例场景）");
        assertEquals(3, rejectedParams.get("stepNo"),
                "退回判定必须落在 pendingNo-1 的相邻步骤上");
        assertEquals(INSTANCE_ID, rejectedParams.get("instanceId"));
    }

    @Test
    @DisplayName("正常审批中：pending 前一步 APPROVED 提交步 → canWithdraw=true（原口径回归）")
    void shouldKeepWithdrawAllowedWhenPreviousSubmitApproved() {
        stubSingleInstanceWithdrawQueries(
                scalarQuery(2),          // 当前 PENDING：step2 负责人审批
                scalarQuery(1L),         // step1「填报人提交」APPROVED 计数 = 1
                scalarQuery(null),       // step1 非 REJECTED
                scalarQuery(null));

        PlanWorkflowSnapshotQueryService.WorkflowSnapshot snapshot = service.getWorkflowSnapshotByPlanId(PLAN_ID);

        assertEquals(Boolean.TRUE, snapshot.getCanWithdraw(),
                "首审环节待审时发起人撤回口径不变");
    }

    @Test
    @DisplayName("首步 PENDING：无前一步 → canWithdraw=false（不适用退回通路）")
    void shouldDenyWithdrawWhenPendingIsFirstStep() {
        stubSingleInstanceWithdrawQueries(
                scalarQuery(1),          // pendingNo=1，无前一步
                scalarQuery(null),
                scalarQuery(null),
                scalarQuery(null));

        PlanWorkflowSnapshotQueryService.WorkflowSnapshot snapshot = service.getWorkflowSnapshotByPlanId(PLAN_ID);

        assertEquals(Boolean.FALSE, snapshot.getCanWithdraw(),
                "首步未过不能撤回（pendingNo=1 无前一步，两条通路都不命中）");
        long rejectedLookupCount = capturedSqls.stream()
                .filter(sql -> sql.contains("asi.status = 'REJECTED'") && sql.contains("COUNT(1)"))
                .count();
        assertEquals(0, rejectedLookupCount, "pendingNo=1 时不应发起退回判定查询（短路）");
    }

    @Test
    @DisplayName("正常三级审批中：pending 前一步 APPROVED（APPROVAL）→ canWithdraw=false（防误放行）")
    void shouldDenyWithdrawWhenPreviousApprovalApproved() {
        stubSingleInstanceWithdrawQueries(
                scalarQuery(3),          // 当前 PENDING：step3 分管校领导审批
                scalarQuery(null),       // step2 非「APPROVED+提交」（是 APPROVED APPROVAL，计数 0）
                scalarQuery(null),       // step2 非 REJECTED
                scalarQuery(null));

        PlanWorkflowSnapshotQueryService.WorkflowSnapshot snapshot = service.getWorkflowSnapshotByPlanId(PLAN_ID);

        assertEquals(Boolean.FALSE, snapshot.getCanWithdraw(),
                "上一审批人已通过、轮到下一审批人时不可撤回（原口径不变）");
    }

    @Test
    @DisplayName("批量快照驳回退回态：前一步 REJECTED → canWithdraw=true（与单实例同口径）")
    void batchSnapshotShouldAllowWithdrawWhenPreviousStepRejected() {
        stubBatchWithdrawQueries(
                scalarQuery(4),          // 当前 PENDING 步骤号
                scalarQuery(null),       // 前一步非「APPROVED+提交」
                scalarQuery(1L));        // 前一步 REJECTED 计数 = 1

        Map<Long, PlanWorkflowSnapshotQueryService.WorkflowSnapshot> snapshots =
                service.getWorkflowSnapshotsByPlanIds(List.of(PLAN_ID));

        assertEquals(Boolean.TRUE, snapshots.get(PLAN_ID).getCanWithdraw(),
                "列表页驳回退回态的单据同样必须可撤回");
    }

    // ==================== stub helpers ====================

    /**
     * 按返回行内容 stub EntityManager：
     * 「取最新实例」的两条 SQL（单实例 / DISTINCT ON 批量）返回 instanceRows；
     * 用户名查询返回空；其余（当前步骤/驳回原因/待撤回计数等）一律空列表。
     * Query mock 全部在 stubbing 阶段创建（不能在 Answer 里现做 Mockito stubbing）。
     */
    private void stubQueriesReturning(Map<String, Object[]> rowsByKey) {
        Query latestBatchQuery = stubQuery(rowsByKey.get("latestBatch"));
        Query instanceQuery = stubQuery(rowsByKey.get("instance"));
        Query otherQuery = stubQuery(null);
        when(entityManager.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            capturedSqls.add(sql);
            if (sql.contains("DISTINCT ON (ai.entity_id)")) {
                return latestBatchQuery;
            }
            return sql.contains("FROM audit_instance ai") ? instanceQuery : otherQuery;
        });
    }

    /**
     * 单实例撤回判定路由 stub（2026-11-11 canWithdraw 用例专用）：
     * 实例固定 IN_REVIEW、当前步骤固定 PENDING（「战略发展部负责人审批」/ 退回待重报场景同名），
     * pendingNo / 前一步提交判定 / 前一步退回判定 / 驳回原因四类查询按传入结果返回。
     * <p>
     * 匹配顺序敏感：
     * <ol>
     *   <li>当前步骤 SQL 含 {@code CASE WHEN asi.status = 'PENDING'}，必须先于
     *       pendingNo 路由键 {@code asi.status = 'PENDING'}；</li>
     *   <li>退回判定（REJECTED + COUNT(1)）必须先于驳回原因（REJECTED + comment）。</li>
     * </ol>
     */
    private void stubSingleInstanceWithdrawQueries(Query pendingNoQuery,
                                                   Query submitCountQuery,
                                                   Query rejectedCountQuery,
                                                   Query rejectReasonQuery) {
        Query instanceQuery = stubQuery(new Object[]{INSTANCE_ID, "IN_REVIEW", 8001L,
                Timestamp.valueOf(LocalDateTime.of(2026, 10, 30, 9, 0)), null});
        Query currentStepQuery = stubQuery(new Object[]{"战略发展部负责人审批", 189L, "PENDING"});
        Query fallbackQuery = stubQuery(null);
        when(entityManager.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            capturedSqls.add(sql);
            if (sql.contains("FROM audit_instance ai")) {
                return instanceQuery;
            }
            if (sql.contains("status IN ('PENDING', 'WITHDRAWN')")) {
                return currentStepQuery;
            }
            if (sql.contains("asi.status = 'REJECTED'") && sql.contains("COUNT(1)")) {
                return rejectedCountQuery;
            }
            if (sql.contains("asi.status = 'REJECTED'")) {
                return rejectReasonQuery;
            }
            if (sql.contains("asi.status = 'APPROVED'")) {
                return submitCountQuery;
            }
            if (sql.contains("asi.status = 'PENDING'")) {
                return pendingNoQuery;
            }
            return fallbackQuery;
        });
    }

    /**
     * 批量撤回判定路由 stub（2026-11-11 canWithdraw 用例专用）：
     * 实例固定 IN_REVIEW、当前步骤固定 PENDING、待办计数固定 1，
     * pendingNo / 前一步提交判定 / 前一步退回判定按传入结果返回。
     * <p>
     * 匹配顺序敏感：批量当前步骤 SQL（DISTINCT ON asi + PENDING CASE）必须先于
     * {@code asi.status = 'PENDING'} 两条查询；GROUP BY（待办计数）先于 ORDER BY step_no（pendingNo）。
     */
    private void stubBatchWithdrawQueries(Query pendingNoQuery,
                                          Query submitCountQuery,
                                          Query rejectedCountQuery) {
        Query latestBatchQuery = stubQuery(new Object[]{PLAN_ID, INSTANCE_ID, "IN_REVIEW", 8001L,
                Timestamp.valueOf(LocalDateTime.of(2026, 10, 30, 9, 0)), null});
        Query currentStepQuery = stubQuery(new Object[]{INSTANCE_ID, "战略发展部负责人审批", 189L, "PENDING"});
        Query pendingCountsQuery = stubQuery(new Object[]{INSTANCE_ID, 1L});
        Query rejectReasonsQuery = stubQuery(null);
        Query fallbackQuery = stubQuery(null);
        when(entityManager.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            capturedSqls.add(sql);
            if (sql.contains("DISTINCT ON (ai.entity_id)")) {
                return latestBatchQuery;
            }
            if (sql.contains("asi.status = 'REJECTED'") && sql.contains("COUNT(1)")) {
                return rejectedCountQuery;
            }
            if (sql.contains("asi.status = 'REJECTED'")) {
                return rejectReasonsQuery;
            }
            if (sql.contains("asi.status = 'APPROVED'")) {
                return submitCountQuery;
            }
            if (sql.contains("status IN ('PENDING', 'WITHDRAWN')")) {
                return currentStepQuery;
            }
            if (sql.contains("GROUP BY")) {
                return pendingCountsQuery;
            }
            if (sql.contains("asi.status = 'PENDING'")) {
                return pendingNoQuery;
            }
            return fallbackQuery;
        });
    }

    /** 注意：行必须用 singletonList 包住——List.of(Object[]) 会按 varargs 展开。
     *  三类 Query mock 按需命中（不同 SQL 路径只会用到其中一类），故用 lenient stubbing。 */
    private Query stubQuery(Object[] row) {
        Query query = mock(Query.class);
        org.mockito.Mockito.lenient().when(query.setParameter(anyString(), any())).thenReturn(query);
        org.mockito.Mockito.lenient().when(query.getResultList()).thenReturn(
                row == null ? List.of() : java.util.Collections.singletonList(row));
        return query;
    }

    /** 标量结果查询（COUNT / step_no 等：resultList 直接含单个值，而非 Object[] 行）；null = 空列表。 */
    private Query scalarQuery(Object scalarValue) {
        return recordingQuery(scalarValue, new LinkedHashMap<>());
    }

    /** 带setParameter 记录的标量查询 mock（供绑定参数断言）。 */
    private Query recordingQuery(Object scalarValue, Map<String, Object> paramSink) {
        Query query = mock(Query.class);
        lenient().when(query.setParameter(anyString(), any())).thenAnswer(invocation -> {
            paramSink.put(invocation.getArgument(0), invocation.getArgument(1));
            return query;
        });
        lenient().when(query.getResultList()).thenReturn(
                scalarValue == null ? List.of() : List.of(scalarValue));
        return query;
    }

    private void assertLatestInstanceSqlOrdersNonTerminalFirst() {
        String singleSql = capturedSqls.stream()
                .filter(sql -> sql.contains("FROM audit_instance ai")
                        && sql.contains("ai.entity_id = :entityId"))
                .findFirst()
                .orElseThrow();
        assertTrue(singleSql.contains("CASE WHEN ai.status IN ('IN_REVIEW', 'PENDING') THEN 0 ELSE 1 END"),
                "取最新实例必须带非终态优先排序键");
        assertTrue(singleSql.indexOf("CASE WHEN ai.status IN ('IN_REVIEW', 'PENDING')")
                        < singleSql.indexOf("ai.started_at DESC"),
                "非终态排序键必须先于 started_at（否则退化为旧行为）");
    }
}
