package com.sism.analytics.infrastructure.repository;

import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeDispatchCountRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeLevelCountRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeLevelMonthlyCountRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeReportedMonthRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.DelayedIndicatorRow;
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
                    indicator_desc VARCHAR(500)
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
        // 按年过滤链路：indicator → sys_task → plan → cycle.year（与 findByYear 同构）
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

        // 组织：1/2/4 二级学院，3 职能部门，5 战略发展部（学院指标的 owner）
        insertOrg(1, "计算机学院", "academic");
        insertOrg(2, "电子学院", "academic");
        insertOrg(3, "学生处", "functional");
        insertOrg(4, "无数据学院", "academic");
        insertOrg(5, "战略发展部", "functional");
        // 指标：106 软删除，107 非 DISTRIBUTED；均挂在 cycle 2026
        insertIndicator(101, 1, 5, false, "DISTRIBUTED");
        insertIndicator(102, 1, 5, false, "DISTRIBUTED");
        insertIndicator(103, 1, 5, false, "DISTRIBUTED");
        insertIndicator(104, 2, 5, false, "DISTRIBUTED");
        insertIndicator(105, 3, 5, false, "DISTRIBUTED");
        insertIndicator(106, 1, 5, true, "DISTRIBUTED");
        insertIndicator(107, 1, 5, false, "DRAFT");
        for (long id = 101; id <= 107; id++) {
            insertIndicatorYearChain(id, 2026);
        }
    }

    @Test
    @DisplayName("单月聚合应只算已提交/已审批月报并排除草稿/驳回/两级等级均NULL/非学院/软删与非在发指标")
    void fetchCollegeLevelCountsShouldApplyConfirmedScope() {
        // 202610：APPROVED 主报告
        insertReport(11, "202610", "APPROVED", false, 1L);
        insertPlanReportIndicator(111, 11, 101, "AHEAD", "NORMAL"); // appraisal 优先于 self
        insertPlanReportIndicator(112, 11, 102, "NORMAL", null);    // appraisal 空回退 self
        insertPlanReportIndicator(113, 11, 103, null, null);        // 两级均空不计数
        insertPlanReportIndicator(114, 11, 105, "AHEAD", null);     // 职能部门不计入
        insertPlanReportIndicator(115, 11, 106, "AHEAD", null);     // 软删指标不计入
        insertPlanReportIndicator(116, 11, 107, "AHEAD", null);     // 非 DISTRIBUTED 指标不计入
        // 202610：DRAFT 草稿不计入
        insertReport(12, "202610", "DRAFT", false, 2L);
        insertPlanReportIndicator(121, 12, 104, "AHEAD", null);
        // 202610：REJECTED 不计入
        insertReport(13, "202610", "REJECTED", false, 1L);
        insertPlanReportIndicator(131, 13, 101, "AHEAD", null);
        // 202610：IN_REVIEW 是 SUBMITTED 的 legacy 落库别名，按已提交口径计入
        insertReport(14, "202610", "IN_REVIEW", false, 2L);
        insertPlanReportIndicator(141, 14, 104, "DELAYED", null);
        // 202610：软删月报不计入
        insertReport(15, "202610", "APPROVED", true, 1L);
        insertPlanReportIndicator(151, 15, 101, "AHEAD", null);

        List<CollegeLevelCountRow> rows = repository.fetchCollegeLevelCounts("202610");

        Map<Long, CollegeLevelCountRow> byOrg = rows.stream()
                .collect(Collectors.toMap(CollegeLevelCountRow::orgId, Function.identity()));
        // 未上报学院（4 无数据学院）与非学院组织（3 学生处）不出现
        assertThat(byOrg).containsOnlyKeys(1L, 2L);

        CollegeLevelCountRow college1 = byOrg.get(1L);
        assertThat(college1.orgName()).isEqualTo("计算机学院");
        // 101：appraisal=AHEAD 优先（self=NORMAL 被忽略）；102：appraisal 空回退 self=NORMAL
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
        insertReport(21, "202610", "APPROVED", false, 1L);
        insertPlanReportIndicator(211, 21, 101, "AHEAD", null);
        insertReport(22, "202611", "APPROVED", false, 1L);
        insertPlanReportIndicator(221, 22, 101, "DELAYED", null);

        List<CollegeLevelCountRow> october = repository.fetchCollegeLevelCounts("202610");

        assertThat(october).hasSize(1);
        assertThat(october.get(0).orgId()).isEqualTo(1L);
        assertThat(october.get(0).ahead()).isEqualTo(1L);
        assertThat(october.get(0).delayed()).isEqualTo(0L);
    }

    @Test
    @DisplayName("trend 应只含有可计数等级的月份且年份区间外数据不出现")
    void fetchCollegeLevelMonthlyCountsShouldOnlyIncludeMonthsWithData() {
        insertReport(31, "202610", "APPROVED", false, 1L);
        insertPlanReportIndicator(311, 31, 101, "AHEAD", null);
        // 202612：最晚有数据月
        insertReport(32, "202612", "APPROVED", false, 1L);
        insertPlanReportIndicator(321, 32, 101, "NORMAL", null);
        insertPlanReportIndicator(322, 32, 104, "DELAYED", null);
        // 202611：只有草稿，该月不应出现
        insertReport(33, "202611", "DRAFT", false, 1L);
        insertPlanReportIndicator(331, 33, 101, "AHEAD", null);
        // 202611：两级等级均 NULL 的已提交明细不产生该月行
        insertReport(35, "202611", "APPROVED", false, 1L);
        insertPlanReportIndicator(351, 35, 102, null, null);
        // 上一年数据不应进入 2026 区间
        insertReport(34, "202511", "APPROVED", false, 1L);
        insertPlanReportIndicator(341, 34, 101, "DELAYED", null);

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

    @Test
    @DisplayName("下发计数应按 cycle.year 过滤年份且区分已下发/未下发（DRAFT/PENDING）")
    void fetchCollegeDispatchCountsShouldFilterByYearAndStatus() {
        // 当年（2026）：org1 有 3 个 DISTRIBUTED + 1 个 DRAFT；org2 有 1 个 DISTRIBUTED
        // 上年（2025）指标不应计入
        insertIndicator(201, 1, 5, false, "DISTRIBUTED");
        insertIndicatorYearChain(201, 2025);
        insertIndicator(202, 1, 5, false, "PENDING");
        insertIndicatorYearChain(202, 2026);
        // 软删指标不计入
        insertIndicator(203, 1, 5, true, "DISTRIBUTED");
        insertIndicatorYearChain(203, 2026);

        List<CollegeDispatchCountRow> rows2026 = repository.fetchCollegeDispatchCounts(2026);

        Map<Long, CollegeDispatchCountRow> byOrg = rows2026.stream()
                .collect(Collectors.toMap(CollegeDispatchCountRow::orgId, Function.identity()));
        // 只统计学院（org.type=academic）；指向职能部门的 105（org3）不计入
        assertThat(byOrg).containsOnlyKeys(1L, 2L);
        assertThat(byOrg.get(1L).dispatchedCount()).isEqualTo(3L); // 101/102/103
        assertThat(byOrg.get(1L).undispatchedCount()).isEqualTo(2L); // 107 DRAFT + 202 PENDING（2025/软删不计）
        assertThat(byOrg.get(2L).dispatchedCount()).isEqualTo(1L); // 104
        assertThat(byOrg.get(2L).undispatchedCount()).isEqualTo(0L);

        // 2025 视角：只有 201
        List<CollegeDispatchCountRow> rows2025 = repository.fetchCollegeDispatchCounts(2025);
        assertThat(rows2025).hasSize(1);
        assertThat(rows2025.get(0).orgId()).isEqualTo(1L);
        assertThat(rows2025.get(0).dispatchedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("已上报月份应只含已提交/已审批月报（DRAFT/REJECTED 不算已上报）且按月提取")
    void fetchCollegeReportedMonthsShouldApplySubmittedScope() {
        insertReport(41, "202610", "APPROVED", false, 1L);
        insertReport(42, "202610", "SUBMITTED", false, 2L);
        insertReport(43, "202610", "IN_REVIEW", false, 1L); // legacy 别名算已上报（同 org 去重）
        insertReport(44, "202610", "DRAFT", false, 2L); // 草稿不算
        insertReport(45, "202610", "REJECTED", false, 4L); // 驳回不算
        insertReport(46, "202611", "APPROVED", false, 1L);
        insertReport(47, "202611", "APPROVED", true, 2L); // 软删不算
        insertReport(48, "202512", "APPROVED", false, 1L); // 区间外

        List<CollegeReportedMonthRow> rows =
                repository.fetchCollegeReportedMonths("202601", "202612");

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(CollegeReportedMonthRow::month)
                .containsExactly(10, 10, 11);
        // 同月内按组织名升序：电子学院 < 计算机学院
        assertThat(rows.get(0).orgId()).isEqualTo(2L); // SUBMITTED 算已上报
        assertThat(rows.get(0).orgName()).isEqualTo("电子学院");
        assertThat(rows.get(1).orgId()).isEqualTo(1L); // APPROVED + IN_REVIEW 同 org 去重
        assertThat(rows.get(1).orgName()).isEqualTo("计算机学院");
        assertThat(rows.get(2).orgId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("滞后清单应只取鉴定 DELAYED（appraisal 优先 self 兜底）并带来源部门与自评/鉴定留痕")
    void fetchDelayedIndicatorsShouldApplyEffectiveDelayLevel() {
        // 11：APPROVED，101（appraisal=DELAYED）入清单
        insertReport(51, "202610", "APPROVED", false, 1L);
        insertPlanReportIndicator(511, 51, 101, "DELAYED", "DELAYED");
        // 52：IN_REVIEW（legacy 已提交），102（appraisal 空、self=DELAYED）入清单
        insertReport(52, "202610", "IN_REVIEW", false, 1L);
        insertPlanReportIndicator(521, 52, 102, null, "DELAYED");
        // 53：非 DELAYED 不入清单
        insertReport(53, "202610", "APPROVED", false, 2L);
        insertPlanReportIndicator(531, 53, 104, "NORMAL", "AHEAD");
        // 54：草稿不入清单
        insertReport(54, "202610", "DRAFT", false, 1L);
        insertPlanReportIndicator(541, 54, 101, "DELAYED", null);
        // 55：其他月份不入清单
        insertReport(55, "202611", "APPROVED", false, 1L);
        insertPlanReportIndicator(551, 55, 101, "DELAYED", null);

        List<DelayedIndicatorRow> rows = repository.fetchDelayedIndicators("202610");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).indicatorId()).isEqualTo(101L);
        assertThat(rows.get(0).orgName()).isEqualTo("计算机学院");
        assertThat(rows.get(0).ownerOrgName()).isEqualTo("战略发展部");
        assertThat(rows.get(0).appraisalLevel()).isEqualTo("DELAYED");
        assertThat(rows.get(0).selfRating()).isEqualTo("DELAYED");
        assertThat(rows.get(1).indicatorId()).isEqualTo(102L);
        assertThat(rows.get(1).appraisalLevel()).isNull();
        assertThat(rows.get(1).selfRating()).isEqualTo("DELAYED");
        assertThat(rows.get(1).ownerOrgName()).isEqualTo("战略发展部");
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

    private void insertIndicator(long id, long targetOrgId, long ownerOrgId, boolean deleted, String status) {
        exec("INSERT INTO indicator (id, task_id, owner_org_id, target_org_id, is_deleted, status, indicator_desc) VALUES ("
                + id + ", " + id + ", " + ownerOrgId + ", " + targetOrgId + ", " + deleted + ", '" + status
                + "', '指标" + id + "描述')");
    }

    /** 建立 indicator → sys_task → plan → cycle 关系链（同一 indicator 复用主键） */
    private void insertIndicatorYearChain(long indicatorId, int year) {
        exec("INSERT INTO cycle (id, \"year\") VALUES (" + indicatorId + ", " + year + ")");
        exec("INSERT INTO plan (id, cycle_id) VALUES (" + indicatorId + ", " + indicatorId + ")");
        exec("INSERT INTO sys_task (task_id, plan_id) VALUES (" + indicatorId + ", " + indicatorId + ")");
    }

    private void insertReport(long id, String reportMonth, String status, boolean deleted, Long reportOrgId) {
        exec("INSERT INTO plan_report (id, plan_id, report_month, report_org_type, report_org_id, status, is_deleted) VALUES ("
                + id + ", 1, '" + reportMonth + "', 'COLLEGE', "
                + (reportOrgId == null ? "NULL" : reportOrgId) + ", '" + status + "', " + deleted + ")");
    }

    private void insertPlanReportIndicator(long id, long reportId, long indicatorId,
                                           String appraisalLevel, String selfRating) {
        String appraisal = appraisalLevel == null ? "NULL" : "'" + appraisalLevel + "'";
        String self = selfRating == null ? "NULL" : "'" + selfRating + "'";
        exec("INSERT INTO plan_report_indicator (id, report_id, indicator_id, progress, self_rating, appraisal_level) VALUES ("
                + id + ", " + reportId + ", " + indicatorId + ", 50, " + self + ", " + appraisal + ")");
    }
}
