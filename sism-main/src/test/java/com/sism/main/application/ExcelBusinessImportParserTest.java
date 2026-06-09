package com.sism.main.application;

import com.sism.main.interfaces.dto.BusinessImportDtos.ImportType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Excel Business Import Parser Tests")
class ExcelBusinessImportParserTest {

    private final ExcelBusinessImportParser parser = new ExcelBusinessImportParser();

    @Test
    @DisplayName("Should parse strategic task milestone detail with minute precision")
    void shouldParseStrategicMilestoneDetailWithMinutePrecision() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("战略任务");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("任务类型");
            header.createCell(1).setCellValue("战略任务");
            header.createCell(2).setCellValue("核心指标");
            header.createCell(3).setCellValue("指标类型");
            header.createCell(4).setCellValue("权重");
            header.createCell(5).setCellValue("里程碑明细");
            header.createCell(6).setCellValue("备注");

            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("发展性");
            data.createCell(1).setCellValue("完善校院两级提案办理机制");
            data.createCell(2).setCellValue("提案办理满意度");
            data.createCell(3).setCellValue("定量");
            data.createCell(4).setCellValue("25%");
            data.createCell(5).setCellValue("""
                    1. 梳理提案清单（2026-03-31T00:00:00，33%）
                    2. 推进督办与反馈（2026-06-30T00:00:00，67%）
                    3. 总结与满意度调查（2026-12-31T00:00:00，100%）""");
            data.createCell(6).setCellValue("按季度推进");

            var parsed = parser.parseSheet(sheet, ImportType.STRATEGIC_TASK);

            assertEquals("战略任务", parsed.sheetName());
            assertEquals(1, parsed.rows().size());
            var row = parsed.rows().get(0);
            assertTrue(row.errors().isEmpty());
            assertEquals("发展性", row.normalized().taskType());
            assertEquals("完善校院两级提案办理机制", row.normalized().strategicTask());
            assertEquals("提案办理满意度", row.normalized().indicatorName());
            assertEquals(0, new BigDecimal("25").compareTo(row.normalized().weight()));
            assertEquals(3, row.normalized().milestones().size());
            assertEquals("梳理提案清单", row.normalized().milestones().get(0).name());
            assertEquals(LocalDateTime.of(2026, 3, 31, 0, 0), row.normalized().milestones().get(0).dueAt());
            assertEquals(33, row.normalized().milestones().get(0).targetProgress());
            assertEquals(LocalDateTime.of(2026, 6, 30, 0, 0), row.normalized().milestones().get(1).dueAt());
            assertEquals(67, row.normalized().milestones().get(1).targetProgress());
            assertEquals(LocalDateTime.of(2026, 12, 31, 0, 0), row.normalized().milestones().get(2).dueAt());
            assertEquals(100, row.normalized().milestones().get(2).targetProgress());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("Should parse distribution parent indicator aliases and decimal weight")
    void shouldParseDistributionParentIndicatorAliasesAndDecimalWeight() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("指标下发");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("责任学院");
            header.createCell(1).setCellValue("父级核心指标");
            header.createCell(2).setCellValue("子指标名称");
            header.createCell(3).setCellValue("指标类型");
            header.createCell(4).setCellValue("权重");
            header.createCell(5).setCellValue("阶段安排");

            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("计算机学院");
            data.createCell(1).setCellValue("人才培养质量提升");
            data.createCell(2).setCellValue("一流课程建设数");
            data.createCell(3).setCellValue("定量");
            data.createCell(4).setCellValue("0.2");
            data.createCell(5).setCellValue("1. 完成课程遴选（2026-06-30T00:00:00，50%）");

            var parsed = parser.parseSheet(sheet, ImportType.DISTRIBUTION);

