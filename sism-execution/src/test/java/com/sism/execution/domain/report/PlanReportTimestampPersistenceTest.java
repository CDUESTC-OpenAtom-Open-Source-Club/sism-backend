package com.sism.execution.domain.report;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-10-31 修复（P-20，试点）回归测试：PlanReport.createdAt/updatedAt 库值回填。
 *
 * <p>缺陷：基类 Entity 的 createdAt/updatedAt 标了 @Transient，Hibernate 不读写
 * created_at/updated_at 列 → 每次水合出的都是「请求时刻」的伪造时间戳（同一次查询里
 * 两条记录的时间戳还会彼此相同，掩盖了创建顺序）。
 *
 * <p>修法：PlanReport 用子类影子字段把两列重新映射为
 * {@code @Column(insertable=false, updatable=false)}——
 * <ul>
 *   <li>读路径回填库值（下列三个测试用「回读比对」验证，不断言测试执行时刻）；</li>
 *   <li>写路径保持与 @Transient 时代等价：INSERT 由库默认值 CURRENT_TIMESTAMP 填充，
 *   UPDATE 不改两列（plan_report 上没有 trg_*_updated_at 触发器，现状即插入后不变）。</li>
 * </ul>
 *
 * <p>位置与隔离说明：本类放在 domain.report（不能放 infrastructure.persistence——
 * 那个包会被 ReportApplicationServiceIntegrationTest 的 @ComponentScan 扫进它的上下文，
 * 嵌套 @SpringBootConfiguration 会造成仓储 bean 重复注册）；并使用独立的 H2 库名，
 * 避免两个测试上下文在同一个 surefire JVM 里因 create-drop 相互踩 schema。
 */
@SpringBootTest(
        classes = PlanReportTimestampPersistenceTest.TestConfig.class,
        properties = {
                // 独立 H2 库名隔离两个测试上下文；H2 PG 模式默认即有 public schema，无需 INIT 子句
                // （INIT 里的 \; 转义会被 @SpringBootTest 内联属性的 Properties 解析吃掉反斜杠，
                //  导致 H2 URL format error，故这里干脆不写 INIT）
                "spring.datasource.url=jdbc:h2:mem:sism_execution_ts_test;DB_CLOSE_DELAY=-1;"
                        + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=YEAR"
        })
@ActiveProfiles("test")
@Transactional
@DisplayName("PlanReport 时间戳库值回填（P-20 试点）")
class PlanReportTimestampPersistenceTest {

    private static final AtomicLong TEST_SEQUENCE = new AtomicLong(1);

    /** 2020-06-01 00:00 —— 与「测试执行时刻」隔着数年的固定库值，旧行为（水合请求时刻）绝无可能撞上。 */
    private static final LocalDateTime BACKDATED = LocalDateTime.of(2020, 6, 1, 0, 0);

    @Autowired
    private PlanReportRepository planReportRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("①插入后重查：createdAt/updatedAt 等于库列值（回读比对），而非构造/请求时刻")
    void reload_shouldHydrateTimestampsFromDatabaseColumns() {
        long sequence = TEST_SEQUENCE.getAndIncrement();
        PlanReport report = persistedDraft(sequence);
        flushAndClear();

        PlanReport reloaded = planReportRepository.findById(report.getId()).orElseThrow();

        LocalDateTime dbCreatedAt = queryColumn("created_at", report.getId());
        LocalDateTime dbUpdatedAt = queryColumn("updated_at", report.getId());

        assertThat(dbCreatedAt).isNotNull();
        // 实体读数与 JDBC 直读库列逐值相等 → 读路径绑定的是库列，不再是内存 now()
        assertThat(reloaded.getCreatedAt()).isEqualTo(dbCreatedAt);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(dbUpdatedAt);
    }

