package com.sism.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Database Data Checker Utility
 * Checks database tables, records, and data quality
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseDataChecker {

    private static final List<String> MAIN_TABLES = List.of(
            "sys_org",
            "sys_user",
            "sys_role",
            "assessment_cycle",
            "sys_task",
            "indicator",
            "progress_report",
            "adhoc_task",
            "adhoc_task_indicator_map",
            "adhoc_task_target",
            "approval_record",
            "audit_log",
            "plan",
            "plan_report",
            "alert_rule",
            "alert_event",
            "alert_window",
            "refresh_token"
    );

    private final JdbcTemplate jdbcTemplate;

    /**
     * Get all table information
     */
    public List<Map<String, Object>> getAllTables() {
        String sql = """
            SELECT
                table_name,
                (SELECT COUNT(*) FROM information_schema.columns WHERE table_name = t.table_name AND table_schema = 'public') as column_count
            FROM information_schema.tables t
            WHERE table_schema = 'public'
                AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """;

        return jdbcTemplate.queryForList(sql);
    }

    /**
     * Get record counts for all main tables
     */
    public Map<String, Long> getAllTableCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();

        for (String table : MAIN_TABLES) {
            try {
                Long count = countRowsFromTrustedTable(table);
                counts.put(table, count != null ? count : 0L);
            } catch (Exception e) {
                counts.put(table, 0L);
                log.warn("Table {} does not exist or is not accessible: {}", table, e.getMessage());
            }
        }

        return counts;
    }

    private Long countRowsFromTrustedTable(String tableName) {
        if (!MAIN_TABLES.contains(tableName)) {
            throw new IllegalArgumentException("Unsupported table name: " + tableName);
        }
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public." + tableName, Long.class);
    }

    /**
     * Check data quality issues
     */
    public Map<String, Object> checkDataQuality() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> issues = new ArrayList<>();

        // Check 1: Orphaned indicators (task_id not found)
        try {
            List<Map<String, Object>> orphanIndicators = jdbcTemplate.queryForList("""
                SELECT i.id, i.indicator_desc, i.task_id
                FROM indicator i
                LEFT JOIN sys_task t ON i.task_id = t.task_id
                WHERE t.task_id IS NULL
                LIMIT 10
                """);

            if (!orphanIndicators.isEmpty()) {
                issues.add(Map.of(
                    "type", "ORPHANED_INDICATORS",
                    "description", "Indicators with invalid task_id",
                    "count", orphanIndicators.size(),
                    "samples", orphanIndicators
                ));
            }
        } catch (Exception e) {
            log.warn("Error checking orphaned indicators: {}", e.getMessage());
        }

        // Check 2: Indicators without owner_org
        try {
            Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM indicator WHERE owner_org_id IS NULL
                """, Long.class);

            if (count != null && count > 0) {
                issues.add(Map.of(
                    "type", "INDICATORS_WITHOUT_OWNER",
                    "description", "Indicators without owner organization",
                    "count", count
                ));
            }
        } catch (Exception e) {
            log.warn("Error checking indicators without owner: {}", e.getMessage());
        }

        // Check 3: Indicators without target_org
        try {
            Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM indicator WHERE target_org_id IS NULL
                """, Long.class);

            if (count != null && count > 0) {
                issues.add(Map.of(
                    "type", "INDICATORS_WITHOUT_TARGET",
                    "description", "Indicators without target organization",
                    "count", count
                ));
            }
        } catch (Exception e) {
            log.warn("Error checking indicators without target: {}", e.getMessage());
        }

        // Check 4: Empty tables
        Map<String, Long> tableCounts = getAllTableCounts();
        List<String> emptyTables = new ArrayList<>();
        for (Map.Entry<String, Long> entry : tableCounts.entrySet()) {
            if (entry.getValue() == 0) {
                emptyTables.add(entry.getKey());
            }
        }

        if (!emptyTables.isEmpty()) {
            issues.add(Map.of(
                "type", "EMPTY_TABLES",
                "description", "Tables with no data",
                "count", emptyTables.size(),
                "tables", emptyTables
            ));
        }

        result.put("totalIssues", issues.size());
        result.put("issues", issues);
        result.put("tableCounts", tableCounts);

        return result;
    }

    /**
     * Sample data from key tables
     */
    public Map<String, Object> sampleData() {
        Map<String, Object> samples = new HashMap<>();

        // Sample indicators
        try {
            List<Map<String, Object>> indicators = jdbcTemplate.queryForList("""
                SELECT id, indicator_desc, task_id, owner_org_id, target_org_id,
                       progress, status, year
                FROM indicator
                ORDER BY id
                LIMIT 5
                """);
            samples.put("indicators", indicators);
        } catch (Exception e) {
            samples.put("indicators", List.of());
        }

        // Sample tasks
        try {
            List<Map<String, Object>> tasks = jdbcTemplate.queryForList("""
                SELECT task_id, name, task_type, cycle_id, org_id, created_by_org_id
                FROM sys_task
                ORDER BY task_id
                LIMIT 5
                """);
            samples.put("tasks", tasks);
        } catch (Exception e) {
            samples.put("tasks", List.of());
        }

        // Sample orgs
        try {
            List<Map<String, Object>> orgs = jdbcTemplate.queryForList("""
                SELECT id, name, type, parent_id, is_active
                FROM sys_org
                ORDER BY id
                LIMIT 5
                """);
            samples.put("organizations", orgs);
        } catch (Exception e) {
            samples.put("organizations", List.of());
        }

        return samples;
    }
}
