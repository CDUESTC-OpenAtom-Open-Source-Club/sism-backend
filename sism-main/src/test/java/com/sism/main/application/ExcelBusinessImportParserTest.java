package com.sism.main.application;

import com.sism.main.interfaces.dto.BusinessImportDtos.ImportAction;
import com.sism.main.interfaces.dto.BusinessImportDtos.ImportType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Excel Business Import Parser Tests")
class ExcelBusinessImportParserTest {

    private final ExcelBusinessImportParser parser = new ExcelBusinessImportParser();

    @Test
    @DisplayName("Should parse strategic task row with legacy header order and percent weight")
    void shouldParseStrategicTaskRowWithAliasAndPercentWeight() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("战略任务");
            Row header = sheet.createRow(0);
            // 2026-10-31：旧布局（任务类型在战略任务前）按列别名识别，兼容旧文件
            header.createCell(0).setCellValue("序号");
            header.createCell(1).setCellValue("任务类型");
            header.createCell(2).setCellValue("战略任务");
            header.createCell(3).setCellValue("核心指标");
            header.createCell(4).setCellValue("指标类型");
            header.createCell(5).setCellValue("权重");
            header.createCell(6).setCellValue("备注");

            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(1);
            // 发展性任务组不做基础性权重合计校验，单行 25 不触发组权重警告
            data.createCell(1).setCellValue("发展性");
            data.createCell(2).setCellValue("完善校院两级提案办理机制");
            data.createCell(3).setCellValue("提案办理满意度");
            data.createCell(4).setCellValue("定量");
            data.createCell(5).setCellValue("25%");
            data.createCell(6).setCellValue("按季度推进");

            var parsed = parser.parseSheet(sheet, ImportType.STRATEGIC_TASK);

            assertEquals("战略任务", parsed.sheetName());
            assertEquals(1, parsed.rows().size());
            var row = parsed.rows().get(0);
            assertTrue(row.errors().isEmpty());
            assertTrue(row.warnings().isEmpty());
            assertEquals(1, row.normalized().seq());
            assertEquals(1, row.rowNo());
            assertEquals("发展性", row.normalized().taskType());
            assertEquals("完善校院两级提案办理机制", row.normalized().strategicTask());
            assertEquals("提案办理满意度", row.normalized().indicatorName());
            assertEquals(0, new BigDecimal("25").compareTo(row.normalized().weight()));
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
            header.createCell(0).setCellValue("序号");
            header.createCell(1).setCellValue("责任学院");
            header.createCell(2).setCellValue("父级核心指标");
            header.createCell(3).setCellValue("子指标名称");
            header.createCell(4).setCellValue("指标类型");
            header.createCell(5).setCellValue("权重");

            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(1);
            data.createCell(1).setCellValue("计算机学院");
            data.createCell(2).setCellValue("人才培养质量提升");
            data.createCell(3).setCellValue("一流课程建设数");
            data.createCell(4).setCellValue("定量");
            data.createCell(5).setCellValue("0.2");

            var parsed = parser.parseSheet(sheet, ImportType.DISTRIBUTION);

            assertEquals(1, parsed.rows().size());
            var row = parsed.rows().get(0);
            assertTrue(row.errors().isEmpty());
            assertEquals("计算机学院", row.normalized().college());
            assertEquals("人才培养质量提升", row.normalized().parentIndicator());
            assertEquals("一流课程建设数", row.normalized().indicatorName());
            assertEquals(0, new BigDecimal("20").compareTo(row.normalized().weight()));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("2026-11-19（学院「全部导入」）：解析「职能部门」列（别名「归口部门」）")
    void shouldParseFunctionalDeptColumn() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("学院子指标模板");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("序号");
            header.createCell(1).setCellValue("学院");
            header.createCell(2).setCellValue("职能部门");
            header.createCell(3).setCellValue("核心指标");
            header.createCell(4).setCellValue("子指标名称");
            header.createCell(5).setCellValue("指标类型");
            header.createCell(6).setCellValue("权重");

            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue(1);
            first.createCell(1).setCellValue("马克思主义学院");
            first.createCell(2).setCellValue("教务处");
            first.createCell(3).setCellValue("父级核心指标A");
            first.createCell(4).setCellValue("子指标A1");
            first.createCell(5).setCellValue("定量");
            first.createCell(6).setCellValue(100);

            // 第二行「职能部门」留空 → 空串（走父级反查兜底，不 sticky 继承上一组）
            Row second = sheet.createRow(2);
            second.createCell(0).setCellValue(2);
            second.createCell(1).setCellValue("马克思主义学院");
            second.createCell(3).setCellValue("父级核心指标B");
            second.createCell(4).setCellValue("子指标B1");
            second.createCell(5).setCellValue("定量");
            second.createCell(6).setCellValue(100);

            var parsed = parser.parseSheet(sheet, ImportType.DISTRIBUTION);

            assertEquals(2, parsed.rows().size());
            assertEquals("教务处", parsed.rows().get(0).normalized().functionalDept());
            assertEquals("", parsed.rows().get(1).normalized().functionalDept(),
                    "「职能部门」列留空不应从上一组 sticky 继承（留空=反查兜底语义）");
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("2026-11-19（学院「全部导入」）：「归口部门」为「职能部门」的兼容别名")
    void shouldParseFunctionalDeptAlias() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("学院子指标模板");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("序号");
            header.createCell(1).setCellValue("学院");
            header.createCell(2).setCellValue("归口部门");
            header.createCell(3).setCellValue("核心指标");
            header.createCell(4).setCellValue("子指标名称");
            header.createCell(5).setCellValue("指标类型");
            header.createCell(6).setCellValue("权重");

            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(1);
            data.createCell(1).setCellValue("马克思主义学院");
            data.createCell(2).setCellValue("数字校园建设办公室");
            data.createCell(3).setCellValue("父级核心指标B");
            data.createCell(4).setCellValue("子指标B1");
            data.createCell(5).setCellValue("定量");
            data.createCell(6).setCellValue(100);

            var parsed = parser.parseSheet(sheet, ImportType.DISTRIBUTION);

