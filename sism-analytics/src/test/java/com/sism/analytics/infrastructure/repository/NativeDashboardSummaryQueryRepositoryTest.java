package com.sism.analytics.infrastructure.repository;

import com.sism.analytics.domain.repository.DashboardSummaryQueryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(NativeDashboardSummaryQueryRepository.class)
@DisplayName("NativeDashboardSummaryQueryRepository 集成测试")
class NativeDashboardSummaryQueryRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private NativeDashboardSummaryQueryRepository repository;

    @Test
    @DisplayName("fetchDepartmentProgressRows should aggregate alert counts without losing org rows")
    void fetchDepartmentProgressRowsShouldAggregateAlertCounts() {
        createProgressSchemaAndSeed();

        var rows = repository.fetchDepartmentProgressRows(null);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(DashboardSummaryQueryRepository.DepartmentProgressRow::departmentName)
                .containsExactly("部门A", "部门B");
        assertThat(rows.get(0).alertCount()).isEqualTo(1L);
        assertThat(rows.get(0).totalIndicators()).isEqualTo(2L);
        assertThat(rows.get(0).completedIndicators()).isEqualTo(1L);
        assertThat(rows.get(1).alertCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("fetchDepartmentProgressRows(targetOrgId) 视角收缩：只返回本组织一行（2026-11-27 角色分流）")
    void fetchDepartmentProgressRowsShouldScopeToTargetOrg() {
        createProgressSchemaAndSeed();

        var rows = repository.fetchDepartmentProgressRows(1L);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).departmentName()).isEqualTo("部门A");
        assertThat(rows.get(0).totalIndicators()).isEqualTo(2L);
        assertThat(rows.get(0).alertCount()).isEqualTo(1L);
    }

    /**
     * 建表/建视图并植入两部门样例数据（两个用例共用；H2 DDL 隐式提交，
     * 先 DROP IF EXISTS 保证用例无论先后执行都能从干净 schema 开始）。
     */
    private void createProgressSchemaAndSeed() {
        entityManager.getEntityManager().createNativeQuery(
                "DROP VIEW IF EXISTS analytics_indicator_dashboard_view").executeUpdate();
        entityManager.getEntityManager().createNativeQuery(
                "DROP VIEW IF EXISTS analytics_unresolved_alert_dashboard_view").executeUpdate();
        entityManager.getEntityManager().createNativeQuery(
                "DROP VIEW IF EXISTS analytics_active_org_dashboard_view").executeUpdate();
        entityManager.getEntityManager().createNativeQuery(
                "DROP TABLE IF EXISTS alert_event").executeUpdate();
        entityManager.getEntityManager().createNativeQuery(
                "DROP TABLE IF EXISTS indicator").executeUpdate();
        entityManager.getEntityManager().createNativeQuery(
                "DROP TABLE IF EXISTS sys_org").executeUpdate();
        entityManager.getEntityManager().createNativeQuery("""
                CREATE TABLE sys_org (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    type VARCHAR(50) NOT NULL,
                    is_active BOOLEAN NOT NULL,
                    sort_order INT NOT NULL,
                    level INT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    is_deleted BOOLEAN NOT NULL
                )
                """).executeUpdate();
        entityManager.getEntityManager().createNativeQuery("""
                CREATE TABLE indicator (
                    id BIGINT PRIMARY KEY,
                    target_org_id BIGINT NOT NULL,
                    owner_org_id BIGINT NOT NULL,
                    indicator_desc TEXT NOT NULL,
                    weight_percent NUMERIC(10, 2) NOT NULL,
                    sort_order INT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    type VARCHAR(50) NOT NULL,
                    progress INT,
                    is_deleted BOOLEAN NOT NULL,
                    status VARCHAR(20) NOT NULL
                )
                """).executeUpdate();
        entityManager.getEntityManager().createNativeQuery("""
                CREATE TABLE alert_event (
                    event_id BIGINT PRIMARY KEY,
                    indicator_id BIGINT NOT NULL,
                    rule_id BIGINT NOT NULL,
                    window_id BIGINT NOT NULL,
                    actual_percent NUMERIC(10, 2) NOT NULL,
                    expected_percent NUMERIC(10, 2) NOT NULL,
                    gap_percent NUMERIC(10, 2) NOT NULL,
                    severity VARCHAR(20) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """).executeUpdate();
        entityManager.getEntityManager().createNativeQuery("""
                CREATE VIEW analytics_indicator_dashboard_view AS
                SELECT
                    id,
                    target_org_id,
                    owner_org_id,
                    indicator_desc,
                    progress,
                    updated_at,
                    type,
                    status
                FROM indicator
                WHERE is_deleted = false
                """).executeUpdate();
        entityManager.getEntityManager().createNativeQuery("""
                CREATE VIEW analytics_unresolved_alert_dashboard_view AS
                SELECT
                    event_id AS id,
                    indicator_id,
                    severity,
                    status
                FROM alert_event
                WHERE status <> 'RESOLVED'
                """).executeUpdate();
        entityManager.getEntityManager().createNativeQuery("""
                CREATE VIEW analytics_active_org_dashboard_view AS
                SELECT
                    id,
                    name
                FROM sys_org
                WHERE is_deleted = false AND is_active = true
                """).executeUpdate();

        entityManager.getEntityManager().createNativeQuery("""
                INSERT INTO sys_org (id, name, type, is_active, sort_order, level, created_at, updated_at, is_deleted)
                VALUES (1, '部门A', 'functional', true, 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, false)
                """).executeUpdate();
        entityManager.getEntityManager().createNativeQuery("""
                INSERT INTO sys_org (id, name, type, is_active, sort_order, level, created_at, updated_at, is_deleted)
                VALUES (2, '部门B', 'functional', true, 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, false)
                """).executeUpdate();
        entityManager.getEntityManager().createNativeQuery("""
                INSERT INTO indicator (id, target_org_id, owner_org_id, indicator_desc, weight_percent, sort_order, created_at, updated_at, type, progress, is_deleted, status)
                VALUES (101, 1, 1, '指标A', 100, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '基础性指标', 80, false, 'COMPLETED')
                """).executeUpdate();
        entityManager.getEntityManager().createNativeQuery("""
                INSERT INTO indicator (id, target_org_id, owner_org_id, indicator_desc, weight_percent, sort_order, created_at, updated_at, type, progress, is_deleted, status)
                VALUES (102, 1, 1, '指标B', 100, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '发展性指标', 60, false, 'IN_PROGRESS')
                """).executeUpdate();
        entityManager.getEntityManager().createNativeQuery("""
                INSERT INTO indicator (id, target_org_id, owner_org_id, indicator_desc, weight_percent, sort_order, created_at, updated_at, type, progress, is_deleted, status)
                VALUES (103, 2, 2, '指标C', 100, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '基础性指标', 50, false, 'COMPLETED')
                """).executeUpdate();
        entityManager.getEntityManager().createNativeQuery("""
                INSERT INTO alert_event (event_id, indicator_id, rule_id, window_id, actual_percent, expected_percent, gap_percent, severity, status, created_at, updated_at)
                VALUES (1001, 101, 1, 1, 10.00, 20.00, 10.00, 'CRITICAL', 'OPEN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """).executeUpdate();
        entityManager.getEntityManager().createNativeQuery("""
                INSERT INTO alert_event (event_id, indicator_id, rule_id, window_id, actual_percent, expected_percent, gap_percent, severity, status, created_at, updated_at)
                VALUES (1002, 102, 1, 1, 10.00, 20.00, 10.00, 'WARNING', 'RESOLVED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """).executeUpdate();
        entityManager.getEntityManager().createNativeQuery("""
                INSERT INTO alert_event (event_id, indicator_id, rule_id, window_id, actual_percent, expected_percent, gap_percent, severity, status, created_at, updated_at)
                VALUES (1003, 103, 1, 1, 10.00, 20.00, 10.00, 'INFO', 'IN_PROGRESS', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """).executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }
}
