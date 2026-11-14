package com.sism.strategy.infrastructure.persistence;

import com.sism.strategy.domain.indicator.Indicator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指标可见性口径仓库层集成测试（H2 PostgreSQL 模式真库执行原生 SQL）。
 *
 * <p>背景（2026-11-10 用户拍板口径）：战略部直发的学院子指标（owner=战略发展部、
 * target=二级学院、parent_indicator_id 非空），其<b>归口职能部门</b>（父级核心指标的
 * 目标组织）此前完全不可见，导致上报审批环节要求的「归口部门完成子指标审核进度鉴定」
 * 不可达（上报链死锁）。本次放行在 findByVisibleOrg / findByYearAndVisibleOrg
 * 新增第三可见分支，并验证不放行过宽（非归口部门仍不可见）。
 *
 * <p>数据布局（用固定 ID 表达线上实证场景：2008 target=37、子指标 2046/2047 target=57）：
 * <pre>
 *   4000 核心指标：战略部(35) → 归口职能部门(37)，DISTRIBUTED
 *   4001 子指标：  战略部直发学院(57)，parent=4000，DISTRIBUTED —— 归口部门应可见
 *   4002 子指标：  战略部直发学院(57)，parent=4000，DRAFT      —— 归口部门应可见（状态不过滤口径）
 *   4003 子指标：  战略部直发学院(57)，parent=4000，已删除     —— 任何视角不可见
 *   4005 核心指标：战略部(35) → 另一职能部门(99)，DISTRIBUTED
 *   4004 子指标：  战略部直发学院(57)，parent=4005            —— 归口=99 可见、归口=37 不可见
 *   4006 核心指标：战略部(35) → 归口职能部门(37)，已删除
 *   4007 子指标：  战略部直发学院(57)，parent=4006            —— 父已删，归口=37 不可见
 * </pre>
 */