            assertEquals(1, parsed.rows().size());
            assertEquals("数字校园建设办公室", parsed.rows().get(0).normalized().functionalDept());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("Should parse distribution export headers and ignore unknown columns")
    void shouldParseDistributionExportHeadersAndIgnoreUnknownColumns() {
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
            // 2026-10-31 层级化：组内子指标合计=100 语义上线——旧断言权重 40（单行合计 40≠100）
            // 会按新口径产生组权重警告，这里改为 100 以保持本用例聚焦「列别名与未知列忽略」
            data.createCell(7).setCellValue(100);
            data.createCell(8).setCellValue("当前进度：0%");
            data.createCell(9).setCellValue("1. 完成调研（2026-03-31，33%）");

            var parsed = parser.parseSheet(sheet, ImportType.DISTRIBUTION);

            assertEquals(1, parsed.rows().size());
            var row = parsed.rows().get(0);
            assertTrue(row.errors().isEmpty());
            assertTrue(row.warnings().isEmpty());
            assertEquals("工学院", row.normalized().college());
            assertEquals("人力资源部师资队伍优化任务", row.normalized().parentStrategicTask());
            assertEquals("推进绩效薪酬制度改革方案落地", row.normalized().parentIndicator());
            assertEquals("推进绩效薪酬制度改革方案落地", row.normalized().indicatorName());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("Should reject workbook without required sequence column")
    void shouldRejectWorkbookWithoutSequenceColumn() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("战略任务");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("任务类型");
            header.createCell(1).setCellValue("战略任务");
            header.createCell(2).setCellValue("核心指标");
            header.createCell(3).setCellValue("指标类型");
            header.createCell(4).setCellValue("权重");

            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("发展性");
            data.createCell(1).setCellValue("完善校院两级提案办理机制");
            data.createCell(2).setCellValue("提案办理满意度");
            data.createCell(3).setCellValue("定量");
            data.createCell(4).setCellValue("25%");

            // 2026-10-19：解析期的用户可见错误改为 IllegalStateException（message 透传给前端）
            var ex = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> parser.parseSheet(sheet, ImportType.STRATEGIC_TASK));
            assertTrue(ex.getMessage().contains("序号"));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    // P-16（2026-10-31）：默认值就是 100（BusinessImportApplicationService.defaultWeight），
    // 警告文案必须直说「默认值 100」，不再让用户猜「系统默认值」是多少
    @Test
    @DisplayName("Should warn exact default weight 100 when weight cell is blank")
    void shouldWarnDefaultWeight100WhenWeightBlank() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("战略任务");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("序号");
            header.createCell(1).setCellValue("任务类型");
            header.createCell(2).setCellValue("战略任务");
            header.createCell(3).setCellValue("核心指标");
            header.createCell(4).setCellValue("指标类型");
            header.createCell(5).setCellValue("权重");
            header.createCell(6).setCellValue("备注");

            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(1);
            data.createCell(1).setCellValue("发展性");
            data.createCell(2).setCellValue("完善校院两级提案办理机制");
            data.createCell(3).setCellValue("提案办理满意度");
            data.createCell(4).setCellValue("定量");
            // 权重列留空
            data.createCell(6).setCellValue("按季度推进");

            var parsed = parser.parseSheet(sheet, ImportType.STRATEGIC_TASK);

            assertEquals(1, parsed.rows().size());
            var row = parsed.rows().get(0);
            assertTrue(row.errors().isEmpty());
            assertEquals(1, row.warnings().size());
            assertTrue(row.warnings().get(0).contains("默认值 100"),
                    "警告文案应写明默认值 100: " + row.warnings().get(0));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    // ==================== 2026-10-31 层级化改造（终版表头）：sticky 继承 / 组号 / 组权重 / 合并单元格 ====================

