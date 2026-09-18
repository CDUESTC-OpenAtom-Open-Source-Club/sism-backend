package com.sism.task.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务改名留痕（「已更改 N 次」）单元测试。与 IndicatorMutationService 共用
 * audit_log 快照结构，entity_type = 'TASK'。
 */
@ExtendWith(MockitoExtension.class)
class TaskMutationHistoryServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private TaskMutationHistoryService service;

    @Test
    void recordRenameShouldInsertTaskSnapshot() {
        service.recordRename(7L, "旧任务名", "新任务名", 9001L);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        Object[] values = args.getValue();
        assertEquals("UPDATE", values[0]);
        assertEquals(Map.of("name", "旧任务名"), parse(values[1]));
        assertEquals(Map.of("name", "新任务名"), parse(values[2]));
        assertEquals(Map.of("name", Map.of("before", "旧任务名", "after", "新任务名")), parse(values[3]));
        assertEquals(7L, values[4]);
        assertEquals(9001L, values[6]);
    }

    @Test
    void recordRenameShouldSkipWhenNameUnchanged() {
        service.recordRename(7L, "同名", "同名", 9001L);
        service.recordRename(7L, null, null, 9001L);
        service.recordRename(null, "a", "b", 9001L);

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void historyShouldReturnParsedSnapshots() {
        LocalDateTime at = LocalDateTime.of(2026, 9, 18, 12, 0);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(7L))).thenAnswer(invocation -> {
            RowMapper<Map<String, Object>> mapper = invocation.getArgument(1);
            java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
            when(rs.getLong("log_id")).thenReturn(42L);
            when(rs.getString("action")).thenReturn("UPDATE");
            when(rs.getString("before_json")).thenReturn("{\"name\":\"旧\"}");
            when(rs.getString("after_json")).thenReturn("{\"name\":\"新\"}");
            when(rs.getString("changed_fields")).thenReturn("{\"name\":{\"before\":\"旧\",\"after\":\"新\"}}");
            when(rs.getObject("actor_user_id")).thenReturn(9001L);
            when(rs.getTimestamp("created_at")).thenReturn(Timestamp.valueOf(at));
            return List.of(mapper.mapRow(rs, 0));
        });

        List<Map<String, Object>> rows = service.history(7L);

        assertEquals(1, rows.size());
        assertEquals(42L, rows.get(0).get("log_id"));
        assertEquals(Map.of("name", "旧"), rows.get(0).get("before_json"));
        assertEquals(Map.of("name", "新"), rows.get(0).get("after_json"));
        assertEquals(Map.of("name", Map.of("before", "旧", "after", "新")), rows.get(0).get("changed_fields"));
        assertEquals(at, rows.get(0).get("created_at"));
    }

    @Test
    void historyShouldTolerateBrokenJson() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(7L))).thenAnswer(invocation -> {
            RowMapper<Map<String, Object>> mapper = invocation.getArgument(1);
            java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
            when(rs.getLong("log_id")).thenReturn(43L);
            when(rs.getString("action")).thenReturn("UPDATE");
            when(rs.getString("before_json")).thenReturn("{\"name\":");
            when(rs.getString("after_json")).thenReturn((String) null);
            when(rs.getString("changed_fields")).thenReturn("not-json-at-all");
            when(rs.getObject("actor_user_id")).thenReturn(9001L);
            when(rs.getTimestamp("created_at")).thenReturn(Timestamp.valueOf(LocalDateTime.now()));
            return List.of(mapper.mapRow(rs, 0));
        });

        List<Map<String, Object>> rows = service.history(7L);

        assertNull(rows.get(0).get("before_json"));
        assertNull(rows.get(0).get("after_json"));
        assertNull(rows.get(0).get("changed_fields"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(Object json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue((String) json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
