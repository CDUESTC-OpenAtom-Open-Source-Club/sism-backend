package com.sism.task.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 战略任务变更留痕：任务改名同样计入「已更改 N 次」。
 *
 * <p>与 {@code IndicatorMutationService} 共用 {@code audit_log} 快照结构
 * （before_json/after_json/changed_fields），前端「已更改 N 次」浮层对
 * 指标与任务使用同一套渲染逻辑，仅 entity_type 不同（INDICATOR / TASK）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskMutationHistoryService {

    private static final String TASK_ENTITY_TYPE = "TASK";
    private static final String FIELD_NAME = "name";

    private final JdbcTemplate jdbcTemplate;

    /** 任务改名留痕；名称未变化时不留痕（避免「已更改 0 次」噪音）。 */
    public void recordRename(Long taskId, String beforeName, String afterName, Long actorUserId) {
        if (taskId == null || taskId <= 0) {
            return;
        }
        String before = beforeName == null ? null : beforeName.trim();
        String after = afterName == null ? null : afterName.trim();
        if (java.util.Objects.equals(before, after)) {
            return;
        }

        Map<String, Object> beforeJson = new LinkedHashMap<>();
        beforeJson.put(FIELD_NAME, before);
        Map<String, Object> afterJson = new LinkedHashMap<>();
        afterJson.put(FIELD_NAME, after);
        Map<String, Object> changedFields = new LinkedHashMap<>();
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("before", before);
        delta.put("after", after);
        changedFields.put(FIELD_NAME, delta);

        jdbcTemplate.update(
                """
                INSERT INTO public.audit_log (
                    action, before_json, after_json, changed_fields,
                    created_at, entity_id, entity_type, reason, actor_user_id
                )
                VALUES (?, ?::jsonb, ?::jsonb, ?::jsonb, CURRENT_TIMESTAMP, ?, 'TASK', ?, ?)
                """,
                "UPDATE",
                toJson(beforeJson),
                toJson(afterJson),
                toJson(changedFields),
                taskId,
                "战略任务异动",
                actorUserId
        );
    }

    /** 任务变更历史（「已更改 N 次」数据源）。 */
    public List<Map<String, Object>> history(Long taskId) {
        return jdbcTemplate.query(
                """
                SELECT log_id, action, before_json::text AS before_json, after_json::text AS after_json,
                       changed_fields::text AS changed_fields, actor_user_id, created_at
                FROM public.audit_log
                WHERE entity_type = 'TASK' AND entity_id = ?
                ORDER BY created_at DESC
                """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("log_id", rs.getLong("log_id"));
                    row.put("action", rs.getString("action"));
                    row.put("before_json", parseJson(rs.getString("before_json")));
                    row.put("after_json", parseJson(rs.getString("after_json")));
                    row.put("changed_fields", parseJson(rs.getString("changed_fields")));
                    row.put("actor_user_id", rs.getObject("actor_user_id"));
                    row.put("created_at", rs.getTimestamp("created_at").toLocalDateTime());
                    return row;
                },
                taskId);
    }

    /**
     * jsonb 列经 JdbcTemplate 取出时是 PGobject，直接返回会被序列化成
     * {"type":"jsonb","value":"...","null":false}，前端无法解析。这里显式解析为对象。
     */
    private Object parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new ObjectMapper().readValue(raw, Map.class);
        } catch (Exception e) {
            log.warn("[TaskMutationHistory] 解析快照 JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化任务变更快照失败", e);
        }
    }
}
