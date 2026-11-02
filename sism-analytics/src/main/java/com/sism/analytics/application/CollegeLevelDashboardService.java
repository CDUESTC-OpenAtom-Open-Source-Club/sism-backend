package com.sism.analytics.application;

import com.sism.analytics.domain.repository.CollegeLevelQueryRepository;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeLevelCountRow;
import com.sism.analytics.domain.repository.CollegeLevelQueryRepository.CollegeLevelMonthlyCountRow;
import com.sism.analytics.interfaces.dto.CollegeLevelDTO;
import com.sism.analytics.interfaces.dto.CollegeLevelsResponseDTO;
import com.sism.analytics.interfaces.dto.CollegeLevelTrendResponseDTO;
import com.sism.analytics.interfaces.dto.CollegeLevelTrendResponseDTO.CollegeLevelMonthDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * CollegeLevelDashboardService - 学院指标完成情况/月度趋势聚合服务。
 *
 * <p>2026-10-31 用户拍板口径：数据看板「学院指标完成情况/月度趋势」改为后端按月报真实数据聚合，
 * 未填报学院不返回、只算已提交/已审批月报、等级只看上级鉴定 appraisal_level、
 * 手动预警（alert_event）彻底退出看板。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class CollegeLevelDashboardService {

    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2999;
    private static final int MIN_MONTH = 1;
    private static final int MAX_MONTH = 12;

    private final CollegeLevelQueryRepository collegeLevelQueryRepository;

    public CollegeLevelDashboardService(CollegeLevelQueryRepository collegeLevelQueryRepository) {
        this.collegeLevelQueryRepository = collegeLevelQueryRepository;
    }

    /**
     * 获取指定月份各学院鉴定等级计数。未上报（当月无已鉴定上报记录）的学院不返回。
     */
    public CollegeLevelsResponseDTO getCollegeLevels(Integer year, Integer month) {
        requireYear(year);
        requireMonth(month);
        List<CollegeLevelDTO> colleges = collegeLevelQueryRepository
                .fetchCollegeLevelCounts(toReportMonth(year, month))
                .stream()
                .map(CollegeLevelDashboardService::toCollegeLevel)
                .toList();
        return CollegeLevelsResponseDTO.builder()
                .year(year)
                .month(month)
                .colleges(colleges)
                .build();
    }

    /**
     * 获取年度趋势。months 仅含有计数 &gt; 0 的月份（升序），无数据月份不出现。
     */
    public CollegeLevelTrendResponseDTO getCollegeLevelsTrend(Integer year) {
        requireYear(year);
        Map<Integer, List<CollegeLevelDTO>> collegesByMonth = new TreeMap<>();
        for (CollegeLevelMonthlyCountRow row : collegeLevelQueryRepository
                .fetchCollegeLevelMonthlyCounts(toReportMonth(year, MIN_MONTH), toReportMonth(year, MAX_MONTH))) {
            collegesByMonth
                    .computeIfAbsent(row.month(), ignored -> new ArrayList<>())
                    .add(toCollegeLevel(row));
        }
        List<CollegeLevelMonthDTO> months = collegesByMonth.entrySet().stream()
                .map(entry -> CollegeLevelMonthDTO.builder()
                        .month(entry.getKey())
                        .colleges(List.copyOf(entry.getValue()))
                        .build())
                .toList();
        return CollegeLevelTrendResponseDTO.builder()
                .year(year)
                .months(months)
                .build();
    }

    private static CollegeLevelDTO toCollegeLevel(CollegeLevelCountRow row) {
        return CollegeLevelDTO.builder()
                .orgId(row.orgId())
                .orgName(row.orgName())
                .ahead(row.ahead())
                .normal(row.normal())
                .delayed(row.delayed())
                .total(row.total())
                .build();
    }

    private static CollegeLevelDTO toCollegeLevel(CollegeLevelMonthlyCountRow row) {
        return CollegeLevelDTO.builder()
                .orgId(row.orgId())
                .orgName(row.orgName())
                .ahead(row.ahead())
                .normal(row.normal())
                .delayed(row.delayed())
                .total(row.total())
                .build();
    }

    /**
     * 年份必填且限定 2000-2999；月份必填且限定 1-12。非法输入由
     * GlobalExceptionHandler 统一映射为 400 并透传本条说人话 message。
     */
    private static void requireYear(Integer year) {
        if (year == null) {
            throw new IllegalArgumentException("缺少必填参数 year（年份），请传入 " + MIN_YEAR + "-" + MAX_YEAR + " 之间的整数");
        }
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new IllegalArgumentException("参数 year（年份）必须在 " + MIN_YEAR + "-" + MAX_YEAR + " 之间，当前值：" + year);
        }
    }

    private static void requireMonth(Integer month) {
        if (month == null) {
            throw new IllegalArgumentException("缺少必填参数 month（月份），请传入 " + MIN_MONTH + "-" + MAX_MONTH + " 之间的整数");
        }
        if (month < MIN_MONTH || month > MAX_MONTH) {
            throw new IllegalArgumentException("参数 month（月份）必须在 " + MIN_MONTH + "-" + MAX_MONTH + " 之间，当前值：" + month);
        }
    }

    private static String toReportMonth(int year, int month) {
        return "%d%02d".formatted(year, month);
    }
}
