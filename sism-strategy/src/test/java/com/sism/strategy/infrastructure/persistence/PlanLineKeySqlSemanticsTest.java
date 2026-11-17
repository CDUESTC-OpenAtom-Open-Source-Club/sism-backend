package com.sism.strategy.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-17（学院线粒度改造）：「线身份」5 键查找的<b>数据库语义</b>锁定测试。
 *
 * <p>为什么单独测：Plan 实体的 {@code plan_level} 是 PG 具名枚举（Hibernate 的
 * {@code NAMED_ENUM} 映射在 H2 上无法绑定），所以 Plan 实体没法在 H2 里跑 Spring 集成测试。
 * 但这次改造最关键的一条语义——「归口用等值比较，NULL 的 legacy 空壳不会被误命中」——
 * 是纯 SQL 语义，与 ORM 无关，这里用裸 JDBC 在 H2（PostgreSQL 模式）上把它钉死。
 *
 * <p>对应的生产查询（{@code JpaPlanRepositoryInternal} 的 5 键 finder）用的是同款谓词：
 * {@code functional_org_id = ?}（<b>不是</b> {@code IS NULL OR = ?}）。
 */
@DisplayName("线身份 5 键查找——数据库语义锁定测试")
class PlanLineKeySqlSemanticsTest {

    private static final String URL = "jdbc:h2:mem:plan_line_key_semantics;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    @Test
    @DisplayName("归口等值命中本线；同学院的另一条线与 legacy 空壳（归口为空）都不被命中")
    void shouldMatchOnlyTheLineWithSameFunctionalOrg() throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "sa", "")) {
            createPlanTable(connection);
            // 归口 A（教务处）这条线
            insertPlan(connection, 1001L, 37L);
            // 归口 B（数字校园）这条线：同学院、另一归口
            insertPlan(connection, 1002L, 38L);
            // legacy 空壳：归口为空
            insertPlan(connection, 1003L, null);

            assertThat(findPlanIds(connection, 37L)).containsExactly(1001L);
            assertThat(findPlanIds(connection, 38L)).containsExactly(1002L);
        }
    }

    @Test
    @DisplayName("归口传 null 时不命中任何行（null 不是通配符，legacy 空壳不会被当成某条线）")
    void shouldNotMatchAnyRowWhenFunctionalOrgIsNull() throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "sa", "")) {
            createPlanTable(connection);
            insertPlan(connection, 1001L, 37L);
            insertPlan(connection, 1003L, null);

            assertThat(findPlanIds(connection, null)).isEmpty();
        }
    }

    @Test
    @DisplayName("IS NULL OR = ? 写法会误命中 legacy 空壳——这正是本次改造要禁用的写法")
    void shouldDocumentThatNullTolerantPredicateWouldHitLegacyShell() throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "sa", "")) {
            createPlanTable(connection);
            insertPlan(connection, 1001L, 37L);
            insertPlan(connection, 1003L, null);

            List<Long> nullTolerant = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id FROM public.plan
                    WHERE cycle_id = 2026 AND plan_level = 'FUNC_TO_COLLEGE'
                      AND created_by_org_id = 35 AND target_org_id = 62
                      AND (functional_org_id IS NULL OR functional_org_id = ?)
                    ORDER BY id
                    """)) {
                statement.setLong(1, 37L);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        nullTolerant.add(rs.getLong(1));
                    }
                }
            }

            // 反证：宽松写法会把 legacy 空壳一起捞出来 → 5 键 finder 必须用等值
            assertThat(nullTolerant).containsExactly(1001L, 1003L);
            // 等值写法（生产用的）只命中本线
            assertThat(findPlanIds(connection, 37L)).containsExactly(1001L);
        }
    }

    private List<Long> findPlanIds(Connection connection, Long functionalOrgId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM public.plan
                WHERE cycle_id = 2026 AND plan_level = 'FUNC_TO_COLLEGE'
                  AND created_by_org_id = 35 AND target_org_id = 62
                  AND functional_org_id = ?
                ORDER BY id
                """)) {
            if (functionalOrgId == null) {
                statement.setNull(1, java.sql.Types.BIGINT);
            } else {
                statement.setLong(1, functionalOrgId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    private void createPlanTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS public.plan");
            statement.execute("""
                    CREATE TABLE public.plan (
                        id BIGINT PRIMARY KEY,
                        cycle_id BIGINT NOT NULL,
                        plan_level VARCHAR(32) NOT NULL,
                        created_by_org_id BIGINT NOT NULL,
                        target_org_id BIGINT NOT NULL,
                        functional_org_id BIGINT,
                        is_deleted BOOLEAN NOT NULL
                    )
                    """);
        }
    }

    private void insertPlan(Connection connection, long id, Long functionalOrgId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO public.plan
                    (id, cycle_id, plan_level, created_by_org_id, target_org_id, functional_org_id, is_deleted)
                VALUES (?, 2026, 'FUNC_TO_COLLEGE', 35, 62, ?, FALSE)
                """)) {
            statement.setLong(1, id);
            if (functionalOrgId == null) {
                statement.setNull(2, java.sql.Types.BIGINT);
            } else {
                statement.setLong(2, functionalOrgId);
            }
            statement.executeUpdate();
        }
    }
}
