package com.sism.analytics.infrastructure.repository;

import com.sism.analytics.domain.Report;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReportRepository 集成测试。
 * 建表来自测试 schema.sql（与 V107 生产 DDL 对齐，ddl-auto=none），
 * 因此本测试同时是「实体映射与真实表结构一致」的回归门禁：
 * D1 缺陷（实体 11 列在库中不存在导致线上 500）即由该类范式拦截。
 */
@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = com.sism.analytics.TestApplication.class)
@DisplayName("ReportRepository 集成测试")
class ReportRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ReportRepository reportRepository;

    @Test
    @DisplayName("should persist report failure message after save and reload")
    void shouldPersistReportFailureMessageAfterSaveAndReload() {
        Report report = Report.create(
                "失败报告",
                Report.TYPE_STRATEGIC,
                Report.FORMAT_PDF,
                1L,
                "{\"year\":2026}",
                "回归测试"
        );
        report.fail("生成失败");

        Report saved = reportRepository.save(report);
        entityManager.flush();
        entityManager.clear();

        Report reloaded = reportRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(Report.STATUS_FAILED);
        assertThat(reloaded.getErrorMessage()).isEqualTo("生成失败");
    }

    @Test
    @DisplayName("should persist all mapped columns across the generate lifecycle")
    void shouldPersistAllMappedColumnsAcrossGenerateLifecycle() {
        Report report = Report.create(
                "战略执行报告",
                Report.TYPE_STRATEGIC,
                Report.FORMAT_PDF,
                7L,
                "{\"cycle\":\"2026Q4\"}",
                "全字段映射回归"
        );

        Report saved = reportRepository.save(report);
        entityManager.flush();
        entityManager.clear();

        Report draft = reportRepository.findByIdAndNotDeleted(saved.getId()).orElseThrow();
        assertThat(draft.getStatus()).isEqualTo(Report.STATUS_DRAFT);
        assertThat(draft.getName()).isEqualTo("战略执行报告");
        assertThat(draft.getType()).isEqualTo(Report.TYPE_STRATEGIC);
        assertThat(draft.getFormat()).isEqualTo(Report.FORMAT_PDF);
        assertThat(draft.getGeneratedBy()).isEqualTo(7L);
        assertThat(draft.getParameters()).isEqualTo("{\"cycle\":\"2026Q4\"}");
        assertThat(draft.getDescription()).isEqualTo("全字段映射回归");
        assertThat(draft.getCreatedAt()).isNotNull();
        assertThat(draft.isDeleted()).isFalse();

        draft.generate("/tmp/analytics/report-1.pdf", 2048L);
        reportRepository.save(draft);
        entityManager.flush();
        entityManager.clear();

        Report generated = reportRepository.findAllGeneratedAndNotDeleted().get(0);
        assertThat(generated.getId()).isEqualTo(saved.getId());
        assertThat(generated.getStatus()).isEqualTo(Report.STATUS_GENERATED);
        assertThat(generated.getFilePath()).isEqualTo("/tmp/analytics/report-1.pdf");
        assertThat(generated.getFileSize()).isEqualTo(2048L);
        assertThat(generated.getGeneratedAt()).isNotNull();
        assertThat(generated.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("should persist soft delete so the row leaves not-deleted queries")
    void shouldPersistSoftDeleteSoRowLeavesNotDeletedQueries() {
        Report report = Report.create(
                "待删除报告",
                Report.TYPE_OPERATIONAL,
                Report.FORMAT_CSV,
                3L,
                null,
                null
        );

        Report saved = reportRepository.save(report);
        entityManager.flush();
        entityManager.clear();

        Report loaded = reportRepository.findById(saved.getId()).orElseThrow();
        loaded.delete();
        reportRepository.save(loaded);
        entityManager.flush();
        entityManager.clear();

        assertThat(reportRepository.findByIdAndNotDeleted(saved.getId())).isEmpty();
        Report archived = reportRepository.findById(saved.getId()).orElseThrow();
        assertThat(archived.isDeleted()).isTrue();
    }
}
