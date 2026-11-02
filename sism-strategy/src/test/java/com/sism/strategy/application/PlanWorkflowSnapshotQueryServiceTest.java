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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-10-31 修复（P-06）单测：快照服务取「最新实例」时非终态（IN_REVIEW/PENDING）优先，
 * 撤回-重发循环下不再绑到 WITHDRAWN 旧实例；全是终态时退化为原 started_at/id 口径。
 * <p>
 * 实例选择发生在 SQL 的 ORDER BY 里（DB 侧执行），因此除数据流断言外，
 * 还对两条取「最新实例」的 SQL 做了排序键断言（CASE 排序键必须先于 started_at）。
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

    /** 注意：行必须用 singletonList 包住——List.of(Object[]) 会按 varargs 展开。
     *  三类 Query mock 按需命中（不同 SQL 路径只会用到其中一类），故用 lenient stubbing。 */
    private Query stubQuery(Object[] row) {
        Query query = mock(Query.class);
        org.mockito.Mockito.lenient().when(query.setParameter(anyString(), any())).thenReturn(query);
        org.mockito.Mockito.lenient().when(query.getResultList()).thenReturn(
                row == null ? List.of() : java.util.Collections.singletonList(row));
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
