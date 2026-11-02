package com.sism.analytics.infrastructure.repository;

import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeLevelCountRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeLevelMonthlyCountRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(NativeCollegeLevelQueryRepository.class)
@DisplayName("NativeCollegeLevelQueryRepository 集成测试")
class NativeCollegeLevelQueryRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private NativeCollegeLevelQueryRepository repository;

    @BeforeEach
    void resetTables() {
        // 同一 JVM 内可能共享嵌入式 H2（其他原生查询测试会建同名表/视图），先清场再建
        exec("DROP VIEW IF EXISTS analytics_indicator_dashboard_view");
        exec("DROP VIEW IF EXISTS analytics_unresolved_alert_dashboard_view");
        exec("DROP VIEW IF EXISTS analytics_active_org_dashboard_view");
        exec("DROP TABLE IF EXISTS plan_report_indicator");
        exec("DROP TABLE IF EXISTS plan_report");
        exec("DROP TABLE IF EXISTS indicator");
        exec("DROP TABLE IF EXISTS sys_org");
        exec("DROP TABLE IF EXISTS alert_event");

        exec("""
                CREATE TABLE plan_report (
                    id BIGINT PRIMARY KEY,
                    plan_id BIGINT NOT NULL,
                    report_month VARCHAR(6) NOT NULL,
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
                    appraisal_level VARCHAR(16)
                )
                """);
        exec("""
                CREATE TABLE indicator (
                    id BIGINT PRIMARY KEY,
                    target_org_id BIGINT NOT NULL,
                    is_deleted BOOLEAN NOT NULL,
                    status VARCHAR(20) NOT NULL
                )
                """);
        exec("""
                CREATE TABLE sys_org (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    type VARCHAR(50) NOT NULL,
                    is_deleted BOOLEAN NOT NULL
                )
                """);

        // 组织：1/2/4 二级学院，3 职能部门
        insertOrg(1, "计算机学院", "academic");
        insertOrg(2, "电子学院", "academic");
        insertOrg(3, "学生处", "functional");
        insertOrg(4, "无数据学院", "academic");
        // 指标：106 软删除，107 非 DISTRIBUTED
        insertIndicator(101, 1, false, "DISTRIBUTED");
        insertIndicator(102, 1, false, "DISTRIBUTED");
        insertIndicator(103, 1, false, "DISTRIBUTED");
        insertIndicator(104, 2, false, "DISTRIBUTED");
        insertIndicator(105, 3, false, "DISTRIBUTED");
        insertIndicator(106, 1, true, "DISTRIBUTED");
        insertIndicator(107, 1, false, "DRAFT");
    }

    @Test
    @DisplayName("单月聚合应只算已提交/已审批月报并排除草稿/驳回/NULL等级/非学院/软删与非在发指标")
    void fetchCollegeLevelCountsShouldApplyConfirmedScope() {
        // 202610：APPROVED 主报告
        insertReport(11, "202610", "APPROVED", false);
        insertPlanReportIndicator(111, 11, 101, "AHEAD");
        insertPlanReportIndicator(112, 11, 102, "NORMAL");
        insertPlanReportIndicator(113, 11, 103, null); // appraisal_level NULL 不计数
        insertPlanReportIndicator(114, 11, 105, "AHEAD"); // 职能部门不计入
        insertPlanReportIndicator(115, 11, 106, "AHEAD"); // 软删指标不计入
        insertPlanReportIndicator(116, 11, 107, "AHEAD"); // 非 DISTRIBUTED 指标不计入
        // 202610：DRAFT 草稿不计入
        insertReport(12, "202610", "DRAFT", false);
        insertPlanReportIndicator(121, 12, 104, "AHEAD");
        // 202610：REJECTED 不计入
        insertReport(13, "202610", "REJECTED", false);
        insertPlanReportIndicator(131, 13, 101, "AHEAD");
        // 202610：IN_REVIEW 是 SUBMITTED 的 legacy 落库别名，按已提交口径计入
        insertReport(14, "202610", "IN_REVIEW", false);
        insertPlanReportIndicator(141, 14, 104, "DELAYED");
        // 202610：软删月报不计入
        insertReport(15, "202610", "APPROVED", true);
        insertPlanReportIndicator(151, 15, 101, "AHEAD");

        List<CollegeLevelCountRow> rows = repository.fetchCollegeLevelCounts("202610");

        Map<Long, CollegeLevelCountRow> byOrg = rows.stream()
                .collect(Collectors.toMap(CollegeLevelCountRow::orgId, Function.identity()));
        // 未上报学院（4 无数据学院）与非学院组织（3 学生处）不出现
        assertThat(byOrg).containsOnlyKeys(1L, 2L);

        CollegeLevelCountRow college1 = byOrg.get(1L);
        assertThat(college1.orgName()).isEqualTo("计算机学院");
        assertThat(college1.ahead()).isEqualTo(1L);
        assertThat(college1.normal()).isEqualTo(1L);
        assertThat(college1.delayed()).isEqualTo(0L);
        assertThat(college1.total()).isEqualTo(2L);

        CollegeLevelCountRow college2 = byOrg.get(2L);
        assertThat(college2.orgName()).isEqualTo("电子学院");
        assertThat(college2.ahead()).isEqualTo(0L); // 草稿里的 AHEAD 不计入
        assertThat(college2.delayed()).isEqualTo(1L); // legacy IN_REVIEW 按 SUBMITTED 口径计入
        assertThat(college2.total()).isEqualTo(1L);
    }

    @Test
    @DisplayName("单月聚合应按 report_month 精确过滤其他月份数据")
    void fetchCollegeLevelCountsShouldFilterByExactMonth() {
        insertReport(21, "202610", "APPROVED", false);
        insertPlanReportIndicator(211, 21, 101, "AHEAD");
        insertReport(22, "202611", "APPROVED", false);
        insertPlanReportIndicator(221, 22, 101, "DELAYED");

        List<CollegeLevelCountRow> october = repository.fetchCollegeLevelCounts("202610");

        assertThat(october).hasSize(1);
        assertThat(october.get(0).orgId()).isEqualTo(1L);
        assertThat(october.get(0).ahead()).isEqualTo(1L);
        assertThat(october.get(0).delayed()).isEqualTo(0L);
    }

    @Test
    @DisplayName("trend 应只含有数据月份且年份区间外数据不出现")
    void fetchCollegeLevelMonthlyCountsShouldOnlyIncludeMonthsWithData() {
        insertReport(31, "202610", "APPROVED", false);
        insertPlanReportIndicator(311, 31, 101, "AHEAD");
        // 202612：最晚有数据月
        insertReport(32, "202612", "APPROVED", false);
        insertPlanReportIndicator(321, 32, 101, "NORMAL");
        insertPlanReportIndicator(322, 32, 104, "DELAYED");
        // 202611：只有草稿，该月不应出现
        insertReport(33, "202611", "DRAFT", false);
        insertPlanReportIndicator(331, 33, 101, "AHEAD");
        // 上一年数据不应进入 2026 区间
        insertReport(34, "202511", "APPROVED", false);
        insertPlanReportIndicator(341, 34, 101, "DELAYED");

        List<CollegeLevelMonthlyCountRow> rows = repository.fetchCollegeLevelMonthlyCounts("202601", "202612");

        // 1~9 月无数据不出现，只有 10 与 12 有数据月出现
        assertThat(rows.stream().map(CollegeLevelMonthlyCountRow::month).distinct().toList())
                .containsExactly(10, 12);

        Map<Integer, List<CollegeLevelMonthlyCountRow>> byMonth = rows.stream()
                .collect(Collectors.groupingBy(CollegeLevelMonthlyCountRow::month));
        assertThat(byMonth.get(10)).hasSize(1);
        assertThat(byMonth.get(10).get(0).orgId()).isEqualTo(1L);
        assertThat(byMonth.get(10).get(0).ahead()).isEqualTo(1L);

        assertThat(byMonth.get(12)).hasSize(2);
        // 与模块既有 department-progress 一致，组织按名称升序：电子学院 < 计算机学院
        assertThat(byMonth.get(12)).extracting(CollegeLevelMonthlyCountRow::orgId)
                .containsExactly(2L, 1L);
        assertThat(byMonth.get(12).get(0).delayed()).isEqualTo(1L);
        assertThat(byMonth.get(12).get(1).normal()).isEqualTo(1L);
    }

    private void exec(String sql) {
        entityManager.getEntityManager().createNativeQuery(sql).executeUpdate();
    }

    private void insertOrg(long id, String name, String type) {
        exec("INSERT INTO sys_org (id, name, type, is_deleted) VALUES ("
                + id + ", '" + name + "', '" + type + "', false)");
    }

    private void insertIndicator(long id, long targetOrgId, boolean deleted, String status) {
        exec("INSERT INTO indicator (id, target_org_id, is_deleted, status) VALUES ("
                + id + ", " + targetOrgId + ", " + deleted + ", '" + status + "')");
    }

    private void insertReport(long id, String reportMonth, String status, boolean deleted) {
        exec("INSERT INTO plan_report (id, plan_id, report_month, status, is_deleted) VALUES ("
                + id + ", 1, '" + reportMonth + "', '" + status + "', " + deleted + ")");
    }

    private void insertPlanReportIndicator(long id, long reportId, long indicatorId, String appraisalLevel) {
        String level = appraisalLevel == null ? "NULL" : "'" + appraisalLevel + "'";
        exec("INSERT INTO plan_report_indicator (id, report_id, indicator_id, progress, appraisal_level) VALUES ("
                + id + ", " + reportId + ", " + indicatorId + ", 50, " + level + ")");
    }
}
