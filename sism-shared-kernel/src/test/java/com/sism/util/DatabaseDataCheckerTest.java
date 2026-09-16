package com.sism.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseDataCheckerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldCountMainTablesUsingTrustedSchemaQualifiedNames() {
        DatabaseDataChecker checker = new DatabaseDataChecker(jdbcTemplate);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.sys_org", Long.class)).thenReturn(3L);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.sys_user", Long.class)).thenReturn(4L);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.sys_role", Long.class)).thenReturn(5L);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.assessment_cycle", Long.class)).thenReturn(6L);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.sys_task", Long.class)).thenReturn(7L);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.indicator", Long.class)).thenReturn(8L);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.progress_report", Long.class)).thenReturn(10L);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.adhoc_task", Long.class)).thenReturn(11L);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.adhoc_task_indicator_map", Long.class)).thenReturn(12L);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.adhoc_task_target", Long.class)).thenReturn(13L);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.approval_record", Long.class)).thenReturn(14L);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.audit_log", Long.class)).thenReturn(15L);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.plan", Long.class)).thenReturn(16L);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.plan_report", Long.class)).thenReturn(17L);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.alert_rule", Long.class)).thenReturn(18L);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.alert_event", Long.class)).thenReturn(19L);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.alert_window", Long.class)).thenReturn(20L);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.refresh_token", Long.class)).thenReturn(21L);

        Map<String, Long> counts = checker.getAllTableCounts();

        assertEquals(18, counts.size());
        assertEquals(3L, counts.get("sys_org"));
        assertEquals(21L, counts.get("refresh_token"));
        verify(jdbcTemplate).queryForObject("SELECT COUNT(*) FROM public.sys_org", Long.class);
        verify(jdbcTemplate).queryForObject("SELECT COUNT(*) FROM public.refresh_token", Long.class);
    }
}
