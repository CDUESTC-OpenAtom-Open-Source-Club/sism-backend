package com.sism.execution.infrastructure.persistence;

import com.sism.execution.domain.report.PlanReportAttachmentSnapshot;
import com.sism.execution.domain.report.PlanReportIndicatorRepository;
import com.sism.execution.domain.report.PlanReportIndicatorSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class JdbcPlanReportIndicatorRepository implements PlanReportIndicatorRepository {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Long upsertDraftIndicator(Long reportId, Long indicatorId, Integer progress, String comment) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO public.plan_report_indicator (report_id, indicator_id, progress, comment, created_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (report_id, indicator_id) DO UPDATE SET
                    progress = EXCLUDED.progress,
                    comment = EXCLUDED.comment,
                    created_at = now()
                RETURNING id
                """,
                Long.class,
                reportId,
                indicatorId,
                progress == null ? 0 : progress,
                comment
        );
    }

    @Override
    public void attachFiles(Long planReportIndicatorId, List<Long> attachmentIds, Long createdBy) {
        if (planReportIndicatorId == null) {
            return;
        }

        List<Long> normalizedAttachmentIds = attachmentIds == null
                ? List.of()
                : attachmentIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();

        if (normalizedAttachmentIds.isEmpty()) {
            jdbcTemplate.update(
                    "DELETE FROM public.plan_report_indicator_attachment WHERE plan_report_indicator_id = ?",
                    planReportIndicatorId
            );
            return;
        }

        String placeholders = normalizedAttachmentIds.stream()
                .map(ignored -> "?")
                .reduce((left, right) -> left + "," + right)
                .orElse("?");

        List<Object> deleteParams = new ArrayList<>();
        deleteParams.add(planReportIndicatorId);
        deleteParams.addAll(normalizedAttachmentIds);
        jdbcTemplate.update(
                """
                DELETE FROM public.plan_report_indicator_attachment
                WHERE plan_report_indicator_id = ?
                  AND attachment_id NOT IN (%s)
                """.formatted(placeholders),
                deleteParams.toArray()
        );

        int sortOrder = 0;
        for (Long attachmentId : normalizedAttachmentIds) {
            jdbcTemplate.update(
                    """
                    INSERT INTO public.plan_report_indicator_attachment (
                        plan_report_indicator_id,
                        attachment_id,
                        sort_order,
                        created_by,
                        created_at
                    )
                    VALUES (?, ?, ?, ?, now())
                    ON CONFLICT (plan_report_indicator_id, attachment_id) DO NOTHING
                    """,
                    planReportIndicatorId,
                    attachmentId,
                    sortOrder++,
                    createdBy == null ? 0L : createdBy
            );
        }
    }

    @Override
    public List<PlanReportIndicatorSnapshot> findByReportId(Long reportId) {
        return findByReportIds(List.of(reportId)).getOrDefault(reportId, List.of());
    }

    @Override
    public Map<Long, List<PlanReportIndicatorSnapshot>> findByReportIds(List<Long> reportIds) {
        return fetchSnapshots(reportIds);
    }

    private Map<Long, List<PlanReportIndicatorSnapshot>> fetchSnapshots(List<Long> reportIds) {
        if (reportIds == null || reportIds.isEmpty()) {
            return Map.of();
        }

        String placeholders = buildPlaceholders(reportIds.size());
        List<Object> params = new ArrayList<>(reportIds.size());
        params.addAll(reportIds);

        LinkedHashMap<Long, SnapshotHolder> snapshotsById = new LinkedHashMap<>();
        jdbcTemplate.query(
                """
                SELECT pri.id,
                       pri.report_id,
                       pri.indicator_id,
                       pri.progress,
                       pri.comment,
                       a.id AS attachment_id,
                       a.original_name,
                       a.size_bytes,
                       a.content_type,
                       COALESCE(NULLIF(a.public_url, ''), CONCAT('/api/v1/attachments/', a.id, '/download')) AS url,
                       a.uploaded_by,
                       a.uploaded_at
                FROM public.plan_report_indicator pri
                LEFT JOIN public.plan_report_indicator_attachment pria
                       ON pria.plan_report_indicator_id = pri.id
                LEFT JOIN public.attachment a
                       ON a.id = pria.attachment_id
                      AND COALESCE(a.is_deleted, false) = false
                WHERE pri.report_id IN (%s)
                ORDER BY pri.report_id ASC, pri.id ASC, pria.sort_order ASC, pria.id ASC
                """.formatted(placeholders),
                params.toArray(),
                rs -> {
                    Long indicatorId = rs.getLong("id");
                    SnapshotHolder holder = snapshotsById.get(indicatorId);
                    if (holder == null) {
                        List<PlanReportAttachmentSnapshot> attachments = new ArrayList<>();
                        holder = new SnapshotHolder(
                                rs.getLong("report_id"),
                                new PlanReportIndicatorSnapshot(
                                        rs.getLong("indicator_id"),
                                        rs.getInt("progress"),
                                        rs.getString("comment"),
                                        attachments
                                ),
                                attachments
                        );
                        snapshotsById.put(indicatorId, holder);
                    }
                    Long attachmentId = rs.getObject("attachment_id", Long.class);
                    if (attachmentId != null) {
                        OffsetDateTime uploadedAt = rs.getObject("uploaded_at", OffsetDateTime.class);
                        holder.attachments().add(new PlanReportAttachmentSnapshot(
                                attachmentId,
                                rs.getString("original_name"),
                                rs.getLong("size_bytes"),
                                rs.getString("content_type"),
                                rs.getString("url"),
                                rs.getLong("uploaded_by"),
                                uploadedAt == null ? null : uploadedAt.toString()
                        ));
                    }
                }
        );

        if (snapshotsById.isEmpty()) {
            return new HashMap<>();
        }

        Map<Long, List<PlanReportIndicatorSnapshot>> grouped = new HashMap<>();
        for (SnapshotHolder holder : snapshotsById.values()) {
            grouped.computeIfAbsent(holder.reportId(), ignored -> new ArrayList<>())
                    .add(holder.snapshot());
        }

        return grouped;
    }

    private record SnapshotHolder(Long reportId,
                                  PlanReportIndicatorSnapshot snapshot,
                                  List<PlanReportAttachmentSnapshot> attachments) {
    }

    private String buildPlaceholders(int count) {
        if (count <= 0) {
            return "";
        }
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}
