package com.sism.analytics.infrastructure.repository;

import com.sism.analytics.domain.repository.IndicatorLevelQueryRepository.IndicatorLevelRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NativeIndicatorLevelQueryRepository 集成测试（2026-11-27 行级鉴定明细）。
 *
 * <p>数据源约束：只有 APPROVED 月报计入（SUBMITTED/DRAFT/软删不捞），行带
 * self_rating/appraisal_level；行主体 = 指标 target 组织（责任单位口径），
 * orgId 过滤动态拼接（null = 不限组织）。</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(NativeIndicatorLevelQueryRepository.class)
@DisplayName("NativeIndicatorLevelQueryRepository 集成测试")
class NativeIndicatorLevelQueryRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private NativeIndicatorLevelQueryRepository repository;

    @BeforeEach
    void resetTables() {
        exec("DROP TABLE IF EXISTS plan_report_indicator");
        exec("DROP TABLE IF EXISTS plan_report");
        exec("DROP TABLE IF EXISTS indicator");

        exec("""
                CREATE TABLE plan_report (
                    id BIGINT PRIMARY KEY,
                    plan_id BIGINT NOT NULL,
                    report_month VARCHAR(6) NOT NULL,
                    report_org_type VARCHAR(16),
                    report_org_id BIGINT,
                    functional_org_id BIGINT,
                    status VARCHAR(20) NOT NULL,
                    is_deleted BOOLEAN NOT NULL
                )
                """);
        exec("""
                CREATE TABLE plan_report_indicator (
                    id BIGINT PRIMARY KEY,
                    report_id BIGINT NOT NULL,
                    indicator_id BIGINT NOT NULL,
                    progress INT,
                    self_rating VARCHAR(16),
                    appraisal_level VARCHAR(16)
                )
                """);
        exec("""
                CREATE TABLE indicator (
                    id BIGINT PRIMARY KEY,
                    task_id BIGINT,
                    owner_org_id BIGINT,
                    target_org_id BIGINT NOT NULL,
                    is_deleted BOOLEAN NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    indicator_desc VARCHAR(500),
                    weight_percent NUMERIC(5, 2)
                )
                """);

        // 指标 201 target=学院（学院线）、202 target=职能部门（职能部门线）、203 软删、204 非在发
        insertIndicator(201, 2, false, "DISTRIBUTED");
        insertIndicator(202, 1, false, "DISTRIBUTED");
        insertIndicator(203, 2, true, "DISTRIBUTED");
        insertIndicator(204, 2, false, "DRAFT");
    }

    @Test
    @DisplayName("只捞取 APPROVED 月报的明细行，带 self_rating/appraisal_level；软删/非在发指标不捞取")
    void fetchIndicatorLevelsShouldOnlyIncludeApprovedReports() {
        insertReport(11, "202611", 2L, "APPROVED", false);
        insertPlanReportIndicator(111, 11, 201, "DELAYED", "NORMAL");
        insertReport(12, "202611", 2L, "SUBMITTED", false);
        insertPlanReportIndicator(121, 12, 202, null, "AHEAD"); // 审批中不捞
        insertReport(13, "202611", 2L, "DRAFT", false);
        insertPlanReportIndicator(131, 13, 201, "AHEAD", "AHEAD"); // 草稿不捞
        insertPlanReportIndicator(141, 11, 203, "AHEAD", "AHEAD"); // 软删指标不捞
        insertPlanReportIndicator(151, 11, 204, "AHEAD", "AHEAD"); // 非在发指标不捞

        List<IndicatorLevelRow> rows = repository.fetchIndicatorLevels("202601", "202612", null);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).indicatorId()).isEqualTo(201L);
        assertThat(rows.get(0).reportMonth()).isEqualTo("202611");
        assertThat(rows.get(0).selfRating()).isEqualTo("DELAYED");
        assertThat(rows.get(0).appraisalLevel()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("orgId 过滤按指标 target 组织（责任单位口径）；null = 不限组织")
    void fetchIndicatorLevelsShouldFilterByTargetOrg() {
        insertReport(11, "202611", 2L, "APPROVED", false);
        insertPlanReportIndicator(111, 11, 201, null, "NORMAL"); // target=学院（2）
        insertReport(12, "202611", 1L, "APPROVED", false);
        insertPlanReportIndicator(121, 12, 202, null, "AHEAD"); // target=职能部门（1）

        List<IndicatorLevelRow> collegeRows = repository.fetchIndicatorLevels("202601", "202612", 2L);
        assertThat(collegeRows).hasSize(1);
        assertThat(collegeRows.get(0).indicatorId()).isEqualTo(201L);

        List<IndicatorLevelRow> allRows = repository.fetchIndicatorLevels("202601", "202612", null);
        assertThat(allRows).hasSize(2);
    }

    @Test
    @DisplayName("月份区间含首尾过滤")
    void fetchIndicatorLevelsShouldFilterMonthRange() {
        insertReport(11, "202610", 2L, "APPROVED", false);
        insertPlanReportIndicator(111, 11, 201, null, "AHEAD");
        insertReport(12, "202612", 2L, "APPROVED", false);
        insertPlanReportIndicator(121, 12, 201, null, "NORMAL");

        List<IndicatorLevelRow> rows = repository.fetchIndicatorLevels("202611", "202611", null);

        assertThat(rows).isEmpty();
    }

    private void exec(String sql) {
        entityManager.getEntityManager().createNativeQuery(sql).executeUpdate();
    }

    private void insertIndicator(long id, long targetOrgId, boolean deleted, String status) {
        exec("INSERT INTO indicator (id, task_id, owner_org_id, target_org_id, is_deleted, status, indicator_desc, weight_percent) VALUES ("
                + id + ", " + id + ", " + id + ", " + targetOrgId + ", " + deleted + ", '" + status
                + "', '指标" + id + "描述', 10.00)");
    }

    private void insertReport(long id, String reportMonth, long reportOrgId, String status, boolean deleted) {
        exec("INSERT INTO plan_report (id, plan_id, report_month, report_org_type, report_org_id, functional_org_id, status, is_deleted) VALUES ("
                + id + ", 1, '" + reportMonth + "', 'COLLEGE', " + reportOrgId
                + ", NULL, '" + status + "', " + deleted + ")");
    }

    private void insertPlanReportIndicator(
            long id, long reportId, long indicatorId, String selfRating, String appraisalLevel) {
        exec("INSERT INTO plan_report_indicator (id, report_id, indicator_id, progress, self_rating, appraisal_level) VALUES ("
                + id + ", " + reportId + ", " + indicatorId + ", 50, "
                + (selfRating == null ? "NULL" : "'" + selfRating + "'") + ", "
                + (appraisalLevel == null ? "NULL" : "'" + appraisalLevel + "'") + ")");
    }
}