            assertEquals(1, parsed.rows().size());
            var row = parsed.rows().get(0);
            assertTrue(row.errors().isEmpty());
            assertEquals("计算机学院", row.normalized().college());
            assertEquals("人才培养质量提升", row.normalized().parentIndicator());
            assertEquals("一流课程建设数", row.normalized().indicatorName());
            assertEquals(0, new BigDecimal("20").compareTo(row.normalized().weight()));
            assertEquals(50, row.normalized().milestones().get(0).targetProgress());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("Should parse distribution export headers and milestone column")
    void shouldParseDistributionExportHeadersAndMilestoneColumn() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("指标下发");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("序号");
            header.createCell(1).setCellValue("学院");
            header.createCell(2).setCellValue("父级战略任务");
            header.createCell(3).setCellValue("父级核心指标");
            header.createCell(4).setCellValue("子指标名称");
            header.createCell(5).setCellValue("指标类型");
            header.createCell(6).setCellValue("备注");
            header.createCell(7).setCellValue("权重");
            header.createCell(8).setCellValue("进度");
            header.createCell(9).setCellValue("里程碑");

            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(1);
            data.createCell(1).setCellValue("工学院");
            data.createCell(2).setCellValue("人力资源部师资队伍优化任务");
            data.createCell(3).setCellValue("推进绩效薪酬制度改革方案落地");
            data.createCell(4).setCellValue("推进绩效薪酬制度改革方案落地");
            data.createCell(5).setCellValue("定性");
            data.createCell(6).setCellValue("战略发展部下发至人力资源部");
            data.createCell(7).setCellValue(40);
            data.createCell(8).setCellValue("当前进度：0%");
            data.createCell(9).setCellValue("""
                    1. 完成绩效薪酬改革调研（2026-03-31 00:00，33%）
                    2. 完成方案论证与试点（2026-06-30 00:00，67%）
                    3. 全面推进与效果评估（2026-12-31 00:00，100%）""");

            var parsed = parser.parseSheet(sheet, ImportType.DISTRIBUTION);

            assertEquals(1, parsed.rows().size());
            var row = parsed.rows().get(0);
            assertTrue(row.errors().isEmpty());
            assertTrue(row.warnings().isEmpty());
            assertEquals("工学院", row.normalized().college());
            assertEquals("人力资源部师资队伍优化任务", row.normalized().parentStrategicTask());
            assertEquals("推进绩效薪酬制度改革方案落地", row.normalized().parentIndicator());
            assertEquals("推进绩效薪酬制度改革方案落地", row.normalized().indicatorName());
            assertEquals(3, row.normalized().milestones().size());
            assertEquals(LocalDateTime.of(2026, 3, 31, 0, 0), row.normalized().milestones().get(0).dueAt());
            assertEquals(33, row.normalized().milestones().get(0).targetProgress());
            assertEquals(LocalDateTime.of(2026, 12, 31, 0, 0), row.normalized().milestones().get(2).dueAt());
            assertEquals(100, row.normalized().milestones().get(2).targetProgress());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("Should block invalid milestone due date and progress")
    void shouldBlockInvalidMilestoneDueDateAndProgress() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("战略任务");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("任务类型");
            header.createCell(1).setCellValue("战略任务");
            header.createCell(2).setCellValue("核心指标");
            header.createCell(3).setCellValue("指标类型");
            header.createCell(4).setCellValue("权重");
            header.createCell(5).setCellValue("里程碑明细");

            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("发展性");
            data.createCell(1).setCellValue("完善校院两级提案办理机制");
            data.createCell(2).setCellValue("提案办理满意度");
            data.createCell(3).setCellValue("定量");
            data.createCell(4).setCellValue("25%");
            data.createCell(5).setCellValue("""
                    1. 梳理提案清单（不是日期，33%）
                    2. 推进督办与反馈（2026-06-30，120%）""");

            var parsed = parser.parseSheet(sheet, ImportType.STRATEGIC_TASK);

            assertEquals(1, parsed.rows().size());
            var row = parsed.rows().get(0);
            assertTrue(row.hasErrors());
            assertTrue(row.errors().contains("第 1 个里程碑截止时间无法解析"));
            assertTrue(row.errors().contains("第 2 个里程碑目标进度必须在 0 到 100 之间"));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