    @Test
    @DisplayName("②同一查询水合两条记录：被回填的时间戳来自各自库列，不再彼此相同")
    void reload_shouldHydrateEachRowFromItsOwnColumnValues() {
        long sequence = TEST_SEQUENCE.getAndIncrement();
        long planId = 21_000L + sequence;

        // 行 A：正常插入后把库列回拨到固定历史值
        PlanReport backdated = persistedDraftWithPlanId(sequence, planId);
        flushAndClear();
        jdbcTemplate.update(
                "UPDATE public.plan_report SET created_at = ?, updated_at = ? WHERE id = ?",
                BACKDATED, BACKDATED, backdated.getId());

        // 行 B：同一 plan 下紧接着正常插入（库默认值 = 现在）
        PlanReport fresh = persistedDraftWithPlanId(sequence, planId);
        flushAndClear();

        // 一条 SQL 同时水合两行——旧行为：两行的 createdAt 都≈查询执行时刻（相同），行 A 的回拨值被抹掉
        List<PlanReport> both = planReportRepository.findByPlanId(planId);
        assertThat(both).hasSize(2);

        PlanReport reloadedBackdated = byId(both, backdated.getId());
        PlanReport reloadedFresh = byId(both, fresh.getId());

        assertThat(reloadedBackdated.getCreatedAt()).isEqualTo(BACKDATED);
        assertThat(reloadedBackdated.getUpdatedAt()).isEqualTo(BACKDATED);
        assertThat(reloadedFresh.getCreatedAt()).isNotEqualTo(BACKDATED);
        // 两行不再相同，且先后顺序符合库值
        assertThat(reloadedBackdated.getCreatedAt()).isNotEqualTo(reloadedFresh.getCreatedAt());
        assertThat(reloadedBackdated.getCreatedAt()).isBefore(reloadedFresh.getCreatedAt());
    }

    @Test
    @DisplayName("③更新语义（保守）：状态流转落库，created_at/updated_at 两列保持插入时的库值不变")
    void submit_shouldNotTouchTimestampColumnsInDatabase() {
        long sequence = TEST_SEQUENCE.getAndIncrement();
        PlanReport report = persistedDraft(sequence);
        flushAndClear();

        LocalDateTime beforeCreatedAt = queryColumn("created_at", report.getId());
        LocalDateTime beforeUpdatedAt = queryColumn("updated_at", report.getId());

        PlanReport managed = planReportRepository.findById(report.getId()).orElseThrow();
        managed.submit(9_001L + sequence);
        planReportRepository.save(managed);
        flushAndClear();

        PlanReport reloaded = planReportRepository.findById(report.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PlanReport.STATUS_SUBMITTED);

        // plan_report 无 updated_at 触发器，@Transient 时代 UPDATE 从不触碰两列；
        // 保守修法（updatable=false）原样保留该库语义：列值只反映插入时刻
        assertThat(queryColumn("created_at", report.getId())).isEqualTo(beforeCreatedAt);
        assertThat(queryColumn("updated_at", report.getId())).isEqualTo(beforeUpdatedAt);
        // 重查实体的时间戳仍等于库值（而不是 @PreUpdate 在内存里刷的 now()）
        assertThat(reloaded.getCreatedAt()).isEqualTo(beforeCreatedAt);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(beforeUpdatedAt);
    }

    private PlanReport persistedDraft(long sequence) {
        return persistedDraftWithPlanId(sequence, 20_000L + sequence);
    }

    private PlanReport persistedDraftWithPlanId(long sequence, long planId) {
        PlanReport report = PlanReport.createDraft(
                "202605", 41_000L + sequence, ReportOrgType.FUNC_DEPT, planId);
        report.validate();
        return planReportRepository.save(report);
    }

    private LocalDateTime queryColumn(String column, Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM public.plan_report WHERE id = ?",
                LocalDateTime.class,
                id);
    }

    private PlanReport byId(List<PlanReport> reports, Long id) {
        return reports.stream()
                .filter(r -> id.equals(r.getId()))
                .findFirst()
                .orElseThrow();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.sism.execution.domain")
    @EnableJpaRepositories(basePackages = "com.sism.execution.infrastructure.persistence")
    static class TestConfig {
    }
}
