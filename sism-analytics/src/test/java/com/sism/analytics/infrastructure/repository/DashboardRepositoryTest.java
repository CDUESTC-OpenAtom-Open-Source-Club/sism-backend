package com.sism.analytics.infrastructure.repository;

import com.sism.analytics.domain.Dashboard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DashboardRepository 集成测试（真实建表，schema.sql 与 V107 对齐）。
 * 修复前 Dashboard 指向的 analytics_dashboards 表从未在任何 DDL 中存在，
 * 此类测试在真实建表下拦截该类映射错位。
 */
@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = com.sism.analytics.TestApplication.class)
@DisplayName("DashboardRepository 集成测试")
class DashboardRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private DashboardRepository dashboardRepository;

    @Test
    @DisplayName("should persist all mapped columns after save and reload")
    void shouldPersistAllMappedColumnsAfterSaveAndReload() {
        Dashboard dashboard = Dashboard.create(
                "学院看板",
                "学院级指标看板",
                5L,
                true,
                "{\"layout\":\"grid\"}"
        );

        Dashboard saved = dashboardRepository.save(dashboard);
        entityManager.flush();
        entityManager.clear();

        Dashboard reloaded = dashboardRepository.findByIdAndNotDeleted(saved.getId()).orElseThrow();

        assertThat(reloaded.getName()).isEqualTo("学院看板");
        assertThat(reloaded.getDescription()).isEqualTo("学院级指标看板");
        assertThat(reloaded.getUserId()).isEqualTo(5L);
        assertThat(reloaded.isPublic()).isTrue();
        assertThat(reloaded.getConfig()).isEqualTo("{\"layout\":\"grid\"}");
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("should persist soft delete so the row leaves not-deleted queries")
    void shouldPersistSoftDeleteSoRowLeavesNotDeletedQueries() {
        Dashboard dashboard = Dashboard.create("临时看板", null, 9L, false, "{}");

        Dashboard saved = dashboardRepository.save(dashboard);
        entityManager.flush();
        entityManager.clear();

        Dashboard loaded = dashboardRepository.findById(saved.getId()).orElseThrow();
        loaded.delete();
        dashboardRepository.save(loaded);
        entityManager.flush();
        entityManager.clear();

        assertThat(dashboardRepository.findByIdAndNotDeleted(saved.getId())).isEmpty();
    }
}
