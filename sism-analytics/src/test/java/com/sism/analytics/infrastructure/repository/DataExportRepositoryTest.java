package com.sism.analytics.infrastructure.repository;

import com.sism.analytics.domain.DataExport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DataExportRepository 集成测试（真实建表，schema.sql 与 V107 对齐）。
 * 修复前 DataExport 挂在执行侧 adhoc_task 表（列全不匹配），
 * 此类测试在真实建表下拦截该类映射错位。
 */
@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = com.sism.analytics.TestApplication.class)
@DisplayName("DataExportRepository 集成测试")
class DataExportRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private DataExportRepository dataExportRepository;

    @Test
    @DisplayName("should persist all mapped columns across the export lifecycle")
    void shouldPersistAllMappedColumnsAcrossExportLifecycle() {
        DataExport export = DataExport.create(
                "指标导出",
                "INDICATOR",
                DataExport.FORMAT_EXCEL,
                11L,
                "{\"cycleId\":2}"
        );
        export.startProcessing();
        export.complete("/tmp/analytics/export-1.xlsx", 4096L);

        DataExport saved = dataExportRepository.save(export);
        entityManager.flush();
        entityManager.clear();

        DataExport reloaded = dataExportRepository.findByIdAndNotDeleted(saved.getId()).orElseThrow();

        assertThat(reloaded.getName()).isEqualTo("指标导出");
        assertThat(reloaded.getType()).isEqualTo("INDICATOR");
        assertThat(reloaded.getFormat()).isEqualTo(DataExport.FORMAT_EXCEL);
        assertThat(reloaded.getStatus()).isEqualTo(DataExport.STATUS_COMPLETED);
        assertThat(reloaded.getRequestedBy()).isEqualTo(11L);
        assertThat(reloaded.getRequestedAt()).isNotNull();
        assertThat(reloaded.getStartedAt()).isNotNull();
        assertThat(reloaded.getCompletedAt()).isNotNull();
        assertThat(reloaded.getFilePath()).isEqualTo("/tmp/analytics/export-1.xlsx");
        assertThat(reloaded.getFileSize()).isEqualTo(4096L);
        assertThat(reloaded.getParameters()).isEqualTo("{\"cycleId\":2}");
        assertThat(reloaded.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("should persist failure message after save and reload")
    void shouldPersistFailureMessageAfterSaveAndReload() {
        DataExport export = DataExport.create(
                "失败导出",
                "INDICATOR",
                DataExport.FORMAT_CSV,
                2L,
                null
        );
        export.fail("数据源不可用");

        DataExport saved = dataExportRepository.save(export);
        entityManager.flush();
        entityManager.clear();

        DataExport reloaded = dataExportRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(DataExport.STATUS_FAILED);
        assertThat(reloaded.getErrorMessage()).isEqualTo("数据源不可用");
    }
}
