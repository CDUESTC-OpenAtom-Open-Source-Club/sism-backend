package com.sism.analytics.infrastructure.repository;

import com.sism.analytics.domain.repository.AppraisalSummaryQueryRepository.AppraisalRow;
import com.sism.analytics.domain.repository.AppraisalSummaryQueryRepository.DispatchCountRow;
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
 * NativeAppraisalSummaryQueryRepository 集成测试。
 *
 * <p>2026-11-18 拍板口径的数据源约束：不再要求 appraisal_level IS NOT NULL
 * （审批中月报的明细行是「当月已填报」的证据），行带 plan_report.status；
 * DRAFT/REJECTED 月报不捞取，鉴定值由服务层只从 APPROVED 行聚合。</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(NativeAppraisalSummaryQueryRepository.class)
@DisplayName("NativeAppraisalSummaryQueryRepository 集成测试")
class NativeAppraisalSummaryQueryRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private NativeAppraisalSummaryQueryRepository repository;

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
        exec("DROP TABLE IF EXISTS sys_task");
        exec("DROP TABLE IF EXISTS plan");
        exec("DROP TABLE IF EXISTS cycle");

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
        exec("""
                CREATE TABLE sys_org (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    type VARCHAR(50) NOT NULL,
                    is_deleted BOOLEAN NOT NULL
                )
                """);
        // 已下发计数关系链：indicator → sys_task → plan → cycle.year（与学院口径同构）
        exec("""
                CREATE TABLE sys_task (
                    task_id BIGINT PRIMARY KEY,
                    plan_id BIGINT NOT NULL
                )
                """);
        exec("""
                CREATE TABLE plan (
                    id BIGINT PRIMARY KEY,
                    cycle_id BIGINT NOT NULL
                )
                """);
        exec("""
                CREATE TABLE cycle (
                    id BIGINT PRIMARY KEY,
                    "year" INT NOT NULL
                )
                """);

        insertOrg(1, "学生处", "functional");
        insertOrg(2, "计算机学院", "academic");
        insertOrg(3, "预算部", "functional"); // 学院月报的归口职能部门（B2 修复）
        // 指标 101 target=职能部门（学生处）、102 target=学院；103 软删除、104 非在发
        insertIndicator(101, 1, false, "DISTRIBUTED");
        insertIndicator(102, 2, false, "DISTRIBUTED");
        insertIndicator(103, 1, true, "DISTRIBUTED");
        insertIndicator(104, 1, false, "DRAFT");
        for (long id = 101; id <= 104; id++) {
            insertIndicatorYearChain(id, 2026);
        }
    }

    @Test
    @DisplayName("应捞取 SUBMITTED/IN_REVIEW/APPROVED 月报明细（含等级为 NULL 的审批中行）并带月报状态")
    void fetchAppraisalRowsShouldIncludeUnapprovedRowsWithStatus() {
        // 职能部门线：10 月 APPROVED（有鉴定），11 月 SUBMITTED（审批中，等级 NULL）
        insertReport(11, "202610", "FUNC_DEPT", 1L, "APPROVED", false);
        insertPlanReportIndicator(111, 11, 101, "DELAYED");
        insertReport(12, "202611", "FUNC_DEPT", 1L, "SUBMITTED", false);
        insertPlanReportIndicator(121, 12, 101, null);
        // 学院线：11 月 IN_REVIEW（legacy 审批中别名），归口预算部（functional_org_id=3）
        insertReport(13, "202611", "COLLEGE", 2L, 3L, "IN_REVIEW", false);
        insertPlanReportIndicator(131, 13, 102, null);
        // 草稿与软删月报不捞取
        insertReport(14, "202611", "COLLEGE", 2L, "DRAFT", false);
        insertPlanReportIndicator(141, 14, 102, "DELAYED");
        insertReport(15, "202611", "COLLEGE", 2L, "APPROVED", true);
        insertPlanReportIndicator(151, 15, 102, "DELAYED");

        List<AppraisalRow> rows = repository.fetchAppraisalRows("202601", "202612");

        // 审批中行的等级为 NULL 也要出现（monthState=IN_REVIEW 的证据行）
        assertThat(rows).hasSize(3);

        AppraisalRow october = rows.get(0);
        assertThat(october.reportMonth()).isEqualTo("202610");
        assertThat(october.reportStatus()).isEqualTo("APPROVED");
        assertThat(october.reportOrgType()).isEqualTo("FUNC_DEPT");
        assertThat(october.reportOrgName()).isEqualTo("学生处");
        assertThat(october.functionalOrgId()).isNull();
        assertThat(october.functionalOrgName()).isNull();
        assertThat(october.appraisalLevel()).isEqualTo("DELAYED");
        assertThat(october.indicatorId()).isEqualTo(101L);
        assertThat(october.targetOrgId()).isEqualTo(1L);
        assertThat(october.targetOrgName()).isEqualTo("学生处");
        assertThat(october.weightPercent()).isNotNull();

        AppraisalRow novemberSubmitted = rows.get(1);
        assertThat(novemberSubmitted.reportMonth()).isEqualTo("202611");
        assertThat(novemberSubmitted.reportStatus()).isEqualTo("SUBMITTED");
        assertThat(novemberSubmitted.appraisalLevel()).isNull();

        AppraisalRow novemberLegacy = rows.get(2);
        assertThat(novemberLegacy.reportStatus()).isEqualTo("IN_REVIEW");
        assertThat(novemberLegacy.reportOrgType()).isEqualTo("COLLEGE");
        assertThat(novemberLegacy.reportOrgName()).isEqualTo("计算机学院");
        // B2 修复：学院月报带出归口职能部门（JOIN sys_org ON pr.functional_org_id = fo.id）
        assertThat(novemberLegacy.functionalOrgId()).isEqualTo(3L);
        assertThat(novemberLegacy.functionalOrgName()).isEqualTo("预算部");
    }

    @Test
    @DisplayName("月份区间过滤含首尾；软删/非在发指标的明细不捞取")
    void fetchAppraisalRowsShouldFilterMonthRangeAndIndicators() {
        insertReport(21, "202609", "FUNC_DEPT", 1L, "APPROVED", false);
        insertPlanReportIndicator(211, 21, 101, "NORMAL");
        insertReport(22, "202612", "FUNC_DEPT", 1L, "APPROVED", false);
        insertPlanReportIndicator(221, 22, 101, "NORMAL");
        insertReport(23, "202612", "FUNC_DEPT", 1L, "APPROVED", false);
        insertPlanReportIndicator(231, 23, 103, "DELAYED"); // 软删指标
        insertReport(24, "202612", "FUNC_DEPT", 1L, "APPROVED", false);
        insertPlanReportIndicator(241, 24, 104, "DELAYED"); // 非在发指标

        List<AppraisalRow> rows = repository.fetchAppraisalRows("202610", "202612");

        // 9 月在区间外；103/104 指标明细被 JOIN 条件排除；只剩 12 月的 101
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).reportMonth()).isEqualTo("202612");
        assertThat(rows.get(0).indicatorId()).isEqualTo(101L);
    }

    @Test
    @DisplayName("已下发计数按 cycle.year 过滤且不设组织类型过滤（职能部门线/学院线都统计，软删/非在发不计）")
    void fetchDispatchCountsShouldFilterByYearAndCountDistributedOnly() {
        // 2026 新增指标：105 target=学生处 DISTRIBUTED（计入）、106 target=学院 PENDING（不计）、107 上年
        insertIndicator(105, 1, false, "DISTRIBUTED");
        insertIndicatorYearChain(105, 2026);
        insertIndicator(106, 1, false, "PENDING");
        insertIndicatorYearChain(106, 2026);
        insertIndicator(107, 1, false, "DISTRIBUTED");
        insertIndicatorYearChain(107, 2025);
        // 软删指标不计入（103 已在 BeforeEach 建：软删、target=学生处）

        List<DispatchCountRow> rows2026 = repository.fetchDispatchCounts(2026);

        // 职能部门线（学生处 101/105）+ 学院线（计算机学院 102）都统计；103 软删/104 非在发/106 未下发/107 上年不计
        assertThat(rows2026).hasSize(2);
        assertThat(rows2026).extracting(DispatchCountRow::orgId)
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(rows2026.stream().filter(r -> r.orgId() == 1L).findFirst().orElseThrow()
                .dispatchedCount()).isEqualTo(2L);
        assertThat(rows2026.stream().filter(r -> r.orgId() == 2L).findFirst().orElseThrow()
                .dispatchedCount()).isEqualTo(1L);

        // 2025 视角：只有 107（target=学生处）
        List<DispatchCountRow> rows2025 = repository.fetchDispatchCounts(2025);
        assertThat(rows2025).hasSize(1);
        assertThat(rows2025.get(0).orgId()).isEqualTo(1L);
        assertThat(rows2025.get(0).dispatchedCount()).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // 数据装配
    // ------------------------------------------------------------------

    private void exec(String sql) {
        entityManager.getEntityManager().createNativeQuery(sql).executeUpdate();
    }

    private void insertOrg(long id, String name, String type) {
        exec("INSERT INTO sys_org (id, name, type, is_deleted) VALUES ("
                + id + ", '" + name + "', '" + type + "', false)");
    }

    private void insertIndicator(long id, long targetOrgId, boolean deleted, String status) {
        exec("INSERT INTO indicator (id, task_id, owner_org_id, target_org_id, is_deleted, status, indicator_desc, weight_percent) VALUES ("
                + id + ", " + id + ", " + id + ", " + targetOrgId + ", " + deleted + ", '" + status
                + "', '指标" + id + "描述', 10.00)");
    }

    /** 建立 indicator → sys_task → plan → cycle 关系链（同一 indicator 复用主键） */
    private void insertIndicatorYearChain(long indicatorId, int year) {
        exec("INSERT INTO cycle (id, \"year\") VALUES (" + indicatorId + ", " + year + ")");
        exec("INSERT INTO plan (id, cycle_id) VALUES (" + indicatorId + ", " + indicatorId + ")");
        exec("INSERT INTO sys_task (task_id, plan_id) VALUES (" + indicatorId + ", " + indicatorId + ")");
    }

    private void insertReport(long id, String reportMonth, String reportOrgType,
                              long reportOrgId, String status, boolean deleted) {
        insertReport(id, reportMonth, reportOrgType, reportOrgId, null, status, deleted);
    }

    /** 带归口职能部门的月报（学院线 B2 修复：functional_org_id → sys_org.name） */
    private void insertReport(long id, String reportMonth, String reportOrgType,
                              long reportOrgId, Long functionalOrgId, String status, boolean deleted) {
        exec("INSERT INTO plan_report (id, plan_id, report_month, report_org_type, report_org_id, functional_org_id, status, is_deleted) VALUES ("
                + id + ", 1, '" + reportMonth + "', '" + reportOrgType + "', " + reportOrgId
                + ", " + (functionalOrgId == null ? "NULL" : functionalOrgId)
                + ", '" + status + "', " + deleted + ")");
    }

    private void insertPlanReportIndicator(long id, long reportId, long indicatorId, String appraisalLevel) {
        String appraisal = appraisalLevel == null ? "NULL" : "'" + appraisalLevel + "'";
        exec("INSERT INTO plan_report_indicator (id, report_id, indicator_id, progress, self_rating, appraisal_level) VALUES ("
                + id + ", " + reportId + ", " + indicatorId + ", 50, NULL, " + appraisal + ")");
    }
}