    /** 终版表头（职能部门）：序号|部门|战略任务|任务类型|核心指标|指标类型|权重|下发备注 */
    private Sheet strategicSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("职能部门指标模板");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("序号");
        header.createCell(1).setCellValue("部门");
        header.createCell(2).setCellValue("战略任务");
        header.createCell(3).setCellValue("任务类型");
        header.createCell(4).setCellValue("核心指标");
        header.createCell(5).setCellValue("指标类型");
        header.createCell(6).setCellValue("权重（可填 10 / 10% / 0.1）");
        header.createCell(7).setCellValue("下发备注");
        return sheet;
    }

    /** 终版表头（学院）：序号|学院|核心指标|核心指标类型|子指标名称|指标类型|权重|下发备注 */
    private Sheet distributionSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("学院子指标模板");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("序号");
        header.createCell(1).setCellValue("学院");
        header.createCell(2).setCellValue("核心指标");
        header.createCell(3).setCellValue("核心指标类型");
        header.createCell(4).setCellValue("子指标名称");
        header.createCell(5).setCellValue("指标类型");
        header.createCell(6).setCellValue("权重（可填 10 / 10% / 0.1）");
        header.createCell(7).setCellValue("下发备注");
        return sheet;
    }

    @Test
    @DisplayName("终版表头: blank group cells inherit previous group including task type; new group opens on change")
    void shouldInheritGroupWhenSerialAndTaskBlank() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = strategicSheet(workbook);

            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue(1);
            first.createCell(1).setCellValue("教务处");
            first.createCell(2).setCellValue("推进本科教育教学改革");
            first.createCell(3).setCellValue("基础性");
            first.createCell(4).setCellValue("建设智慧教学质量监测体系");
            first.createCell(5).setCellValue("定量");
            first.createCell(6).setCellValue(20);

            // 第 2 行：组列（序号/战略任务/任务类型）留空 = 同上；部门每行填
            Row second = sheet.createRow(2);
            second.createCell(1).setCellValue("教务处");
            second.createCell(4).setCellValue("建成教学质量数据平台");
            second.createCell(5).setCellValue("定量");
            second.createCell(6).setCellValue(30);

            // 第 3 行：新组开启（组号 2、新任务、新任务类型）
            Row third = sheet.createRow(3);
            third.createCell(0).setCellValue(2);
            third.createCell(2).setCellValue("完善专业建设质量保障机制");
            third.createCell(3).setCellValue("发展性");
            third.createCell(4).setCellValue("完成重点专业年度质量报告");
            third.createCell(5).setCellValue("定性");
            third.createCell(6).setCellValue(40);

            var parsed = parser.parseSheet(sheet, ImportType.STRATEGIC_TASK);

            assertEquals(3, parsed.rows().size());
            var stickyRow = parsed.rows().get(1);
            assertTrue(stickyRow.errors().isEmpty(),
                    "延续行不应有错误: " + stickyRow.errors());
            assertEquals(1, stickyRow.normalized().seq(), "留空序号应继承上一组组号");
            assertEquals("推进本科教育教学改革", stickyRow.normalized().strategicTask(), "留空任务应继承上一组任务");
            assertEquals("基础性", stickyRow.normalized().taskType(), "留空任务类型应继承组首行（任务级）");
            assertEquals("建成教学质量数据平台", stickyRow.normalized().indicatorName());
            assertEquals("教务处", stickyRow.normalized().department(), "部门列每行填，不参与继承");
            assertEquals(1, stickyRow.rowNo(), "预览行号展示为组号");

            var newGroupRow = parsed.rows().get(2);
            assertTrue(newGroupRow.errors().isEmpty());
            assertEquals(2, newGroupRow.normalized().seq());
            assertEquals("完善专业建设质量保障机制", newGroupRow.normalized().strategicTask());
            assertEquals("发展性", newGroupRow.normalized().taskType());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("终版表头: merged group cells (POI merged regions) parse identically to blank sticky rows")
    void shouldParseMergedCellsSameAsBlankStickyRows() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = strategicSheet(workbook);

            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue(1);
            first.createCell(1).setCellValue("教务处");
            first.createCell(2).setCellValue("推进本科教育教学改革");
            first.createCell(3).setCellValue("基础性");
            first.createCell(4).setCellValue("建设智慧教学质量监测体系");
            first.createCell(5).setCellValue("定量");
            first.createCell(6).setCellValue(60);

            // 第 2 行只填内容列——组列由合并区域覆盖（值只在锚点格，POI 读非锚点为空）
            Row second = sheet.createRow(2);
            second.createCell(1).setCellValue("教务处");
            second.createCell(4).setCellValue("建成教学质量数据平台");
            second.createCell(5).setCellValue("定量");
            second.createCell(6).setCellValue(40);
            // 序号 A2:A3、战略任务 C2:C3、任务类型 D2:D3 竖向合并
            sheet.addMergedRegion(new CellRangeAddress(1, 2, 0, 0));
            sheet.addMergedRegion(new CellRangeAddress(1, 2, 2, 2));
            sheet.addMergedRegion(new CellRangeAddress(1, 2, 3, 3));

            var parsed = parser.parseSheet(sheet, ImportType.STRATEGIC_TASK);

            assertEquals(2, parsed.rows().size());
            var mergedRow = parsed.rows().get(1);
            assertTrue(mergedRow.errors().isEmpty(),
                    "合并单元格从属行不应报错: " + mergedRow.errors());
            assertEquals(1, mergedRow.normalized().seq(), "合并区域从属行应归入锚点组（组号 1）");
            assertEquals("推进本科教育教学改革", mergedRow.normalized().strategicTask());
            assertEquals("基础性", mergedRow.normalized().taskType());
            assertEquals("建成教学质量数据平台", mergedRow.normalized().indicatorName());
            // 同组基础性 60+40=100 → 无组权重警告
            assertTrue(mergedRow.warnings().stream().noneMatch(msg -> msg.contains("应为 100")),
                    "合并版同组合计 100 不应警告: " + mergedRow.warnings());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("终版表头: distribution merged/blank rows inherit parent group and warn when children sum not 100")
    void shouldInheritParentGroupAndWarnWhenChildrenSumNot100() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = distributionSheet(workbook);

            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue(1);
            first.createCell(1).setCellValue("计算机学院");
            first.createCell(2).setCellValue("建设智慧教学质量监测体系");
            first.createCell(3).setCellValue("定量");
            first.createCell(4).setCellValue("完成课程质量数据接入");
            first.createCell(5).setCellValue("定量");
            first.createCell(6).setCellValue(40);

            // 第 2 行：组列（序号/学院/核心指标/核心指标类型）留空 = 同一父级拆第二个子指标
            Row second = sheet.createRow(2);
            second.createCell(4).setCellValue("建设院级教学督导队伍");
            second.createCell(5).setCellValue("定性");
            second.createCell(6).setCellValue(30);

            var parsed = parser.parseSheet(sheet, ImportType.DISTRIBUTION);

            assertEquals(2, parsed.rows().size());
            var stickyRow = parsed.rows().get(1);
            assertTrue(stickyRow.errors().isEmpty(), "延续行不应有错误: " + stickyRow.errors());
            assertEquals(1, stickyRow.normalized().seq(), "留空序号应继承父级组号");
            assertEquals("建设智慧教学质量监测体系", stickyRow.normalized().parentIndicator(),
                    "留空核心指标应继承上一组父级指标");
            assertEquals("定量", stickyRow.normalized().parentIndicatorType(),
                    "留空核心指标类型应继承组首行（父指标类型）");
            assertEquals("建设院级教学督导队伍", stickyRow.normalized().indicatorName());
            // 组内子指标 40+30=70 ≠ 100 → 组权重警告（终版文案以「核心指标」称呼父级列）
            assertTrue(stickyRow.warnings().stream().anyMatch(
                            msg -> msg.contains("核心指标「建设智慧教学质量监测体系」内子指标")
                                    && msg.contains("权重合计为 70")),
                    "组内子指标合计 70 应警告: " + stickyRow.warnings());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("2026-10-31: duplicate group number for a different task is rejected")
    void shouldRejectDuplicateGroupNumberForDifferentTask() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = strategicSheet(workbook);

            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue(1);
            first.createCell(2).setCellValue("任务一");
            first.createCell(3).setCellValue("发展性");
            first.createCell(4).setCellValue("指标甲");
            first.createCell(5).setCellValue("定量");
            first.createCell(6).setCellValue(10);

            Row second = sheet.createRow(2);
            second.createCell(0).setCellValue(1);
            second.createCell(2).setCellValue("任务二");
            second.createCell(3).setCellValue("发展性");
            second.createCell(4).setCellValue("指标乙");
            second.createCell(5).setCellValue("定量");
            second.createCell(6).setCellValue(10);

            var parsed = parser.parseSheet(sheet, ImportType.STRATEGIC_TASK);

            assertEquals(2, parsed.rows().size());
            var duplicated = parsed.rows().get(1);
            assertEquals(ImportAction.ERROR, duplicated.action());
            assertTrue(duplicated.errors().stream().anyMatch(msg -> msg.contains("组号（序号）不能重复")),
                    "应提示组号重复: " + duplicated.errors());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("2026-10-31: new group first row without serial is rejected")
    void shouldRequireSerialForNewGroupFirstRow() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = strategicSheet(workbook);

            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue(1);
            first.createCell(2).setCellValue("任务一");
            first.createCell(3).setCellValue("发展性");
            first.createCell(4).setCellValue("指标甲");
            first.createCell(5).setCellValue("定量");
            first.createCell(6).setCellValue(10);

            // 新任务没填组号 → 组首行缺序号，拒绝
            Row second = sheet.createRow(2);
            second.createCell(2).setCellValue("任务二");
            second.createCell(3).setCellValue("发展性");
            second.createCell(4).setCellValue("指标乙");
            second.createCell(5).setCellValue("定量");
            second.createCell(6).setCellValue(10);

            var parsed = parser.parseSheet(sheet, ImportType.STRATEGIC_TASK);

            var invalid = parsed.rows().get(1);
            assertEquals(ImportAction.ERROR, invalid.action());
            assertTrue(invalid.errors().stream().anyMatch(msg -> msg.contains("组首行「序号」（组号）不能为空")),
                    "应提示组首行缺组号: " + invalid.errors());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("2026-10-31: strategic group basic weights not summing to 100 produce warnings")
    void shouldWarnWhenStrategicGroupBasicWeightNot100() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = strategicSheet(workbook);

            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue(1);
            first.createCell(2).setCellValue("任务一");
            first.createCell(3).setCellValue("基础性");
            first.createCell(4).setCellValue("指标甲");
            first.createCell(5).setCellValue("定量");
            first.createCell(6).setCellValue(60);

            Row second = sheet.createRow(2);
            second.createCell(4).setCellValue("指标乙");
            second.createCell(5).setCellValue("定量");
            second.createCell(6).setCellValue(30);

            var parsed = parser.parseSheet(sheet, ImportType.STRATEGIC_TASK);

            assertEquals(2, parsed.rows().size());
            for (var row : parsed.rows()) {
                assertTrue(row.errors().isEmpty());
                assertTrue(row.warnings().stream().anyMatch(
                                msg -> msg.contains("第 1 组") && msg.contains("权重合计为 90") && msg.contains("应为 100")),
                        "组内基础性 60+30=90 应给出组权重警告: " + row.warnings());
            }
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("2026-10-31: group task type conflict on continuation row falls back to group head with warning")
    void shouldWarnWhenGroupTaskTypeConflicts() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = strategicSheet(workbook);

            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue(1);
            first.createCell(2).setCellValue("任务一");
            first.createCell(3).setCellValue("基础性");
            first.createCell(4).setCellValue("指标甲");
            first.createCell(5).setCellValue("定量");
            first.createCell(6).setCellValue(100);

            // 延续行显式填了不同任务类型：任务级以组首行为准 + 警告
            Row second = sheet.createRow(2);
            second.createCell(3).setCellValue("发展性");
            second.createCell(4).setCellValue("指标乙");
            second.createCell(5).setCellValue("定量");
            second.createCell(6).setCellValue(20);

            var parsed = parser.parseSheet(sheet, ImportType.STRATEGIC_TASK);

            var conflicting = parsed.rows().get(1);
            assertTrue(conflicting.errors().isEmpty());
            assertEquals("基础性", conflicting.normalized().taskType(),
                    "任务类型为任务级字段，应归一到组首行值");
            assertTrue(conflicting.warnings().stream().anyMatch(msg -> msg.contains("任务类型") && msg.contains("组首行")),
                    "应提示组内任务类型不一致: " + conflicting.warnings());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("2026-10-31: development-only group skips basic weight sum check; three weight notations equal 10")
    void shouldSupportThreeWeightFormatsAndSkipDevelopmentGroupSum() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = strategicSheet(workbook);

            // 同一发展性任务组内三种权重写法：10 / 10% / 0.1 都应换算为 10；
            // 发展性行不参与基础性合计校验（合计 30 不触发警告）
            String[] weights = {"10", "10%", "0.1"};
            for (int i = 0; i < weights.length; i++) {
                Row row = sheet.createRow(i + 1);
                if (i == 0) {
                    row.createCell(0).setCellValue(1);
                    row.createCell(2).setCellValue("任务一");
                    row.createCell(3).setCellValue("发展性");
                }
                row.createCell(4).setCellValue("指标" + (char) ('甲' + i));
                row.createCell(5).setCellValue("定量");
                row.createCell(6).setCellValue(weights[i]);
            }

            var parsed = parser.parseSheet(sheet, ImportType.STRATEGIC_TASK);

            assertEquals(3, parsed.rows().size());
            for (var row : parsed.rows()) {
                assertTrue(row.errors().isEmpty(), () -> row.normalized() + " / " + row.errors());
                assertEquals(0, new BigDecimal("10").compareTo(row.normalized().weight()),
                        "权重 " + row.normalized().weight() + " 应换算为 10");
                assertTrue(row.warnings().stream().noneMatch(msg -> msg.contains("应为 100")),
                        "发展性组不做基础性合计校验: " + row.warnings());
            }
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    // ==================== 2026-10-31 终版表头加列（学院模板「任务类型」列） ====================

    /**
     * 2026-10-31 终版表头加列（学院 9 列）：序号|学院|任务类型|核心指标|核心指标类型|子指标名称|指标类型|权重|下发备注
     */
    private Sheet distributionSheetV2(Workbook workbook) {
        Sheet sheet = workbook.createSheet("学院子指标模板");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("序号");
        header.createCell(1).setCellValue("学院");
        header.createCell(2).setCellValue("任务类型");
        header.createCell(3).setCellValue("核心指标");
        header.createCell(4).setCellValue("核心指标类型");
        header.createCell(5).setCellValue("子指标名称");
        header.createCell(6).setCellValue("指标类型");
        header.createCell(7).setCellValue("权重（可填 10 / 10% / 0.1）");
        header.createCell(8).setCellValue("下发备注");
        return sheet;
    }

    @Test
    @DisplayName("终版表头加列: 任务类型列随组 sticky 继承——延续行留空任务类型=继承组首行值")
    void shouldInheritTaskTypeColumnOnDistributionStickyRows() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = distributionSheetV2(workbook);

            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue(1);
            first.createCell(1).setCellValue("计算机学院");
            first.createCell(2).setCellValue("基础性");
            first.createCell(3).setCellValue("建设智慧教学质量监测体系");
            first.createCell(4).setCellValue("定量");
            first.createCell(5).setCellValue("完成课程质量数据接入");
            first.createCell(6).setCellValue("定量");
            first.createCell(7).setCellValue(40);

            // 第 2 行：组列（序号/任务类型/核心指标/核心指标类型）留空 = 同一父级拆第二个子指标；
            // 学院列留空 = 默认当前学院（整列合并的从属格同语义）
            Row second = sheet.createRow(2);
            second.createCell(5).setCellValue("建成教学质量看板");
            second.createCell(6).setCellValue("定量");
            second.createCell(7).setCellValue(60);

            var parsed = parser.parseSheet(sheet, ImportType.DISTRIBUTION);

            assertEquals(2, parsed.rows().size());
            var stickyRow = parsed.rows().get(1);
            assertTrue(stickyRow.errors().isEmpty(), "延续行不应有错误: " + stickyRow.errors());
            assertEquals(1, stickyRow.normalized().seq(), "留空序号应继承父级组号");
            assertEquals("基础性", stickyRow.normalized().taskType(),
                    "留空任务类型应继承组首行（父级所属任务类型）");
            assertEquals("建设智慧教学质量监测体系", stickyRow.normalized().parentIndicator());
            assertEquals("定量", stickyRow.normalized().parentIndicatorType());
            assertTrue(stickyRow.warnings().stream().noneMatch(msg -> msg.contains("任务类型")),
                    "任务类型继承不应产生冲突警告: " + stickyRow.warnings());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("终版表头加列: 合并单元格版式（任务类型/学院整列合并）与 sticky 留空解析同判")
    void shouldParseMergedTaskTypeAndWholeColumnCollegeSameAsSticky() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = distributionSheetV2(workbook);

            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue(1);
            first.createCell(1).setCellValue("计算机学院");
            first.createCell(2).setCellValue("基础性");
            first.createCell(3).setCellValue("建设智慧教学质量监测体系");
            first.createCell(4).setCellValue("定量");
            first.createCell(5).setCellValue("完成课程质量数据接入");
            first.createCell(6).setCellValue("定量");
            first.createCell(7).setCellValue(40);

            Row second = sheet.createRow(2);
            second.createCell(5).setCellValue("建成教学质量看板");
            second.createCell(6).setCellValue("定量");
            second.createCell(7).setCellValue(60);
            // 学院 B2:B3 整列合并；序号 A2:A3、任务类型 C2:C3、核心指标 D2:D3、核心指标类型 E2:E3 按组合并
            sheet.addMergedRegion(new CellRangeAddress(1, 2, 1, 1));
            sheet.addMergedRegion(new CellRangeAddress(1, 2, 0, 0));
            sheet.addMergedRegion(new CellRangeAddress(1, 2, 2, 2));
            sheet.addMergedRegion(new CellRangeAddress(1, 2, 3, 3));
            sheet.addMergedRegion(new CellRangeAddress(1, 2, 4, 4));

            var parsed = parser.parseSheet(sheet, ImportType.DISTRIBUTION);

            assertEquals(2, parsed.rows().size());
            var mergedRow = parsed.rows().get(1);
            assertTrue(mergedRow.errors().isEmpty(),
                    "合并单元格从属行不应报错: " + mergedRow.errors());
            assertEquals(1, mergedRow.normalized().seq(), "合并区域从属行应归入锚点组（组号 1）");
            assertEquals("计算机学院", mergedRow.normalized().college(),
                    "学院整列合并的从属格应从锚点格回填（与当前学院一致即通过）");
            assertEquals("基础性", mergedRow.normalized().taskType(),
                    "任务类型合并区域从属行应读回锚点值");
            assertEquals("建设智慧教学质量监测体系", mergedRow.normalized().parentIndicator());
            assertEquals("定量", mergedRow.normalized().parentIndicatorType());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("终版表头加列: 延续行显式填写不同任务类型 → 警告并归一到组首行值")
    void shouldWarnAndNormalizeWhenContinuationRowTaskTypeConflicts() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = distributionSheetV2(workbook);

            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue(1);
            first.createCell(1).setCellValue("计算机学院");
            first.createCell(2).setCellValue("基础性");
            first.createCell(3).setCellValue("建设智慧教学质量监测体系");
            first.createCell(4).setCellValue("定量");
            first.createCell(5).setCellValue("完成课程质量数据接入");
            first.createCell(6).setCellValue("定量");
            first.createCell(7).setCellValue(100);

            // 延续行显式填了不同任务类型：以组首行为准 + 警告（锚点值是否被改由 enrich 阶段按组首行判定）
            Row second = sheet.createRow(2);
            second.createCell(2).setCellValue("发展性");
            second.createCell(5).setCellValue("建成教学质量看板");
            second.createCell(6).setCellValue("定量");
            second.createCell(7).setCellValue(10);

            var parsed = parser.parseSheet(sheet, ImportType.DISTRIBUTION);

            var conflicting = parsed.rows().get(1);
            assertTrue(conflicting.errors().isEmpty());
            assertEquals("基础性", conflicting.normalized().taskType(),
                    "任务类型为父级级字段，应归一到组首行值");
            assertTrue(conflicting.warnings().stream()
                            .anyMatch(msg -> msg.contains("组内任务类型填写为 发展性")
                                    && msg.contains("与组首行（基础性）不一致")),
                    "应提示组内任务类型不一致: " + conflicting.warnings());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("终版表头加列: 「任务类别」表头别名兼容识别（旧口径表头照常解析）")
    void shouldParseTaskCategoryAliasOnDistributionSheet() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("学院子指标模板");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("序号");
            header.createCell(1).setCellValue("学院");
            header.createCell(2).setCellValue("任务类别");
            header.createCell(3).setCellValue("核心指标");
            header.createCell(4).setCellValue("核心指标类型");
            header.createCell(5).setCellValue("子指标名称");
            header.createCell(6).setCellValue("指标类型");
            header.createCell(7).setCellValue("权重");
            header.createCell(8).setCellValue("下发备注");

            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(1);
            data.createCell(1).setCellValue("计算机学院");
            data.createCell(2).setCellValue("基础性");
            data.createCell(3).setCellValue("建设智慧教学质量监测体系");
            data.createCell(4).setCellValue("定量");
            data.createCell(5).setCellValue("完成课程质量数据接入");
            data.createCell(6).setCellValue("定量");
            data.createCell(7).setCellValue(100);

            var parsed = parser.parseSheet(sheet, ImportType.DISTRIBUTION);

            assertEquals(1, parsed.rows().size());
            var row = parsed.rows().get(0);
            assertTrue(row.errors().isEmpty(), "别名表头不应报错: " + row.errors());
            assertEquals("基础性", row.normalized().taskType(),
                    "「任务类别」表头应识别为任务类型字段");
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    // ==================== 2026-11-12 权重列头改版（前端方案A）：「权重（纯数字）」别名兼容 ====================

    /**
     * 2026-11-12 前端模板/导出权重列头改为「权重（纯数字）」（全角括号）：职能部门与学院两条导入链
     * 的权重别名表都必须识别，否则整列不映射 → weight=null → 预览「-」+ 默认 100 警告。
     */
    @Test
    @DisplayName("新模板权重列头「权重（纯数字）」: 职能部门与学院两条导入链均正确解析数值权重")
    void shouldParseNumericWeightUnderNewPureNumberHeaderOnBothImportChains() {
        try (Workbook workbook = new XSSFWorkbook()) {
            // 职能部门链：序号|部门|战略任务|任务类型|核心指标|指标类型|权重（纯数字）|下发备注
            Sheet strategic = workbook.createSheet("职能部门指标模板");
            Row strategicHeader = strategic.createRow(0);
            strategicHeader.createCell(0).setCellValue("序号");
            strategicHeader.createCell(1).setCellValue("部门");
            strategicHeader.createCell(2).setCellValue("战略任务");
            strategicHeader.createCell(3).setCellValue("任务类型");
            strategicHeader.createCell(4).setCellValue("核心指标");
            strategicHeader.createCell(5).setCellValue("指标类型");
            strategicHeader.createCell(6).setCellValue("权重（纯数字）");
            strategicHeader.createCell(7).setCellValue("下发备注");

            Row strategicRow = strategic.createRow(1);
            strategicRow.createCell(0).setCellValue(1);
            strategicRow.createCell(1).setCellValue("教务处");
            strategicRow.createCell(2).setCellValue("测试任务");
            strategicRow.createCell(3).setCellValue("发展性");
            strategicRow.createCell(4).setCellValue("测试核心指标A");
            strategicRow.createCell(5).setCellValue("定量");
            strategicRow.createCell(6).setCellValue(40);
            strategicRow.createCell(7).setCellValue("按季度推进");

            var parsedStrategic = parser.parseSheet(strategic, ImportType.STRATEGIC_TASK);

            assertEquals(1, parsedStrategic.rows().size());
            var parsedStrategicRow = parsedStrategic.rows().get(0);
            assertTrue(parsedStrategicRow.errors().isEmpty(),
                    "新权重表头不应报错: " + parsedStrategicRow.errors());
            assertTrue(parsedStrategicRow.warnings().stream().noneMatch(msg -> msg.contains("权重为空")),
                    "权重 40 应正确解析，不应出现默认值警告: " + parsedStrategicRow.warnings());
            assertEquals(0, new BigDecimal("40").compareTo(parsedStrategicRow.normalized().weight()),
                    "「权重（纯数字）」表头下数值 40 应解析为 40（而非 null/默认 100）");

            // 学院链：序号|学院|任务类型|核心指标|核心指标类型|子指标名称|指标类型|权重（纯数字）|下发备注
            Sheet distribution = workbook.createSheet("学院子指标模板");
            Row distributionHeader = distribution.createRow(0);
            distributionHeader.createCell(0).setCellValue("序号");
            distributionHeader.createCell(1).setCellValue("学院");
            distributionHeader.createCell(2).setCellValue("任务类型");
            distributionHeader.createCell(3).setCellValue("核心指标");
            distributionHeader.createCell(4).setCellValue("核心指标类型");
            distributionHeader.createCell(5).setCellValue("子指标名称");
            distributionHeader.createCell(6).setCellValue("指标类型");
            distributionHeader.createCell(7).setCellValue("权重（纯数字）");
            distributionHeader.createCell(8).setCellValue("下发备注");

            Row distributionRow = distribution.createRow(1);
            distributionRow.createCell(0).setCellValue(1);
            distributionRow.createCell(1).setCellValue("计算机学院");
            distributionRow.createCell(2).setCellValue("定性");
            distributionRow.createCell(3).setCellValue("完善课程建设与专业评估机制");
            distributionRow.createCell(4).setCellValue("定性");
            distributionRow.createCell(5).setCellValue("完成课程质量数据接入");
            distributionRow.createCell(6).setCellValue("定量");
            distributionRow.createCell(7).setCellValue(40);
            distributionRow.createCell(8).setCellValue("按学期推进");

            var parsedDistribution = parser.parseSheet(distribution, ImportType.DISTRIBUTION);

            assertEquals(1, parsedDistribution.rows().size());
            var parsedDistributionRow = parsedDistribution.rows().get(0);
            assertTrue(parsedDistributionRow.errors().isEmpty(),
                    "新权重表头不应报错: " + parsedDistributionRow.errors());
            assertTrue(parsedDistributionRow.warnings().stream().noneMatch(msg -> msg.contains("权重为空")),
                    "权重 40 应正确解析，不应出现默认值警告: " + parsedDistributionRow.warnings());
            assertEquals(0, new BigDecimal("40").compareTo(parsedDistributionRow.normalized().weight()),
                    "学院链「权重（纯数字）」表头下数值 40 应解析为 40");
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    // ==================== 2026-11-26 学院导入序号语义改造（序号降级为子指标行号，纯展示） ====================

    /**
     * 2026-11-26 序号语义改造用例的行定义：序号|学院|任务类型|核心指标|核心指标类型|子指标名称|指标类型|权重。
     * 序号传 {@code null} 表示该格留空；核心指标传 {@code null} 表示留空（sticky 继承）。
     */
    private void addDistributionRow(Sheet sheet, int rowIndex, Integer seq,
                                    String parentIndicator, String childName, String weight) {
        Row row = sheet.createRow(rowIndex);
        if (seq != null) {
            row.createCell(0).setCellValue(seq);
        }
        row.createCell(1).setCellValue("计算机学院");
        row.createCell(2).setCellValue("基础性");
        if (parentIndicator != null) {
            row.createCell(3).setCellValue(parentIndicator);
            row.createCell(4).setCellValue("定量");
        }
        row.createCell(5).setCellValue(childName);
        row.createCell(6).setCellValue("定量");
        row.createCell(7).setCellValue(weight);
    }

    @Test
    @DisplayName("2026-11-26 序号语义改造: 同父级多子指标逐行递增序号 → 全部归同组，序号原样保留")
    void shouldGroupByParentIndicatorWhenSeqIncrementsPerRow() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = distributionSheetV2(workbook);
            addDistributionRow(sheet, 1, 1, "父级核心指标A", "子指标A1", "40");
            addDistributionRow(sheet, 2, 2, "父级核心指标A", "子指标A2", "30");
            addDistributionRow(sheet, 3, 3, "父级核心指标A", "子指标A3", "30");
            addDistributionRow(sheet, 4, 4, "父级核心指标B", "子指标B1", "100");

            var parsed = parser.parseSheet(sheet, ImportType.DISTRIBUTION);

            assertEquals(4, parsed.rows().size());
            // 归属锚定核心指标列：同父级三行全部归同组（序号不参与归属判定）
            for (int i = 0; i < 3; i++) {
                var row = parsed.rows().get(i);
                assertTrue(row.errors().isEmpty(), "递增序号行不应报错: " + row.errors());
                assertEquals("父级核心指标A", row.normalized().parentIndicator(),
                        "同父级行应按核心指标列归组");
            }
            // 序号=子指标行号：每行原样保留（不再是组号语义，不做组号查重/继承覆盖）
            for (int i = 0; i < 4; i++) {
                assertEquals(i + 1, parsed.rows().get(i).normalized().seq(),
                        "逐行递增序号应原样保留");
            }
            // 父级A 40+30+30=100、父级B 100 → 均无权重警告（权重按父级合计校验）
            for (var row : parsed.rows()) {
                assertTrue(row.warnings().stream().noneMatch(msg -> msg.contains("权重合计")),
                        "父级合计均为 100 不应有权重警告: " + row.warnings());
            }
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("2026-11-26 序号语义改造: 权重警告按父级 groupKey 合计点名（不再按序号分组、不带「第 N 组」）")
    void shouldWarnWeightSumByParentIndicatorGroupKey() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = distributionSheetV2(workbook);
            // 父级A 三行合计 90（≠100）→ 三行都警告；父级B 单行 100 → 不警告
            addDistributionRow(sheet, 1, 1, "父级核心指标A", "子指标A1", "40");
            addDistributionRow(sheet, 2, 2, "父级核心指标A", "子指标A2", "30");
            addDistributionRow(sheet, 3, 3, "父级核心指标A", "子指标A3", "20");
            addDistributionRow(sheet, 4, 4, "父级核心指标B", "子指标B1", "100");

            var parsed = parser.parseSheet(sheet, ImportType.DISTRIBUTION);

            assertEquals(4, parsed.rows().size());
            for (int i = 0; i < 3; i++) {
                var row = parsed.rows().get(i);
                assertTrue(row.warnings().stream().anyMatch(
                                msg -> msg.contains("核心指标「父级核心指标A」内子指标")
                                        && msg.contains("权重合计为 90") && msg.contains("应为 100")),
                        "父级A 三行应按父级合计 90 警告: " + row.warnings());
                assertTrue(row.warnings().stream().noneMatch(msg -> msg.contains("第 ")),
                        "学院模板权重警告不应再按组号点名: " + row.warnings());
            }
            var lastRow = parsed.rows().get(3);
            assertTrue(lastRow.warnings().stream().noneMatch(msg -> msg.contains("权重合计")),
                    "父级B 合计 100 不应警告: " + lastRow.warnings());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("2026-11-26 序号语义改造: 序号重复 → 行级警告（旧硬错误删除），归属仍按核心指标列")
    void shouldWarnInsteadOfErrorWhenDistributionSeqDuplicated() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = distributionSheetV2(workbook);
            // 旧口径：序号 1 被父级A 使用后又被父级B 使用 → 行级硬错误「序号已被…使用」；
            // 新口径：序号=行号纯展示，重复仅警告，两行照常落库
            addDistributionRow(sheet, 1, 1, "父级核心指标A", "子指标A1", "100");
            addDistributionRow(sheet, 2, 1, "父级核心指标B", "子指标B1", "100");

            var parsed = parser.parseSheet(sheet, ImportType.DISTRIBUTION);

            assertEquals(2, parsed.rows().size());
            var duplicated = parsed.rows().get(1);
            assertEquals(ImportAction.CREATE, duplicated.action(),
                    "序号重复不应再阻断（无其他错误时仍为可导入行）");
            assertTrue(duplicated.errors().isEmpty(),
                    "学院模板序号重复不应产生任何错误: " + duplicated.errors());
            assertTrue(duplicated.warnings().stream().anyMatch(msg -> msg.contains("序号 1 重复，已按行处理")),
                    "重复序号应给出行级警告: " + duplicated.warnings());
            assertEquals("父级核心指标B", duplicated.normalized().parentIndicator(),
                    "归属仍按核心指标列判定，不受序号重复影响");
            var first = parsed.rows().get(0);
            assertTrue(first.warnings().stream().noneMatch(msg -> msg.contains("重复")),
                    "首次出现的序号不应警告: " + first.warnings());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("2026-11-26 序号语义改造: 序号留空 → 按物理行号自动补号（纯展示定位用）")
    void shouldAutoFillMissingSeqWithPhysicalRowNumber() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = distributionSheetV2(workbook);
            // 全部行序号留空（新模板口径：留空自动按行补）；数据行位于 Excel 第 2/3 行
            addDistributionRow(sheet, 1, null, "父级核心指标A", "子指标A1", "50");
            addDistributionRow(sheet, 2, null, "父级核心指标B", "子指标B1", "100");

            var parsed = parser.parseSheet(sheet, ImportType.DISTRIBUTION);

            assertEquals(2, parsed.rows().size());
            for (int i = 0; i < 2; i++) {
                var row = parsed.rows().get(i);
                assertTrue(row.errors().isEmpty(), "序号留空不应报错: " + row.errors());
                int expectedPhysicalRowNo = i + 2; // 表头在第 1 行，数据行物理行号从 2 起
                assertEquals(expectedPhysicalRowNo, row.normalized().seq(),
                        "留空序号应自动补物理行号");
                assertEquals(expectedPhysicalRowNo, row.rowNo(), "预览「序号」列应显示补号后的行号");
            }
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("2026-11-26 序号语义改造: 旧文件写法（序号=组号+组内留空延续）兼容解析，组号当行号显示")
    void shouldParseLegacyGroupNumberStyleFileWithoutDataLoss() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = distributionSheetV2(workbook);
            // 旧写法：组首行带组号 1，组内两行序号/核心指标留空（sticky 延续）
            addDistributionRow(sheet, 1, 1, "父级核心指标A", "子指标A1", "40");
            addDistributionRow(sheet, 2, null, null, "子指标A2", "30");
            addDistributionRow(sheet, 3, null, null, "子指标A3", "30");

            var parsed = parser.parseSheet(sheet, ImportType.DISTRIBUTION);

            assertEquals(3, parsed.rows().size());
            for (int i = 0; i < 3; i++) {
                var row = parsed.rows().get(i);
                assertTrue(row.errors().isEmpty(), "旧写法文件不应报错: " + row.errors());
                assertTrue(row.warnings().stream().noneMatch(msg -> msg.contains("重复")),
                        "旧写法组首行唯一组号不应触发重复警告: " + row.warnings());
                assertEquals(1, row.normalized().seq(),
                        "旧组号按展示行号沿用（延续行继承组首行序号）");
                assertEquals(1, row.rowNo(), "预览「序号」列显示组号（无数据损失）");
                assertEquals("父级核心指标A", row.normalized().parentIndicator(),
                        "组内归属按核心指标列 sticky 继承");
            }
            // 40+30+30=100 → 无权重警告
            for (var row : parsed.rows()) {
                assertTrue(row.warnings().stream().noneMatch(msg -> msg.contains("权重合计")),
                        "旧写法组内合计 100 不应警告: " + row.warnings());
            }
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @DisplayName("2026-11-26 序号语义改造: STRATEGIC_TASK 不受影响——组号重复仍硬错误、缺组号仍拒绝")
    void strategicTaskKeepsLegacyGroupNumberSemantics() {
        // 场景1：职能部门模板同组号不同任务 → 仍为行级硬错误（模板01 口径不变）
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet strategic = strategicSheet(workbook);
            Row first = strategic.createRow(1);
            first.createCell(0).setCellValue(1);
            first.createCell(2).setCellValue("任务一");
            first.createCell(3).setCellValue("发展性");
            first.createCell(4).setCellValue("指标甲");
            first.createCell(5).setCellValue("定量");
            first.createCell(6).setCellValue(10);
            Row second = strategic.createRow(2);
            second.createCell(0).setCellValue(1);
            second.createCell(2).setCellValue("任务二");
            second.createCell(3).setCellValue("发展性");
            second.createCell(4).setCellValue("指标乙");
            second.createCell(5).setCellValue("定量");
            second.createCell(6).setCellValue(10);

            var parsedStrategic = parser.parseSheet(strategic, ImportType.STRATEGIC_TASK);
            var duplicated = parsedStrategic.rows().get(1);
            assertEquals(ImportAction.ERROR, duplicated.action(),
                    "职能部门模板组号重复仍是硬错误（本次改造不涉及）");
            assertTrue(duplicated.errors().stream().anyMatch(msg -> msg.contains("组号（序号）不能重复")),
                    "职能部门模板组号查重文案不变: " + duplicated.errors());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }

        // 场景2：新组首行缺组号 → 仍拒绝（序号在模板01 仍是必填组号）
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet strategic2 = strategicSheet(workbook);
            Row head = strategic2.createRow(1);
            head.createCell(0).setCellValue(1);
            head.createCell(2).setCellValue("任务一");
            head.createCell(3).setCellValue("发展性");
            head.createCell(4).setCellValue("指标甲");
            head.createCell(5).setCellValue("定量");
            head.createCell(6).setCellValue(10);
            Row noSeq = strategic2.createRow(2);
            noSeq.createCell(2).setCellValue("任务二");
            noSeq.createCell(3).setCellValue("发展性");
            noSeq.createCell(4).setCellValue("指标乙");
            noSeq.createCell(5).setCellValue("定量");
            noSeq.createCell(6).setCellValue(10);

            var parsedStrategic2 = parser.parseSheet(strategic2, ImportType.STRATEGIC_TASK);
            var invalid = parsedStrategic2.rows().get(1);
            assertEquals(ImportAction.ERROR, invalid.action(),
                    "职能部门模板新组首行缺组号仍拒绝");
            assertTrue(invalid.errors().stream().anyMatch(msg -> msg.contains("组首行「序号」（组号）不能为空")),
                    "职能部门模板缺组号文案不变: " + invalid.errors());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
