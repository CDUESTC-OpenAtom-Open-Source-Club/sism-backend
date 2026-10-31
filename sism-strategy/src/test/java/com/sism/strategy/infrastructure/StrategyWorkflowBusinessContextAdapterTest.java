package com.sism.strategy.infrastructure;

import com.sism.organization.domain.OrganizationRepository;
import com.sism.organization.domain.SysOrg;
import com.sism.shared.domain.workflow.WorkflowBusinessContextPort.BusinessSummary;
import com.sism.strategy.domain.repository.PlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审批业务上下文（INDICATOR 分支）摘要解析。
 *
 * 2026-10-31 用户定案：取消异动终结含新增行的批次后，新增指标行走软删除
 * （is_deleted=true，表格不可见、数据保留），但审批中心「审批历史」的
 * 「已取消」卡必须仍能展示当时新增的内容——摘要查不到活行时回落
 * audit_log 快照（entity_type='INDICATOR' 最新一行 after_json 的 indicator_desc）。
 */
@ExtendWith(MockitoExtension.class)
class StrategyWorkflowBusinessContextAdapterTest {

    private static final Long INDICATOR_ID = 2062L;

    @Mock
    private PlanRepository planRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private StrategyWorkflowBusinessContextAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new StrategyWorkflowBusinessContextAdapter(planRepository, organizationRepository, jdbcTemplate);
    }

    @Test
    @DisplayName("活行存在：按原口径返回摘要，不触发 audit_log 快照兜底")
    void liveIndicator_shouldReturnSummaryWithoutSnapshotFallback() {
        BusinessSummary live = new BusinessSummary(null, "在审指标", 88L, "战略发展部", 99L, "某部门", "在审指标");
        when(jdbcTemplate.query(
                contains("COALESCE(is_deleted, false) = false"),
                any(ResultSetExtractor.class),
                eq(INDICATOR_ID)))
                .thenReturn(Optional.of(live));

        Optional<BusinessSummary> summary = adapter.getBusinessSummary("INDICATOR", INDICATOR_ID);

        assertTrue(summary.isPresent());
        assertEquals("在审指标", summary.get().displayName());
        verify(jdbcTemplate, never()).query(contains("public.audit_log"), any(ResultSetExtractor.class), eq(INDICATOR_ID));
    }

    @Test
    @DisplayName("软删除行回落 audit_log 快照：已取消新增行的审批历史卡仍展示当时标题")
    void softDeletedIndicator_shouldFallBackToAuditSnapshotSummary() {
        // ① 活行被 is_deleted 过滤 → 查空（软删除后的唯一形态）
        when(jdbcTemplate.query(
                contains("COALESCE(is_deleted, false) = false"),
                any(ResultSetExtractor.class),
                eq(INDICATOR_ID)))
                .thenReturn(Optional.empty());

        // ② 兜底查询：真实执行行映射（模拟 DB 返回 audit_log 快照标题 + 行上组织归属）
        Answer<Optional<BusinessSummary>> fallback = invocation -> {
            String sql = invocation.getArgument(0);
            // 兜底 SQL 必须不带 is_deleted 过滤，且从 audit_log 快照取标题
            assertFalse(sql.contains("is_deleted"), "兜底查询不能带 is_deleted 过滤: " + sql);
            assertTrue(sql.contains("public.audit_log"), "兜底查询必须取 audit_log 快照: " + sql);
            assertTrue(sql.contains("after_json ->> 'indicator_desc'"), "标题必须优先取快照 after 值: " + sql);
            ResultSetExtractor<Optional<BusinessSummary>> extractor = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getLong("owner_org_id")).thenReturn(88L);
            when(rs.getLong("target_org_id")).thenReturn(99L);
            when(rs.getString("indicator_desc")).thenReturn("已取消新增的指标描述");
            return extractor.extractData(rs);
        };
        doAnswer(fallback).when(jdbcTemplate).query(
                contains("public.audit_log"), any(ResultSetExtractor.class), eq(INDICATOR_ID));
        SysOrg source = mock(SysOrg.class);
        when(source.getName()).thenReturn("战略发展部");
        SysOrg target = mock(SysOrg.class);
        when(target.getName()).thenReturn("目标部门");
        when(organizationRepository.findById(88L)).thenReturn(Optional.of(source));
        when(organizationRepository.findById(99L)).thenReturn(Optional.of(target));

        Optional<BusinessSummary> summary = adapter.getBusinessSummary("INDICATOR", INDICATOR_ID);

        assertTrue(summary.isPresent(), "软删除行必须回落快照拼出摘要，审批历史卡不能丢标题");
        assertEquals("已取消新增的指标描述", summary.get().displayName());
        assertEquals(88L, summary.get().sourceOrgId());
        assertEquals("战略发展部", summary.get().sourceOrgName());
        assertEquals(99L, summary.get().targetOrgId());
        assertEquals("目标部门", summary.get().targetOrgName());
    }

    @Test
    @DisplayName("快照标题缺失时回落行值；活行与快照都查不到返回 empty")
    void fallbackWithoutSnapshotTitle_shouldUseRowValueAndEmptyWhenNothing() {
        when(jdbcTemplate.query(
                contains("COALESCE(is_deleted, false) = false"),
                any(ResultSetExtractor.class),
                eq(INDICATOR_ID)))
                .thenReturn(Optional.empty());
        // 兜底查询 DB 返回零行（行物理不存在/快照标题与行值皆空）
        doAnswer(invocation -> ((ResultSetExtractor<Optional<BusinessSummary>>) invocation.getArgument(1))
                .extractData(mock(ResultSet.class)))
                .when(jdbcTemplate).query(
                        contains("public.audit_log"), any(ResultSetExtractor.class), eq(INDICATOR_ID));

        Optional<BusinessSummary> summary = adapter.getBusinessSummary("INDICATOR", INDICATOR_ID);

        assertFalse(summary.isPresent());
    }
}
