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
        // 108 的 target=职能部门（学生处）——滞后清单全量口径（2026-11-18 拍板）用例
        insertIndicator(101, 1, 5, false, "DISTRIBUTED");
        insertIndicator(102, 1, 5, false, "DISTRIBUTED");
        insertIndicator(103, 1, 5, false, "DISTRIBUTED");
        insertIndicator(104, 2, 5, false, "DISTRIBUTED");
        insertIndicator(105, 3, 5, false, "DISTRIBUTED");
        insertIndicator(106, 1, 5, true, "DISTRIBUTED");
        insertIndicator(107, 1, 5, false, "DRAFT");
        insertIndicator(108, 3, 5, false, "DISTRIBUTED");
        for (long id = 101; id <= 108; id++) {
            insertIndicatorYearChain(id, 2026);
        }
    }

    @Test
    @DisplayName("单月聚合只认 APPROVED 月报（2026-11-18 按月快照：审批中/草稿/驳回/软删/两级等级均NULL/非学院/非在发指标都不计）")
    void fetchCollegeLevelCountsShouldApplyApprovedOnlyScope() {
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
        // 202610：IN_REVIEW（legacy 已提交别名）= 审批中，按 2026-11-18 快照口径不产生鉴定值
        insertReport(14, "202610", "IN_REVIEW", false, 2L);
        insertPlanReportIndicator(141, 14, 104, "DELAYED", null);
        // 202610：SUBMITTED 同样是审批中，不计入
        insertReport(17, "202610", "SUBMITTED", false, 4L);
        insertPlanReportIndicator(171, 17, 101, "DELAYED", null);
        // 202610：软删月报不计入
        insertReport(15, "202610", "APPROVED", true, 1L);
        insertPlanReportIndicator(151, 15, 101, "AHEAD", null);
        // 202610：org2 的 APPROVED 报告是唯一计入电子学院的鉴定值
        insertReport(16, "202610", "APPROVED", false, 2L);
        insertPlanReportIndicator(161, 16, 104, "DELAYED", null);

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
        assertThat(college2.ahead()).isEqualTo(0L);        // 草稿里的 AHEAD 不计入
        assertThat(college2.delayed()).isEqualTo(1L);      // 只有 APPROVED 月报计入（IN_REVIEW 的 DELAYED 不算）
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
    @DisplayName("trend 应只含有可计数等级（APPROVED）的月份且年份区间外数据不出现")
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
        // 202611：两级等级均 NULL 的 APPROVED 明细不产生该月行
        insertReport(35, "202611", "APPROVED", false, 1L);
        insertPlanReportIndicator(351, 35, 102, null, null);
        // 202611：审批中（IN_REVIEW）月份按 2026-11-18 快照口径无鉴定值，该月不出现
        insertReport(36, "202611", "IN_REVIEW", false, 2L);
        insertPlanReportIndicator(361, 36, 104, "DELAYED", null);
        // 上一年数据不应进入 2026 区间
        insertReport(34, "202511", "APPROVED", false, 1L);
        insertPlanReportIndicator(341, 34, 101, "DELAYED", null);

        List<CollegeLevelMonthlyCountRow> rows = repository.fetchCollegeLevelMonthlyCounts("202601", "202612");

        // 1~9 月无数据不出现，只有 10 与 12 有数据月出现（11 月全是草稿/审批中/无鉴定）
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
        // 只统计学院（org.type=academic）；指向职能部门的 105/108（org3）不计入
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
    @DisplayName("已上报月份应带月报状态标记：APPROVED 优先于审批中（SUBMITTED/IN_REVIEW），草稿/驳回不算已上报")
    void fetchCollegeReportedMonthsShouldApplySubmittedScope() {
        insertReport(41, "202610", "APPROVED", false, 1L);
        insertReport(42, "202610", "SUBMITTED", false, 2L);
        insertReport(43, "202610", "IN_REVIEW", false, 1L); // legacy 别名算已上报（同 org 去重，APPROVED 优先）
        insertReport(44, "202610", "DRAFT", false, 2L); // 草稿不算
        insertReport(45, "202610", "REJECTED", false, 4L); // 驳回不算
        insertReport(46, "202611", "APPROVED", false, 1L);
        insertReport(47, "202611", "APPROVED", true, 2L); // 软删不算
        insertReport(49, "202611", "SUBMITTED", false, 2L); // org2 在 11 月仅审批中 → IN_REVIEW
        insertReport(48, "202512", "APPROVED", false, 1L); // 区间外

        List<CollegeReportedMonthRow> rows =
                repository.fetchCollegeReportedMonths("202601", "202612");

        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(CollegeReportedMonthRow::month)
                .containsExactly(10, 10, 11, 11);
        // 同月内按组织名升序：电子学院 < 计算机学院
        assertThat(rows.get(0).orgId()).isEqualTo(2L); // SUBMITTED 算已上报
        assertThat(rows.get(0).orgName()).isEqualTo("电子学院");
        assertThat(rows.get(0).monthState()).isEqualTo("IN_REVIEW"); // 仅审批中 → IN_REVIEW
        assertThat(rows.get(1).orgId()).isEqualTo(1L); // APPROVED + IN_REVIEW 同 org 去重
        assertThat(rows.get(1).orgName()).isEqualTo("计算机学院");
        assertThat(rows.get(1).monthState()).isEqualTo("APPROVED"); // 有 APPROVED 月报 → APPROVED 优先
        // 11 月按组织名升序：电子学院 < 计算机学院
        assertThat(rows.get(2).orgId()).isEqualTo(2L); // org2 在 11 月仅 SUBMITTED
        assertThat(rows.get(2).monthState()).isEqualTo("IN_REVIEW");
        assertThat(rows.get(3).orgId()).isEqualTo(1L);
        assertThat(rows.get(3).monthState()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("滞后清单全量口径：职能部门线（target=职能部门）+ 学院线都记录；只认 APPROVED 月报")
    void fetchDelayedIndicatorsShouldCoverBothOrgLinesWithApprovedOnly() {
        // 51：APPROVED，101（target=计算机学院，appraisal=DELAYED）入清单 —— 学院线
        insertReport(51, "202610", "APPROVED", false, 1L);
        insertPlanReportIndicator(511, 51, 101, "DELAYED", "DELAYED");
        // 52：IN_REVIEW（审批中），102（appraisal 空、self=DELAYED）按 2026-11-18 快照口径不入清单
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
        // 56：APPROVED 职能部门线月报（report_org_type=FUNC_DEPT），108 target=学生处 → 入清单（2026-11-18 拍板）
        exec("INSERT INTO plan_report (id, plan_id, report_month, report_org_type, report_org_id, status, is_deleted)"
                + " VALUES (56, 1, '202610', 'FUNC_DEPT', 3, 'APPROVED', false)");
        insertPlanReportIndicator(561, 56, 108, "DELAYED", null);

        List<DelayedIndicatorRow> rows = repository.fetchDelayedIndicators("202610");

        // 职能部门线（学生处）+ 学院线（计算机学院）都在清单内；按责任单位名+指标ID排序
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).indicatorId()).isEqualTo(108L);
        assertThat(rows.get(0).orgId()).isEqualTo(3L);
        assertThat(rows.get(0).orgName()).isEqualTo("学生处");
        assertThat(rows.get(0).ownerOrgName()).isEqualTo("战略发展部");
        assertThat(rows.get(0).appraisalLevel()).isEqualTo("DELAYED");
        // 学院线不回归：101 仍在清单
        assertThat(rows.get(1).indicatorId()).isEqualTo(101L);
        assertThat(rows.get(1).orgId()).isEqualTo(1L);
        assertThat(rows.get(1).orgName()).isEqualTo("计算机学院");
        assertThat(rows.get(1).ownerOrgName()).isEqualTo("战略发展部");
        assertThat(rows.get(1).appraisalLevel()).isEqualTo("DELAYED");
        assertThat(rows.get(1).selfRating()).isEqualTo("DELAYED");
        // 语义变化（2026-11-18 快照口径）：102 只有审批中月报，不再进清单
        assertThat(rows).extracting(DelayedIndicatorRow::indicatorId).doesNotContain(102L);
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