@SpringBootTest(classes = IndicatorVisibleOrgRepositoryIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("指标归口可见性放行——仓库层集成测试")
class IndicatorVisibleOrgRepositoryIntegrationTest {

    private static final long STRATEGY_ORG_ID = 35L;
    /** 归口职能部门（父级核心指标的目标组织） */
    private static final long OWNING_FUNC_ORG_ID = 37L;
    /** 二级学院（子指标的目标组织） */
    private static final long COLLEGE_ORG_ID = 57L;
    /** 另一职能部门（既非 owner、非 target、非归口） */
    private static final long OTHER_FUNC_ORG_ID = 99L;

    private static final long CYCLE_ID = 3000L;
    private static final int CYCLE_YEAR = 2026;
    private static final long PLAN_ID = 3100L;
    private static final long TASK_ID = 3200L;

    @Autowired
    private JpaIndicatorRepositoryInternal jpaIndicatorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        // 原生查询引用但本测试上下文未做实体扫描的表（sys_task/plan/cycle）手工补建，
        // 只建查询引用到的列（参照 TaskCreationIntegrationTest 对 plan 表的做法）。
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS public.sys_task (
                    task_id BIGINT PRIMARY KEY,
                    plan_id BIGINT NOT NULL,
                    is_deleted BOOLEAN DEFAULT FALSE
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS public.plan (
                    id BIGINT PRIMARY KEY,
                    cycle_id BIGINT NOT NULL,
                    is_deleted BOOLEAN DEFAULT FALSE
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS public.cycle (
                    id BIGINT PRIMARY KEY,
                    year INT NOT NULL
                )
                """);

        insertOrg(STRATEGY_ORG_ID, "战略发展部-可见性测试", "admin");
        insertOrg(OWNING_FUNC_ORG_ID, "归口职能部门-可见性测试", "functional");
        insertOrg(COLLEGE_ORG_ID, "二级学院-可见性测试", "academic");
        insertOrg(OTHER_FUNC_ORG_ID, "另一职能部门-可见性测试", "functional");

        jdbcTemplate.update("INSERT INTO public.cycle (id, year) VALUES (?, ?)", CYCLE_ID, CYCLE_YEAR);
        jdbcTemplate.update("INSERT INTO public.plan (id, cycle_id, is_deleted) VALUES (?, ?, FALSE)",
                PLAN_ID, CYCLE_ID);
        jdbcTemplate.update("INSERT INTO public.sys_task (task_id, plan_id, is_deleted) VALUES (?, ?, FALSE)",
                TASK_ID, PLAN_ID);

        // 核心指标 4000：战略部 → 归口职能部门，DISTRIBUTED（无父级）
        insertIndicator(4000L, null, STRATEGY_ORG_ID, OWNING_FUNC_ORG_ID, "DISTRIBUTED", false);
        // 战略部直发的学院子指标：DISTRIBUTED / DRAFT / 已删除
        insertIndicator(4001L, 4000L, STRATEGY_ORG_ID, COLLEGE_ORG_ID, "DISTRIBUTED", false);
        insertIndicator(4002L, 4000L, STRATEGY_ORG_ID, COLLEGE_ORG_ID, "DRAFT", false);
        insertIndicator(4003L, 4000L, STRATEGY_ORG_ID, COLLEGE_ORG_ID, "DISTRIBUTED", true);
        // 另一职能部门的核心指标及其学院子指标
        insertIndicator(4005L, null, STRATEGY_ORG_ID, OTHER_FUNC_ORG_ID, "DISTRIBUTED", false);
        insertIndicator(4004L, 4005L, STRATEGY_ORG_ID, COLLEGE_ORG_ID, "DISTRIBUTED", false);
        // 父级核心指标已删除：其学院子指标对归口部门不可见
        insertIndicator(4006L, null, STRATEGY_ORG_ID, OWNING_FUNC_ORG_ID, "DISTRIBUTED", true);
        insertIndicator(4007L, 4006L, STRATEGY_ORG_ID, COLLEGE_ORG_ID, "DISTRIBUTED", false);
    }

    @Test
    @DisplayName("归口职能部门可见战略部直发的学院子指标（无年份与带年份口径一致）")
    void owningFunctionalDeptShouldSeeStrategyDispatchedCollegeChildren() {
        // 无年份口径：findByVisibleOrg
        Page<Indicator> page = jpaIndicatorRepository.findByVisibleOrg(
                OWNING_FUNC_ORG_ID, PageRequest.of(0, 50));
        List<Long> ids = idsOf(page);
        // 原口径：本部门为目标组织（核心指标 4000）
        assertThat(ids).contains(4000L);
        // 新口径：归口于本部门（父级 4000 target=37）的战略部直发学院子指标
        assertThat(ids).contains(4001L);
        // 状态不过滤口径：DRAFT 子指标同样可见（鉴定提前准备）
        assertThat(ids).contains(4002L);
        // 不放行过宽：已删除子指标不可见
        assertThat(ids).doesNotContain(4003L);
        // 不放行过宽：归口于其他职能部门（父级 4005 target=99）的子指标不可见
        assertThat(ids).doesNotContain(4004L, 4005L);
        // 父级已删除的子指标不可见
        assertThat(ids).doesNotContain(4006L, 4007L);
        // countQuery 与结果一致
        assertThat(page.getTotalElements()).isEqualTo(ids.size());

        // 带年份口径（GET /api/v1/indicators?year=... 的实证路径）：与无年份口径一致
        Page<Indicator> yearPage = jpaIndicatorRepository.findByYearAndVisibleOrg(
                CYCLE_YEAR, OWNING_FUNC_ORG_ID, PageRequest.of(0, 50));
        List<Long> yearIds = idsOf(yearPage);
        assertThat(yearIds).contains(4000L, 4001L, 4002L);
        assertThat(yearIds).doesNotContain(4003L, 4004L, 4005L, 4006L, 4007L);
        assertThat(yearPage.getTotalElements()).isEqualTo(yearIds.size());
    }

    @Test
    @DisplayName("非归口职能部门仍不可见他人归口的学院子指标（不放行过宽）")
    void nonOwningFunctionalDeptShouldNotSeeOthersCollegeChildren() {
        Page<Indicator> page = jpaIndicatorRepository.findByVisibleOrg(
                OTHER_FUNC_ORG_ID, PageRequest.of(0, 50));
        List<Long> ids = idsOf(page);
        // 本部门目标组织（核心指标 4005）可见
        assertThat(ids).contains(4005L);
        // 归口于本部门（父级 4005 target=99）的学院子指标可见
        assertThat(ids).contains(4004L);
        // 归口于归口职能部门（父级 4000 target=37）的子指标不可见
        assertThat(ids).doesNotContain(4000L, 4001L, 4002L);
        assertThat(page.getTotalElements()).isEqualTo(ids.size());
    }

    @Test
    @DisplayName("学院视角原口径不回归：目标组织为本学院的子指标仍可见")
    void collegeShouldStillSeeItsTargetedChildren() {
        Page<Indicator> page = jpaIndicatorRepository.findByVisibleOrg(
                COLLEGE_ORG_ID, PageRequest.of(0, 50));
        List<Long> ids = idsOf(page);
        // target=本学院 的子指标可见（原口径）
        assertThat(ids).contains(4001L, 4002L, 4004L);
        // 已删除不可见；核心指标 target=职能部门 与学院无关
        assertThat(ids).doesNotContain(4003L, 4000L, 4005L);
        assertThat(page.getTotalElements()).isEqualTo(ids.size());
    }

    @Test
    @DisplayName("年份过滤不受放行影响：非本周期年份查不到任何指标")
    void yearFilterShouldStillApply() {
        Page<Indicator> page = jpaIndicatorRepository.findByYearAndVisibleOrg(
                CYCLE_YEAR + 1, OWNING_FUNC_ORG_ID, PageRequest.of(0, 50));
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    private List<Long> idsOf(Page<Indicator> page) {
        return page.getContent().stream().map(Indicator::getId).toList();
    }

    private void insertOrg(long id, String name, String orgType) {
        jdbcTemplate.update("""
                INSERT INTO public.sys_org
                    (id, name, type, is_active, sort_order, level, created_at, updated_at, is_deleted)
                VALUES (?, ?, ?, TRUE, 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE)
                """, id, name, orgType);
    }

    private void insertIndicator(long id, Long parentId, long ownerOrgId, long targetOrgId,
                                 String status, boolean deleted) {
        jdbcTemplate.update("""
                INSERT INTO public.indicator
                    (id, task_id, parent_indicator_id, owner_org_id, target_org_id,
                     indicator_desc, weight_percent, sort_order, created_at, updated_at,
                     type, progress, is_deleted, status)
                VALUES (?, ?, ?, ?, ?, ?, 100, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '定量', 0, ?, ?)
                """, id, TASK_ID, parentId, ownerOrgId, targetOrgId, "指标-" + id, deleted, status);
    }

    // ==================== Test Configuration ====================

    /**
     * 最小化测试上下文：只装载 indicator + sys_org 实体与 Indicator 仓库；
     * Plan/Cycle 仓库被排除（其实体不在本持久化单元内），plan/cycle/sys_task
     * 表由 @BeforeEach 手工补建（原生查询只引用其少量列）。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = {
            "com.sism.strategy.domain.indicator",
            "com.sism.organization.domain"
    })
    @EnableJpaRepositories(basePackages = "com.sism.strategy.infrastructure.persistence",
            excludeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {
                            JpaPlanRepositoryInternal.class,
                            StrategyJpaPlanRepositoryInternal.class,
                            JpaCycleRepositoryInternal.class
                    }))
    static class TestConfig {
    }
}
