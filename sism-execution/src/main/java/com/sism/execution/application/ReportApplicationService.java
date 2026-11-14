package com.sism.execution.application;

import com.sism.exception.BusinessConflictException;
import com.sism.exception.ConflictException;
import com.sism.exception.ResourceNotFoundException;
import com.sism.execution.domain.report.PlanReport;
import com.sism.execution.domain.report.PlanReportIndicatorRepository;
import com.sism.execution.domain.report.PlanReportIndicatorSnapshot;
import com.sism.execution.domain.report.PlanReportRepository;
import com.sism.execution.domain.report.PlanReportStatus;
import com.sism.execution.domain.report.ReportOrgType;
import com.sism.execution.domain.report.WorkflowApprovalMetadata;
import com.sism.execution.domain.report.WorkflowApprovalMetadataQuery;
import com.sism.execution.domain.report.WorkflowAuditSyncGateway;
import com.sism.execution.interfaces.dto.PlanReportQueryRequest;
import com.sism.execution.interfaces.dto.IndicatorLatestAttachmentResponse;
import com.sism.execution.interfaces.dto.ReportAttachmentResponse;
import com.sism.enums.ProgressLevel;
import com.sism.execution.interfaces.dto.UpdatePlanReportIndicatorDetailRequest;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.shared.domain.exception.TechnicalException;
import com.sism.shared.domain.model.base.DomainEvent;
import com.sism.shared.infrastructure.event.DomainEventPublisher;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.repository.IndicatorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ReportApplicationService - 计划报告应用服务
 * 处理计划报告的业务逻辑
 */
@Service("executionReportApplicationService")
@RequiredArgsConstructor
@Slf4j
public class ReportApplicationService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    /**
     * 2026-11-13 用户拍板（学院上报按归口部门分批提交）两条拦截文案：
     * 报告明细指标的父级核心指标 target_org_id 混跨多个职能部门 / 一条都反查不到。
     */
    private static final String MSG_REPORT_MIXED_FUNCTIONAL_ORG =
            "报告内指标归属多个职能部门，请按归属部门分别提交";
    private static final String MSG_REPORT_FUNCTIONAL_ORG_UNRESOLVED =
            "无法确定报告归属部门，请确认报告内指标已归属职能部门核心指标后再提交";

    /**
     * 2026-11-13：报告归口反查——报告明细指标 → 父级核心指标.target_org_id 全集（去重）。
     * 与存量回填 V104 / PlanFunctionalOrgResolver 同一父级反查口径。
     */
    private static final String SQL_REPORT_PARENT_TARGET_ORGS = """
            SELECT DISTINCT parent.target_org_id
            FROM public.plan_report_indicator pri
            JOIN public.indicator child ON child.id = pri.indicator_id
                 AND COALESCE(child.is_deleted, false) = false
            JOIN public.indicator parent ON parent.id = child.parent_indicator_id
            WHERE pri.report_id = ?
              AND parent.target_org_id IS NOT NULL
            """;

    private final PlanReportRepository planReportRepository;
    private final PlanReportIndicatorRepository planReportIndicatorRepository;
    private final IndicatorRepository indicatorRepository;
    private final DomainEventPublisher eventPublisher;
    private final WorkflowApprovalMetadataQuery workflowApprovalMetadataQuery;
    private final WorkflowAuditSyncGateway workflowAuditSyncGateway;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final java.util.List<com.sism.shared.domain.workflow.WorkflowBusinessContextPort> workflowBusinessContextPorts;

    /**
     * 创建报告（草稿）
     */
    @Transactional
    public PlanReport createReport(String reportMonth, Long reportOrgId,
                                   ReportOrgType reportOrgType, Long planId) {
        return createReport(reportMonth, reportOrgId, reportOrgType, planId, null);
    }

    /**
     * 创建报告（草稿）
     */
    @Transactional
    public PlanReport createReport(String reportMonth, Long reportOrgId,
                                   ReportOrgType reportOrgType, Long planId, Long createdBy) {
        return createReport(reportMonth, reportOrgId, reportOrgType, planId, createdBy, null);
    }

    /**
     * 创建报告（草稿，2026-11-13 起支持可选归口职能部门）。
     *
     * <p>学院上报按归口部门分批提交：学院 × 归口部门 = 一份月报。唯一性判定从
     * (plan, report_org, month) 升级为 (plan, report_org, month, functional_org_id) 四元组
     * （IS NOT DISTINCT FROM 语义：NULL 与 NULL 同组、NULL 与非 NULL 不同组）。
     *
     * <p>草稿期 functionalOrgId 由前端选中部门后传入，仅用于隔离；权威归口以提交时反查落列为准。
     * 不传（null）= 旧行为兼容，但已按部门落值的草稿不会被复用（四元组隔离）。
     */
    @Transactional
    public PlanReport createReport(String reportMonth, Long reportOrgId,
                                   ReportOrgType reportOrgType, Long planId, Long createdBy,
                                   Long functionalOrgId) {
        String normalizedMonth = normalizeReportMonth(reportMonth);
        assertEarliestUnfilledMonth(reportMonth, normalizedMonth, reportOrgId, reportOrgType, planId, functionalOrgId);
        Optional<PlanReport> existingReport = planReportRepository.findLatestByMonthlyScope(
                planId, normalizedMonth, reportOrgType, reportOrgId, functionalOrgId);
        PlanReport previousRound = null;

        if (existingReport.isPresent()) {
            PlanReport report = existingReport.get();

            if (!report.isDeleted() && !PlanReport.STATUS_SUBMITTED.equals(report.getStatus())) {
                // 2026-11-13：传入归口时对已有草稿做反查一致性校验（草稿有明细时），
                // 防止「选中部门 B 却复用了归属部门 A 的填报内容」的错位草稿。
                assertDraftDetailsMatchFunctionalOrg(report, functionalOrgId);
            }

            if (report.isDeleted()) {
                report.setDeleted(false);
                report.resetToDraft(createdBy);
                report.setReportMonth(normalizedMonth);
                report.markCreatedByIfAbsent(createdBy);
                adoptFunctionalOrgIfAbsent(report, functionalOrgId);
                report.setUpdatedAt(LocalDateTime.now());
                report.validate();
                return enrichReportMetadata(planReportRepository.save(report));
            }

            if (PlanReport.STATUS_DRAFT.equals(report.getStatus())) {
                report.markCreatedByIfAbsent(createdBy);
                report.setReportMonth(normalizedMonth);
                adoptFunctionalOrgIfAbsent(report, functionalOrgId);
                report.setUpdatedAt(LocalDateTime.now());
                report.validate();
                return enrichReportMetadata(planReportRepository.save(report));
            }

            if (PlanReport.STATUS_REJECTED.equals(report.getStatus())) {
                report.resetToDraft(createdBy);
                report.setReportMonth(normalizedMonth);
                report.markCreatedByIfAbsent(createdBy);
                adoptFunctionalOrgIfAbsent(report, functionalOrgId);
                report.setUpdatedAt(LocalDateTime.now());
                report.validate();
                return enrichReportMetadata(planReportRepository.save(report));
            }

            if (PlanReport.STATUS_SUBMITTED.equals(report.getStatus())) {
                throw new ConflictException("当前月份已有报告正在审批中，请等待审批完成或先撤回");
            }

            previousRound = report;
        }

        PlanReport report = PlanReport.createDraft(
                normalizedMonth, reportOrgId, reportOrgType, planId, createdBy, functionalOrgId);
        report.validate();
        PlanReport savedReport = enrichReportMetadata(planReportRepository.save(report));

        if (previousRound != null) {
            String previousStatus = String.valueOf(previousRound.getStatus()).trim().toUpperCase();
            if ("APPROVED".equals(previousStatus) || "REJECTED".equals(previousStatus)) {
                log.info(
                        "Created new monthly report round from historical report: previousReportId={}, previousStatus={}, newReportId={}, planId={}, reportMonth={}, reportOrgId={}, reportOrgType={}, createdBy={}, functionalOrgId={}",
                        previousRound.getId(),
                        previousStatus,
                        savedReport.getId(),
                        planId,
                        reportMonth,
                        reportOrgId,
                        reportOrgType,
                        createdBy,
                        functionalOrgId
                );
            }
        }

        return savedReport;
    }

    /** 2026-11-13：传入归口而草稿未落归口时收编（草稿隔离口径下归一），已有归口则不动。 */
    private void adoptFunctionalOrgIfAbsent(PlanReport report, Long functionalOrgId) {
        if (functionalOrgId != null && report.getFunctionalOrgId() == null) {
            report.setFunctionalOrgId(functionalOrgId);
        }
    }

    /**
     * 2026-11-13：开草稿传入归口时的反查一致性校验——草稿已有填报明细且明细能反查出唯一
     * 归口部门时，与传入归口不一致即拦（混归口同拦），保证草稿与归口绑定关系不漂移。
     * 无明细 / 反查不到 → 放行（权威落列在提交时）。
     */
    private void assertDraftDetailsMatchFunctionalOrg(PlanReport draft, Long functionalOrgId) {
        if (functionalOrgId == null) {
            return;
        }
        List<Long> resolved = resolveReportParentTargetOrgs(draft.getId());
        if (resolved.size() > 1) {
            throw new ConflictException(MSG_REPORT_MIXED_FUNCTIONAL_ORG);
        }
        if (resolved.size() == 1 && !resolved.get(0).equals(functionalOrgId)) {
            throw new ConflictException(
                    "该报告的填报内容归属职能部门 " + resolved.get(0) + "，与所选归口部门不一致，请按归属部门分别填报");
        }
    }

    /**
     * 更新报告内容（包含标题）
     */
    @Transactional
    public PlanReport updateReport(Long reportId, String title, Long indicatorId, String content, String summary, Integer progress,
                                   String selfRating, String issues, String nextPlan, Long operatorUserId) {
        PlanReport report = planReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report", reportId));

        report.markCreatedByIfAbsent(operatorUserId);
        report.updateContent(content, summary, progress, issues, nextPlan);
        if (title != null) {
            report.setTitle(title);
        }
        // 2026-10-30 修复（等级制填报保存必失败）：原先在此无条件调用 validatePendingProgress，
        // 而「进度必须大于真实进度」是百分比时代的校验——2026-10-07 裁决改为等级制后，
        // 前端提交 progress 恒为 0（占位，不再采集百分比），indicator.progress 也是 0，
        // 于是 `progress <= currentProgress` 恒成立 → 每次单条填报保存都 400
        // 「填报进度必须大于真实进度，当前真实进度为 0%」，等级制填报彻底不可用。
        // 现按等级制语义豁免：带 selfRating 即等级制填报，跳过百分比递增校验；
        // 无等级的历史百分比路径保留原校验。校验仍在落库前完成，失败不留脏数据。
        assertProgressNotRegressed(indicatorId, progress, isLevelBased(selfRating));
        PlanReport savedReport = planReportRepository.save(report);
        upsertIndicatorDetail(savedReport.getId(), indicatorId, progress, content, selfRating, List.of(), operatorUserId);
        return enrichReportMetadata(savedReport);
    }

    @Transactional
    public PlanReport updateReportBatch(Long reportId,
                                        String title,
                                        String content,
                                        String summary,
                                        Integer progress,
                                        String issues,
                                        String nextPlan,
                                        Long operatorUserId,
                                        List<UpdatePlanReportIndicatorDetailRequest> indicatorDetails) {
        PlanReport report = planReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report", reportId));

        report.markCreatedByIfAbsent(operatorUserId);
        report.updateContent(content, summary, progress, issues, nextPlan);
        if (title != null) {
            report.setTitle(title);
        }

        List<UpdatePlanReportIndicatorDetailRequest> normalizedDetails = indicatorDetails == null
                ? List.of()
                : indicatorDetails.stream()
                .filter(Objects::nonNull)
                .toList();

        // 2026-10-30 修复（等级制批量保存必失败）：整批只要有一条带自评进度等级，
        // 本次提交就是等级制填报——等级制下百分比 progress 恒为 0，
        // 对未单独带等级的条目再套「进度必须大于真实进度」判定必然 `0 <= 0` 失败，
        // 导致整次保存回滚、报「保存指标填报失败」。
        boolean levelBasedBatch = normalizedDetails.stream()
                .anyMatch(detail -> isLevelBased(detail.getSelfRating()));

        // 校验在落库前完成：等级制整批豁免；历史百分比路径逐条保留原校验，失败不留脏数据。
        if (!levelBasedBatch) {
            for (UpdatePlanReportIndicatorDetailRequest detail : normalizedDetails) {
                assertProgressNotRegressed(detail.getIndicatorId(), detail.getProgress(), false);
            }
        }

        PlanReport savedReport = planReportRepository.save(report);

        for (UpdatePlanReportIndicatorDetailRequest detail : normalizedDetails) {
            upsertIndicatorDetail(
                    savedReport.getId(),
                    detail.getIndicatorId(),
                    detail.getProgress(),
                    detail.getContent(),
                    detail.getSelfRating(),
                    detail.getAttachmentIds(),
                    operatorUserId
            );
        }

        return enrichReportMetadata(savedReport);
    }

    /**
     * 2026-10-30：判断一次填报是否属于等级制（自评进度等级）语境。
     */
    private boolean isLevelBased(String selfRating) {
        return selfRating != null && !selfRating.isBlank();
    }

    /**
     * 2026-10-30：落库前的进度校验。等级制填报跳过百分比递增校验。
     */
    private void assertProgressNotRegressed(Long indicatorId, Integer progress, boolean levelBased) {
        if (levelBased) {
            return;
        }
        validatePendingProgress(indicatorId, progress);
    }

    private void validatePendingProgress(Long indicatorId, Integer progress) {
        if (indicatorId == null || progress == null) {
            return;
        }

        Indicator indicator = indicatorRepository.findById(indicatorId)
                .orElseThrow(() -> new ResourceNotFoundException("Indicator", indicatorId));
        int currentProgress = indicator.getProgress() == null ? 0 : indicator.getProgress();
        if (progress <= currentProgress) {
            throw new IllegalArgumentException(
                    "填报进度必须大于真实进度，当前真实进度为 " + currentProgress + "%"
            );
        }
    }

    /**
     * 更新报告内容（旧方法，保持向后兼容）
     */
    @Transactional
    public PlanReport updateReport(Long reportId, String content, String summary, Integer progress,
                                   String issues, String nextPlan) {
        return updateReport(reportId, null, null, content, summary, progress, null, issues, nextPlan, null);
    }

    /**
     * 提交报告
     */
    @Transactional
    public PlanReport submitReport(Long reportId, Long userId) {
        return submitReport(reportId, userId, (String) null);
    }

    /**
     * 提交报告（携带重报说明）。
     * 2026-10-31 修复（重报说明被丢弃，图05）：驳回后重报时前端强制收集的
     * 「重报说明」经 submit 端点透传进 {@link PlanReportSubmittedEvent}，
     * 由工作流监听器写入提交步备注；comment 为空时行为与改前完全一致。
     *
     * <p>2026-11-13 用户拍板（学院上报按归口部门分批提交）：提交校验通过后、落库前，
     * 按「报告明细指标 → 父级核心指标.target_org_id」反查归口部门并落列（权威来源）：
     * <ul>
     *   <li>全部一致 → 取该值落 functional_org_id（学院 × 部门 = 一份月报，强制按部门分别提交）；</li>
     *   <li>混跨多个职能部门 → 拦「报告内指标归属多个职能部门，请按归属部门分别提交」；</li>
     *   <li>一条都反查不到 → 学院报告拦「无法确定报告归属部门」；职能部门本级报告
     *       （指标无父级）保持旧口径不落值、不拦（互斥与席位走 plan 回退）。</li>
     * </ul>
     */
    @Transactional
    public PlanReport submitReport(Long reportId, Long userId, String submitComment) {
        PlanReport report = planReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report not found with id: " + reportId));

        // 2026-10-30 用户定案（门禁前移到提交时 + 口径修正）：审核进度（评定等级）归审批人，
        // 填报人提交前必须完成的是全部指标的「自评进度」；未填自评的按指标名点名拦截
        // （BusinessConflictException 原文透传），进度说明缺口沿用原有兜底校验。
        assertIndicatorDetailsCompleteForSubmit(reportId);

        // 2026-11-13 用户拍板（未来月放填报、拦提交）：草稿允许提前录入未来月内容
        // （填报不受限），但**提交**必须等到了当月——reportMonth > 当前 yyyyMM 时拒绝。
        // 历史月/当前月照旧；「跳月」补报仍由既有的最早未填月门禁另行管理。
        assertReportMonthNotInFuture(report.getReportMonth());

        // 2026-11-13 用户拍板：归口反查落列（权威来源），详见方法注释。
        Long functionalOrgId = resolveFunctionalOrgIdForSubmit(report);
        if (functionalOrgId != null) {
            report.setFunctionalOrgId(functionalOrgId);
        }

        // 2026-10-24 用户定案（上报/异动互斥）：异动审批在途时锁定上报提交——
        // 异动改动的内容正在被审批固化，两者对同一部门二选一。
        // 2026-11-13 用户拍板：互斥收窄——只锁本归口部门；归口未落值时保持旧口径全锁。
        assertMutationApprovalNotInFlight(report.getReportOrgId(), report.getFunctionalOrgId());

        report.submit(userId, submitComment);
        report = planReportRepository.save(report);
        publishAndSaveEvents(report);
        return enrichReportMetadata(report);
    }

    /**
     * 2026-11-13：提交时归口反查。规则见 {@link #submitReport}。
     */
    private Long resolveFunctionalOrgIdForSubmit(PlanReport report) {
        List<Long> parentTargetOrgs = resolveReportParentTargetOrgs(report.getId());
        if (parentTargetOrgs.size() > 1) {
            throw new BusinessConflictException(MSG_REPORT_MIXED_FUNCTIONAL_ORG);
        }
        if (parentTargetOrgs.size() == 1) {
            return parentTargetOrgs.get(0);
        }
        // 反查不到：职能部门本级报告（指标无父级）保持旧口径；学院报告必须能反查（单层口径下
        // 学院子指标 100% 有父级），反查不到说明数据异常，拦下而不是猜一个归口。
        if (report.getReportOrgType() == ReportOrgType.COLLEGE) {
            throw new BusinessConflictException(MSG_REPORT_FUNCTIONAL_ORG_UNRESOLVED);
        }
        return report.getFunctionalOrgId();
    }

    /** 报告明细 → 父级核心指标 target_org_id 全集（去重，参数绑定）。 */
    private List<Long> resolveReportParentTargetOrgs(Long reportId) {
        if (reportId == null) {
            return List.of();
        }
        List<Long> orgIds = jdbcTemplate.queryForList(SQL_REPORT_PARENT_TARGET_ORGS, Long.class, reportId);
        return orgIds == null ? List.of() : orgIds;
    }

    /**
     * 2026-10-24 用户定案：提交上报前校验全部指标明细完整——
     * 每条明细的 selfRating（自评进度）与 description/comment（进度说明）不得为空。
     * <p>
     * 2026-10-30 用户定案（门禁前移到提交时 + 口径修正）：审核进度（评定等级）是
     * 上报审批期间由审批人评定的，与填报人无关；提交门禁只校验「自评进度」——
     * 未填自评的按指标名点名拦截（{@link BusinessConflictException}，全局处理器
     * 原文透传、不套「资源冲突」前缀），拼装口径与审批侧兜底门共用
     * {@link com.sism.shared.domain.indicator.IndicatorGateMessages}
     * （报名不报 ID、20 字截断、超 3 项列前 3 个 +「等 N 项」）。
     * 进度说明缺口的校验保持原样（IllegalStateException 兜底）。
     */
    /**
     * 2026-11-13 用户拍板（未来月放填报、拦提交）：草稿允许提前录入未来月内容，
     * 但提交必须等到了当月——reportMonth > 当前 yyyyMM 时拒绝提交。
     * 当前月/历史月照旧；「跳月」补报由既有的最早未填月门禁另行管理，不在本校验内。
     * 与前端入口收敛（一键提交按钮未来月不渲染）同口径，前端隐藏不能替代服务端防线。
     */
    private void assertReportMonthNotInFuture(String reportMonth) {
        // 兼容历史注释里的 yyyy-MM 写法：先归一成纯 6 位 yyyyMM 再比较
        String month = reportMonth == null ? "" : reportMonth.replaceAll("[^0-9]", "");
        if (!month.matches("\\d{6}")) {
            return;
        }
        java.time.LocalDate today = java.time.LocalDate.now();
        String current = String.format("%d%02d", today.getYear(), today.getMonthValue());
        if (Long.parseLong(month) > Long.parseLong(current)) {
            throw new BusinessConflictException("未来周期暂不能提交上报，请在当月提交");
        }
    }

    private void assertIndicatorDetailsCompleteForSubmit(Long reportId) {
        List<PlanReportIndicatorSnapshot> details = planReportIndicatorRepository.findByReportId(reportId);
        if (details.isEmpty()) {
            throw new IllegalStateException("本期还没有任何指标填报内容，请先逐条完成填报后再提交");
        }
        assertSelfRatingCompleteForSubmit(reportId, details);
        long missingDescription = details.stream()
                .filter(detail -> {
                    String description = detail.description() == null ? "" : detail.description().trim();
                    String comment = detail.comment() == null ? "" : detail.comment().trim();
                    return description.isEmpty() && comment.isEmpty();
                })
                .count();
        if (missingDescription > 0) {
            throw new IllegalStateException(
                    "上报被拦下：" + missingDescription + " 条指标未填写进度说明"
                            + "。请在指标填报页逐条补齐进度说明后再提交");
        }
    }

    /**
     * 2026-10-30 用户定案（门禁前移到提交时）：填报人提交上报前必须完成全部指标的
     * 自评进度评定，未填的在启动/推进审批链之前拦下（此前只拦审批通过侧，填报人可带
     * 缺口提交 → 审批人在途时填报页全锁、谁都补不了，负责人只能驳回，死循环）。
     * 文案为自评语境，与审批侧兜底门同构：
     * 「自评未完成：还有 N 项指标的自评进度未填写（『指标名A』『指标名B』…）。
     *   请先完成全部指标的自评进度评定，再提交上报。」
     */
    private void assertSelfRatingCompleteForSubmit(Long reportId,
                                                   List<PlanReportIndicatorSnapshot> details) {
        List<Long> missingSelfRating = details.stream()
                .filter(detail -> detail.selfRating() == null || detail.selfRating().isBlank())
                .map(PlanReportIndicatorSnapshot::indicatorId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (missingSelfRating.isEmpty()) {
            return;
        }
        throw new BusinessConflictException(
                com.sism.shared.domain.indicator.IndicatorGateMessages.buildNamedGateMessage(
                        "自评未完成",
                        "自评进度未填写",
                        missingSelfRating,
                        planReportIndicatorRepository.findIndicatorNamesByReport(reportId),
                        "请先完成全部指标的自评进度评定，再提交上报。"));
    }

    /**
     * 2026-10-24 用户定案（上报/异动互斥）：该报告部门存在进行中的异动审批（INDICATOR/TASK，
     * PLAN_MUTATION_STRATEGY 链）时拒绝提交上报。异动锁在该部门的指标上，按部门维度判定：
     * 异动实例的 approver_org 或指标 target_org 命中报告部门即在途。
     *
     * <p>2026-11-13 用户拍板（上报互斥只锁本部门）：学院上报按归口部门分批提交后，
     * 互斥收窄为「与该报告归口部门相关的异动在审才拦」。判定基准=异动指标的归口部门
     * （父级核心指标 target_org_id；无父级的部门本级指标取自身 target_org_id）
     * 或异动所属计划的 functional_org_id。报告归口=null（兼容旧数据）时保持现行为（全锁，保守）。
     */
    private void assertMutationApprovalNotInFlight(Long reportOrgId, Long functionalOrgId) {
        if (functionalOrgId != null) {
            assertMutationApprovalNotInFlightForFunctionalOrg(functionalOrgId);
            return;
        }
        assertMutationApprovalNotInFlight(reportOrgId);
    }

    /** 旧口径（全锁）：报告归口未落值时的保守行为，与 2026-10-24 版本逐字一致。 */
    private void assertMutationApprovalNotInFlight(Long reportOrgId) {
        if (reportOrgId == null) {
            return;
        }
        Integer inFlight = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM public.audit_instance ai
                JOIN public.audit_flow_def f ON f.id = ai.flow_def_id
                    AND f.flow_code = 'PLAN_MUTATION_STRATEGY'
                WHERE ai.status = 'IN_REVIEW'
                  AND (
                        EXISTS (SELECT 1 FROM public.audit_step_instance si
                                WHERE si.instance_id = ai.id AND si.status = 'PENDING'
                                  AND si.approver_org_id = ?)
                     OR EXISTS (SELECT 1 FROM public.indicator i
                                WHERE i.id = ai.entity_id AND ai.entity_type = 'INDICATOR'
                                  AND i.target_org_id = ?)
                     OR EXISTS (SELECT 1 FROM public.sys_task t
                                WHERE t.task_id = ai.entity_id AND ai.entity_type = 'TASK'
                                  AND t.org_id = ?)
                  )
                """,
                Integer.class, reportOrgId, reportOrgId, reportOrgId);
        if (inFlight != null && inFlight > 0) {
            throw new IllegalStateException(
                    "当前部门存在进行中的异动审批，暂不能提交上报；请等异动审批结束或撤回后再操作");
        }
    }

    /**
     * 2026-11-13 用户拍板（上报互斥只锁本部门）：只统计与本报告归口部门相关的异动——
     * ① 异动指标：父级核心指标 target_org_id 或指标自身 target_org_id 命中归口
     * （学院子指标走父级反查，职能部门本级指标走自身 target_org）；
     * ② 异动指标/任务所属计划的 functional_org_id 命中归口；
     * ③ 任务改名异动：任务所属计划的 functional_org_id 命中归口。
     * 全部参数绑定，无字符串拼接。
     */
    private void assertMutationApprovalNotInFlightForFunctionalOrg(Long functionalOrgId) {
        Integer inFlight = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM public.audit_instance ai
                JOIN public.audit_flow_def f ON f.id = ai.flow_def_id
                    AND f.flow_code = 'PLAN_MUTATION_STRATEGY'
                WHERE ai.status = 'IN_REVIEW'
                  AND (
                        EXISTS (SELECT 1 FROM public.indicator i
                                LEFT JOIN public.indicator fi ON fi.id = i.parent_indicator_id
                                WHERE i.id = ai.entity_id AND ai.entity_type = 'INDICATOR'
                                  AND (fi.target_org_id = ? OR i.target_org_id = ?))
                     OR EXISTS (SELECT 1 FROM public.indicator i2
                                JOIN public.sys_task t ON t.task_id = i2.task_id
                                JOIN public.plan p ON p.id = t.plan_id
                                WHERE i2.id = ai.entity_id AND ai.entity_type = 'INDICATOR'
                                  AND p.functional_org_id = ?)
                     OR EXISTS (SELECT 1 FROM public.sys_task t2
                                JOIN public.plan p2 ON p2.id = t2.plan_id
                                WHERE t2.task_id = ai.entity_id AND ai.entity_type = 'TASK'
                                  AND p2.functional_org_id = ?)
                  )
                """,
                Integer.class, functionalOrgId, functionalOrgId, functionalOrgId, functionalOrgId);
        if (inFlight != null && inFlight > 0) {
            throw new IllegalStateException(
                    "当前归口部门存在进行中的异动审批，暂不能提交上报；请等异动审批结束或撤回后再操作");
        }
    }

    @Transactional
    public PlanReport submitReport(Long reportId, Long userId, CurrentUser currentUser) {
        return submitReport(reportId, userId, null, currentUser);
    }

    /**
     * 2026-10-31 修复（重报说明被丢弃，图05）：submit 端点把可选「重报说明」
     * 透传进提交链路；comment 为空时与旧签名行为完全一致。
     */
    @Transactional
    public PlanReport submitReport(Long reportId, Long userId, String submitComment, CurrentUser currentUser) {
        requireAnyRole(currentUser, "ROLE_VICE_PRESIDENT", "ROLE_STRATEGY_DEPT_HEAD", "ROLE_REPORTER", "ROLE_APPROVER");
        Long actorId = resolveUserId(userId, currentUser);
        return submitReport(reportId, actorId, submitComment);
    }

    @Transactional
    public PlanReport attachAuditInstance(Long reportId, Long auditInstanceId) {
        PlanReport report = planReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report", reportId));
        report.setAuditInstanceId(auditInstanceId);
        report.setUpdatedAt(LocalDateTime.now());
        return enrichReportMetadata(planReportRepository.save(report));
    }

    /**
     * 审批通过报告
     */
    @Transactional
    public PlanReport approveReport(Long reportId, Long userId) {
        PlanReport report = planReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report", reportId));

        report.approve(userId);
        report = planReportRepository.save(report);
        syncApprovedIndicatorProgress(report.getId());
        publishAndSaveEvents(report);
        return enrichReportMetadata(report);
    }

    @Transactional
    public PlanReport approveReport(Long reportId, Long userId, CurrentUser currentUser) {
        requireAnyRole(currentUser, "ROLE_VICE_PRESIDENT", "ROLE_STRATEGY_DEPT_HEAD", "ROLE_APPROVER");
        Long actorId = resolveUserId(userId, currentUser);
        return approveReport(reportId, actorId);
    }

    /**
     * 驳回报告
     */
    @Transactional
    public PlanReport rejectReport(Long reportId, Long userId, String reason) {
        PlanReport report = planReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report", reportId));

        report.reject(userId, reason);
        report = planReportRepository.save(report);
        publishAndSaveEvents(report);
        return enrichReportMetadata(report);
    }

    @Transactional
    public PlanReport rejectReport(Long reportId, Long userId, String reason, CurrentUser currentUser) {
        requireAnyRole(currentUser, "ROLE_VICE_PRESIDENT", "ROLE_STRATEGY_DEPT_HEAD", "ROLE_APPROVER");
        Long actorId = resolveUserId(userId, currentUser);
        return rejectReport(reportId, actorId, reason);
    }

    @Transactional
    public PlanReport markWorkflowApproved(Long reportId, Long approverId) {
        PlanReport report = planReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report", reportId));
        if (!PlanReport.STATUS_APPROVED.equals(report.getStatus())) {
            report.setStatus(PlanReport.STATUS_APPROVED);
            report.setRejectionReason(null);
            report.setUpdatedAt(LocalDateTime.now());
            report = planReportRepository.save(report);
        }
        Long auditInstanceId = report.getAuditInstanceId();
        syncWorkflowAuditState(reportId, auditInstanceId,
                () -> workflowAuditSyncGateway.markApproved(auditInstanceId));
        syncApprovedIndicatorProgress(report.getId());
        // 2026-10-31 修复（P-19）：删除「审批通过后预建同月 DRAFT」。
        // V57 多轮次模型下，填报入口 ensureCurrentMonthReport/createReport 已按需创建轮次；
        // 预建的同月 DRAFT 会被跳月校验（assertEarliestUnfilledMonth）当作未结清欠账，
        // 把「当月」算成最早未填报月，阻塞下个月的上报（409「存在更早的未填报月份」）。
        return enrichReportMetadata(report);
    }

    @Transactional
    public PlanReport markWorkflowRejected(Long reportId, Long approverId, String reason) {
        PlanReport report = planReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report", reportId));
        if (PlanReport.STATUS_REJECTED.equals(report.getStatus())
                && Objects.equals(reason, report.getRejectionReason())) {
            return report;
        }
        report.setStatus(PlanReport.STATUS_REJECTED);
        report.setRejectionReason(reason);
        report.setApprovedAt(null);
        report.setUpdatedAt(LocalDateTime.now());
        report = planReportRepository.save(report);
        Long auditInstanceId = report.getAuditInstanceId();
        syncWorkflowAuditState(reportId, auditInstanceId,
                () -> workflowAuditSyncGateway.markRejected(auditInstanceId));
        return enrichReportMetadata(report);
    }

    @Transactional
    public PlanReport markWorkflowWithdrawn(Long reportId) {
        PlanReport report = planReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report", reportId));
        report.setStatus(PlanReport.STATUS_DRAFT);
        report.setSubmittedAt(null);
        report.setSubmittedBy(null);
        report.setApprovedBy(null);
        report.setApprovedAt(null);
        report.setRejectionReason(null);
        report.setAuditInstanceId(null);
        report.setUpdatedAt(LocalDateTime.now());
        return enrichReportMetadata(planReportRepository.save(report));
    }

    @Transactional
    public PlanReport markWorkflowReturnedForResubmission(Long reportId, Long auditInstanceId) {
        PlanReport report = planReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report", reportId));
        report.setStatus(PlanReport.STATUS_DRAFT);
        report.setSubmittedAt(null);
        report.setSubmittedBy(null);
        report.setApprovedBy(null);
        report.setApprovedAt(null);
        report.setRejectionReason(null);
        if (auditInstanceId != null && auditInstanceId > 0) {
            report.setAuditInstanceId(auditInstanceId);
        }
        report.setUpdatedAt(LocalDateTime.now());
        return enrichReportMetadata(planReportRepository.save(report));
    }

    /**
     * 根据ID查询报告
     */
    public Optional<PlanReport> findReportById(Long reportId) {
        return planReportRepository.findById(reportId).map(this::enrichReportMetadata);
    }

    /**
     * 根据组织ID查询报告
     */
    public List<PlanReport> findReportsByOrgId(Long reportOrgId) {
        return enrichReportList(planReportRepository.findByReportOrgId(reportOrgId));
    }

    /**
     * 根据月份查询报告
     */
    public List<PlanReport> findReportsByMonth(String reportMonth) {
        return enrichReportList(planReportRepository.findByReportMonth(normalizeReportMonth(reportMonth)));
    }

    /**
     * 根据状态查询报告
     */
    public List<PlanReport> findReportsByStatus(String status) {
        return enrichReportList(planReportRepository.findByStatus(normalizeStatusValue(status)));
    }

    public List<PlanReport> findReportsByStatusForOrg(String status, Long reportOrgId) {
        return enrichReportList(planReportRepository.findByConditions(
                null,
                reportOrgId,
                null,
                null,
                normalizeStatusValue(status),
                Pageable.unpaged()
        ).getContent());
    }

    /**
     * 根据组织和月份范围查询报告
     */
    public List<PlanReport> findReportsByOrgAndMonthRange(Long orgId, String startMonth, String endMonth) {
        return enrichReportList(planReportRepository.findByOrgIdAndMonthRange(
                orgId,
                normalizeReportMonth(startMonth),
                normalizeReportMonth(endMonth)
        ));
    }

    /**
     * 查询待审批的报告
     */
    public List<PlanReport> findPendingReports() {
        return enrichReportList(planReportRepository.findByStatus(PlanReportStatus.SUBMITTED));
    }

    public List<PlanReport> findPendingReportsForOrg(Long reportOrgId) {
        return enrichReportList(planReportRepository.findByConditions(
                null,
                reportOrgId,
                null,
                null,
                PlanReportStatus.SUBMITTED,
                Pageable.unpaged()
        ).getContent());
    }

    /**
     * 根据组织类型查询报告
     */
    public List<PlanReport> findReportsByOrgType(ReportOrgType orgType) {
        return enrichReportList(planReportRepository.findByReportOrgType(orgType));
    }

    public List<PlanReport> findReportsByOrgTypeForOrg(ReportOrgType orgType, Long reportOrgId) {
        return enrichReportList(planReportRepository.findByConditions(
                null,
                reportOrgId,
                orgType,
                null,
                (PlanReportStatus) null,
                Pageable.unpaged()
        ).getContent());
    }

    // ==================== 新增查询方法 ====================

    /**
     * 查询所有有效的报告（未删除）
     */
    public List<PlanReport> findAllActiveReports() {
        return enrichReportList(planReportRepository.findAllActive());
    }

    /**
     * 分页查询所有有效的报告
     */
    public Page<PlanReport> findAllActiveReports(int page, int size) {
        Pageable pageable = createPageable(page, size);
        return enrichReportPage(planReportRepository.findAllActive(pageable));
    }

    /**
     * 根据条件分页查询报告
     * Note: title, minProgress, maxProgress parameters are not used in query
     * as these fields are transient (not stored in database)
     */
    public Page<PlanReport> findReportsByConditions(PlanReportQueryRequest queryRequest) {
        Pageable pageable = createPageable(queryRequest.getPage(), queryRequest.getSize());

        return enrichReportPage(planReportRepository.findByConditions(
                normalizeReportMonth(queryRequest.getReportMonth()),
                queryRequest.getReportOrgId(),
                queryRequest.getReportOrgType(),
                queryRequest.getPlanId(),
                normalizeStatusValue(queryRequest.getStatus()),
                pageable
        ));
    }

    public Page<PlanReport> findReportsByConditionsForOrg(PlanReportQueryRequest queryRequest, Long reportOrgId) {
        Pageable pageable = createPageable(queryRequest.getPage(), queryRequest.getSize());

        return enrichReportPage(planReportRepository.findByConditions(
                normalizeReportMonth(queryRequest.getReportMonth()),
                reportOrgId,
                queryRequest.getReportOrgType(),
                queryRequest.getPlanId(),
                normalizeStatusValue(queryRequest.getStatus()),
                pageable
        ));
    }

    private void syncApprovedIndicatorProgress(Long reportId) {
        List<PlanReportIndicatorSnapshot> details = planReportIndicatorRepository.findByReportId(reportId);
        if (details.isEmpty()) {
            return;
        }
        List<Long> indicatorIds = details.stream()
                .map(PlanReportIndicatorSnapshot::indicatorId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, Indicator> indicatorsById = indicatorRepository.findByIds(indicatorIds).stream()
                .collect(Collectors.toMap(Indicator::getId, indicator -> indicator));

        List<Indicator> updatedIndicators = new ArrayList<>();
        for (PlanReportIndicatorSnapshot detail : details) {
            if (detail.progress() == null) {
                continue;
            }
            Indicator indicator = indicatorsById.get(detail.indicatorId());
            if (indicator == null) {
                continue;
            }
            indicator.setProgress(detail.progress());
            updatedIndicators.add(indicator);
        }

        if (!updatedIndicators.isEmpty()) {
            indicatorRepository.saveAll(updatedIndicators);
        }
    }

    private PlanReport enrichReportMetadata(PlanReport report) {
        if (report == null) {
            return null;
        }
        assignMetadata(List.of(report));
        return report;
    }

    private List<PlanReport> enrichReportList(List<PlanReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return reports;
        }
        assignMetadata(reports);
        return reports;
    }

    private Page<PlanReport> enrichReportPage(Page<PlanReport> page) {
        if (page == null || page.isEmpty()) {
            return page;
        }
        List<PlanReport> content = new ArrayList<>(page.getContent());
        assignMetadata(content);
        return new PageImpl<>(content, page.getPageable(), page.getTotalElements());
    }

    private void assignMetadata(List<PlanReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return;
        }

        List<PlanReport> nonNullReports = reports.stream()
                .filter(Objects::nonNull)
                .toList();
        List<Long> reportIds = nonNullReports.stream()
                .map(PlanReport::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<Long> auditIds = nonNullReports.stream()
                .map(PlanReport::getAuditInstanceId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();

        Map<Long, List<PlanReportIndicatorSnapshot>> indicatorDetailsByReportId = reportIds.isEmpty()
                ? Map.of()
                : planReportIndicatorRepository.findByReportIds(reportIds);
        Map<Long, WorkflowApprovalMetadata> workflowMetadata = auditIds.isEmpty()
                ? Map.of()
                : workflowApprovalMetadataQuery.findByAuditInstanceIds(auditIds);

        for (PlanReport report : nonNullReports) {
            Long reportId = report.getId();
            report.setIndicatorDetails(reportId == null
                    ? List.of()
                    : indicatorDetailsByReportId.getOrDefault(reportId, List.of()));

            Long auditInstanceId = report.getAuditInstanceId();
            WorkflowApprovalMetadata metadata = auditInstanceId == null
                    ? WorkflowApprovalMetadata.empty()
                    : workflowMetadata.getOrDefault(auditInstanceId, WorkflowApprovalMetadata.empty());
            Long submittedBy = metadata.submittedBy();
            if (submittedBy == null) {
                submittedBy = report.getSubmittedBy();
            }
            if (submittedBy == null) {
                submittedBy = report.getCreatedBy();
            }
            report.setSubmittedBy(submittedBy);

            if (metadata.approvedBy() != null) {
                report.setApprovedBy(metadata.approvedBy());
            }
            if (metadata.approvedAt() != null) {
                report.setApprovedAt(metadata.approvedAt());
            }
        }
    }

    /**
     * 分页查询组织的报告
     */
    public Page<PlanReport> findReportsByOrgId(Long reportOrgId, int page, int size) {
        Pageable pageable = createPageable(page, size);
        return enrichReportPage(planReportRepository.findByReportOrgId(reportOrgId, pageable));
    }

    /**
     * 分页查询指定状态的报告
     */
    public Page<PlanReport> findReportsByStatus(String status, int page, int size) {
        Pageable pageable = createPageable(page, size);
        return enrichReportPage(planReportRepository.findByStatus(normalizeStatusValue(status), pageable));
    }

    public Page<PlanReport> findReportsByStatusForOrg(String status, Long reportOrgId, int page, int size) {
        Pageable pageable = createPageable(page, size);
        return enrichReportPage(planReportRepository.findByConditions(
                null,
                reportOrgId,
                null,
                null,
                normalizeStatusValue(status),
                pageable
        ));
    }

    /**
     * 根据计划ID查询报告
     */
    public List<PlanReport> findReportsByPlanId(Long planId) {
        return enrichReportList(planReportRepository.findByPlanId(planId));
    }

    /**
     * 2026-11-13：按计划查询报告，可选按归口部门过滤（学院上报按归口部门分批提交）。
     * functionalOrgId=null = 旧行为（聚合全部部门）；传值=只看该部门（严格等值）。
     */
    public List<PlanReport> findReportsByPlanId(Long planId, Long functionalOrgId) {
        List<PlanReport> reports = findReportsByPlanId(planId);
        if (functionalOrgId == null) {
            return reports;
        }
        return reports.stream()
                .filter(report -> functionalOrgId.equals(report.getFunctionalOrgId()))
                .toList();
    }

    /**
     * 统计指定状态的报告数量
     */
    public long countReportsByStatus(String status) {
        return planReportRepository.countByStatus(resolveStatus(status));
    }

    public long countReportsByStatusForOrg(String status, Long reportOrgId) {
        return planReportRepository.countByStatusAndReportOrgId(resolveStatus(status), reportOrgId);
    }

    /**
     * 根据月份和组织ID查询报告
     */
    public List<PlanReport> findReportsByMonthAndOrgId(String month, Long orgId) {
        return enrichReportList(planReportRepository.findByMonthAndOrgId(normalizeReportMonth(month), orgId));
    }

    /**
     * 2026-11-13：按月份+组织查询报告，可选按归口部门过滤（不传=旧行为聚合全部）。
     */
    public List<PlanReport> findReportsByMonthAndOrgId(String month, Long orgId, Long functionalOrgId) {
        List<PlanReport> reports = findReportsByMonthAndOrgId(month, orgId);
        if (functionalOrgId == null) {
            return reports;
        }
        return reports.stream()
                .filter(report -> functionalOrgId.equals(report.getFunctionalOrgId()))
                .toList();
    }

    public List<PlanReport> findReportsByPlanIdForOrg(Long planId, Long reportOrgId) {
        return enrichReportList(planReportRepository.findByPlanId(planId));
    }

    /**
     * 2026-10-28 用户定案：导出附件必须与页面可见性同构。
     *
     * 返回每个指标「当前查看人可见」的最新一轮填报及其附件：
     * ① 填报组织本人：最新一轮（含尚未发起审批的草稿，页面实时可见）；
     * ② 上级/其他组织：最新一轮非草稿（即上一轮已上报快照）；
     * ③ 系统管理员：不按组织过滤。
     *
     * 注意：不能简单取「最新一条 plan_report_indicator」，因为最新报告可能不包含该指标
     * （例如本轮只填了部分指标），必须取「包含该指标的报告里最新的一条」。
     */
    public List<IndicatorLatestAttachmentResponse> findLatestVisibleIndicatorAttachments(
            List<Long> indicatorIds, boolean systemAdmin, Long viewerOrgId) {
        if (indicatorIds == null || indicatorIds.isEmpty()) {
            return List.of();
        }

        List<Long> normalizedIds = indicatorIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .limit(2000)
                .toList();
        if (normalizedIds.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(", ",
                java.util.Collections.nCopies(normalizedIds.size(), "?"));
        // 2026-10-27 定案沿用：草稿只对填报组织自身可见，链上审批角色不享受草稿豁免
        String visibilityCondition = systemAdmin
                ? ""
                : " AND (pr.status <> 'DRAFT' OR pr.report_org_id = ?)";

        String sql = """
                WITH visible_reports AS (
                    SELECT pr.id AS report_id,
                           pr.report_month,
                           pr.status,
                           pr.report_org_id,
                           pr.submitted_at
                    FROM public.plan_report pr
                    WHERE pr.is_deleted = false
                    %s
                ),
                ranked AS (
                    SELECT pri.indicator_id,
                           vr.report_id,
                           vr.report_month,
                           vr.status,
                           vr.submitted_at,
                           ROW_NUMBER() OVER (
                               PARTITION BY pri.indicator_id
                               ORDER BY vr.report_month ASC, vr.report_id ASC
                           ) AS round_no,
                           ROW_NUMBER() OVER (
                               PARTITION BY pri.indicator_id
                               ORDER BY vr.report_month DESC, vr.report_id DESC
                           ) AS latest_rank
                    FROM public.plan_report_indicator pri
                    JOIN visible_reports vr ON vr.report_id = pri.report_id
                    WHERE pri.indicator_id IN (%s)
                )
                SELECT r.indicator_id,
                       r.report_id,
                       r.report_month,
                       r.status,
                       r.submitted_at,
                       r.round_no,
                       a.id AS attachment_id,
                       a.original_name,
                       a.content_type,
                       a.size_bytes
                FROM ranked r
                LEFT JOIN public.plan_report_indicator pri
                       ON pri.report_id = r.report_id
                      AND pri.indicator_id = r.indicator_id
                LEFT JOIN public.plan_report_indicator_attachment pria
                       ON pria.plan_report_indicator_id = pri.id
                LEFT JOIN public.attachment a
                       ON a.id = pria.attachment_id
                      AND COALESCE(a.is_deleted, false) = false
                WHERE r.latest_rank = 1
                ORDER BY r.indicator_id ASC, pria.sort_order ASC, a.id ASC
                """.formatted(visibilityCondition, placeholders);

        List<Object> params = new ArrayList<>();
        if (!systemAdmin) {
            params.add(viewerOrgId);
        }
        params.addAll(normalizedIds);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
        Map<Long, IndicatorLatestAttachmentResponse> summaries = new java.util.LinkedHashMap<>();

        for (Map<String, Object> row : rows) {
            Long indicatorId = toLong(row.get("indicator_id"));
            if (indicatorId == null) {
                continue;
            }

            IndicatorLatestAttachmentResponse summary = summaries.computeIfAbsent(
                    indicatorId,
                    id -> IndicatorLatestAttachmentResponse.builder()
                            .indicatorId(id)
                            .reportId(toLong(row.get("report_id")))
                            .reportMonth(toText(row.get("report_month")))
                            .fillDate(resolveFillDate(
                                    row.get("submitted_at"),
                                    toText(row.get("report_month"))))
                            .round(toInteger(row.get("round_no")))
                            .status(toText(row.get("status")))
                            .attachments(new ArrayList<>())
                            .build()
            );

            Long attachmentId = toLong(row.get("attachment_id"));
            if (attachmentId != null) {
                summary.getAttachments().add(ReportAttachmentResponse.builder()
                        .id(attachmentId)
                        .fileName(toText(row.get("original_name")))
                        .fileSize(toLong(row.get("size_bytes")))
                        .fileType(toText(row.get("content_type")))
                        .url("/api/v1/attachments/" + attachmentId + "/download")
                        .build());
            }
        }

        return new ArrayList<>(summaries.values());
    }

    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer toInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String toText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 提交时间优先，草稿回退到填报期间（YYYYMM → YYYY-MM-01） */
    private static String resolveFillDate(Object submittedAt, String reportMonth) {
        if (submittedAt instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate().toString();
        }
        if (submittedAt instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toLocalDate().toString();
        }
        if (submittedAt instanceof java.time.LocalDateTime localDateTime) {
            return localDateTime.toLocalDate().toString();
        }
        if (submittedAt instanceof java.time.LocalDate localDate) {
            return localDate.toString();
        }
        if (reportMonth != null && reportMonth.length() == 6) {
            return reportMonth.substring(0, 4) + "-" + reportMonth.substring(4) + "-01";
        }
        return null;
    }

    /**
     * 发布并保存领域事件
     * 从聚合根中提取所有领域事件，保存到事件存储，并发布到事件系统
     */
    private void publishAndSaveEvents(com.sism.shared.domain.model.base.AggregateRoot<?> aggregate) {
        List<DomainEvent> events = aggregate.getDomainEvents();
        eventPublisher.publishAll(events);
        aggregate.clearEvents();
    }

    @FunctionalInterface
    private interface SyncAction {
        void run();
    }

    private void syncWorkflowAuditState(Long reportId, Long auditInstanceId, SyncAction action) {
        if (auditInstanceId == null) {
            return;
        }

        bestEffortSync("sync audit_instance state", reportId, auditInstanceId, action);
    }

    private void bestEffortSync(String operation, Long reportId, Long relatedId, SyncAction action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("[ReportApplicationService] Best-effort sync failed. operation={}, reportId={}, relatedId={}",
                    operation, reportId, relatedId, e);
            throw new TechnicalException(
                    "EXECUTION_SYNC_FAILED",
                    "Failed to " + operation + " for reportId=" + reportId + ", relatedId=" + relatedId,
                    e
            );
        }
    }

    /**
     * P1 月份规则（会议定案）：只能填报「未填报的最近一个月」，不允许跳月。
     * 即：同计划 + 同填报组织下，存在 reportMonth 严格小于本次填报月份的历史报告时，
     * 本次填报月份必须是该最小月份（把最早的欠账补上）。
     * 已批准（APPROVED）的月份视为已结清，不参与「最早未填报月」比较；
     * 仅 DRAFT/REJECTED/SUBMITTED 等未结清报告的月份仍算欠账、继续拦截跳月。
     * 月份格式统一 yyyy-MM，字符串比较即时间序比较。
     *
     * 2026-10-27 用户定案：起算月不是 1 月，而是「计划下发给该组织的那个月」。
     * 例如 2 月下发到某学院的计划，该学院从 2 月开始填报，不会被要求补 1 月。
     * 下发月取该计划的审批实例（PLAN 类型、entity_id=planId）完成时刻所在月——
     * 审批通过即下发完成，那一刻就是该组织开始填报的月份。
     */
    private void assertEarliestUnfilledMonth(String rawMonth,
                                             String normalizedMonth,
                                             Long reportOrgId,
                                             ReportOrgType reportOrgType,
                                             Long planId,
                                             Long functionalOrgId) {
        if (normalizedMonth == null || planId == null || reportOrgId == null) {
            return;
        }
        List<PlanReport> existing = planReportRepository.findByReportOrgId(reportOrgId).stream()
                .filter(report -> !Boolean.TRUE.equals(report.isDeleted()))
                .filter(report -> report.getPlanId() != null && report.getPlanId().equals(planId))
                .filter(report -> report.getReportOrgType() == reportOrgType)
                .filter(report -> report.getReportMonth() != null)
                // 2026-11-13：按部门分批提交后，跳月守卫只看本部门（+ 归口未知的旧数据，
                // 保守视为欠账）；其他部门的未结清月份不再阻塞本部门按月推进。
                .filter(report -> functionalOrgId == null
                        || report.getFunctionalOrgId() == null
                        || report.getFunctionalOrgId().equals(functionalOrgId))
                .toList();
        // 已批准（APPROVED）的月份视为已结清，不再参与「最早未填报月」比较——
        // 否则已批准月份会永远占据「最早」，月度上报整条链无法推进到下一个月。
        Set<String> settledMonths = existing.stream()
                .filter(report -> PlanReport.STATUS_APPROVED.equals(report.getStatus()))
                .map(PlanReport::getReportMonth)
                .collect(Collectors.toSet());
        String earliest = existing.stream()
                .map(PlanReport::getReportMonth)
                .filter(month -> !settledMonths.contains(month))
                .min(String::compareTo)
                .orElse(null);
        if (earliest != null && earliest.compareTo(normalizedMonth) < 0) {
            throw new ConflictException(
                    "存在更早的未填报月份 " + earliest + "，请先补报 " + earliest + " 后再填报 " + normalizedMonth);
        }

        // 2026-10-27：填报月份不得早于「计划下发月」——
        // 计划 2 月下发到某组织，该组织从 2 月开始填报，不允许被要求补 1 月。
        String dispatchMonth = resolvePlanDispatchMonth(planId);
        if (dispatchMonth != null && normalizedMonth.compareTo(dispatchMonth) < 0) {
            throw new ConflictException(
                    "填报月份不能早于计划下发月份 " + dispatchMonth + "，请从下发当月开始填报");
        }
    }

    /**
     * 2026-10-27 用户定案：查询「计划下发给该组织」的月份（yyyyMM）。
     *
     * 口径：该计划的审批实例（audit_instance.entity_type='PLAN' 且 entity_id=planId）
     * 完成（completed_at）时刻所在月。审批通过 = 下发完成，那一刻起该组织开始按月填报。
     * 数据不可得时返回 null，由调用方按「当前自然月」降级。
     */
    private String resolvePlanDispatchMonth(Long planId) {
        if (planId == null || planId <= 0) {
            return null;
        }
        try {
            List<String> months = jdbcTemplate.query(
                    """
                    SELECT DISTINCT to_char(completed_at, 'YYYYMM') AS ym
                    FROM public.audit_instance
                    WHERE entity_type = 'PLAN'
                      AND entity_id = ?
                      AND completed_at IS NOT NULL
                    """,
                    (rs, rowNum) -> rs.getString(1),
                    planId
            );
            return months.stream()
                    .filter(month -> month != null && month.matches("^\\d{6}$"))
                    .min(String::compareTo)
                    .orElse(null);
        } catch (Exception error) {
            log.warn("[ReportApplicationService] 查询计划下发月份失败，降级为当前月口径: {}", error.getMessage());
            return null;
        }
    }

    private void upsertIndicatorDetail(Long reportId,
                                       Long indicatorId,
                                       Integer progress,
                                       String comment,
                                       String selfRating,
                                       List<Long> attachmentIds,
                                       Long operatorUserId) {
        if (reportId == null || indicatorId == null) {
            return;
        }

        // 2026-10-07 裁决：进度改为等级制（自评进度等级）定性评价，不再采集百分比。
        // 2026-10-30 修复（等级制批量保存必失败）：「进度必须大于真实进度」是百分比时代的校验，
        // 等级制下 progress 恒为 0 → `0 <= 0` 恒成立 → 保存必失败。
        // 校验已前移到落库前（见 assertProgressNotRegressed），此处只负责明细落库。
        ProgressLevel rating = ProgressLevel.normalize(selfRating);
        String normalizedRating = rating == null ? null : rating.name();
        // 完成情况描述同时落 comment 与 description（口径：两列语义合并，读侧优先 description）
        Long planReportIndicatorId = planReportIndicatorRepository.upsertDraftIndicator(
                reportId,
                indicatorId,
                progress,
                comment,
                normalizedRating,
                comment
        );
        // The draft indicator and its attachments must be kept in sync as a unit.
        planReportIndicatorRepository.attachFiles(
                planReportIndicatorId,
                attachmentIds == null ? List.of() : attachmentIds,
                operatorUserId
        );
    }

    private Pageable createPageable(int page, int size) {
        return PageRequest.of(normalizePageNumber(page), normalizePageSize(size), DEFAULT_SORT);
    }

    private int normalizePageNumber(int page) {
        return Math.max(page, 1) - 1;
    }

    private int normalizePageSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private String normalizeReportMonth(String reportMonth) {
        String normalized = PlanReport.normalizeReportMonth(reportMonth);
        if (normalized == null) {
            if (reportMonth == null || reportMonth.isBlank()) {
                return null;
            }
            throw new IllegalArgumentException("Report month must be provided in yyyyMM or yyyy-MM format");
        }
        return normalized;
    }

    private String normalizeStatusValue(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return resolveStatus(status).name();
    }

    private PlanReportStatus resolveStatus(String status) {
        PlanReportStatus resolved = PlanReportStatus.from(status);
        if (resolved == null) {
            throw new IllegalArgumentException("Report status is required");
        }
        return resolved;
    }

    private Long resolveUserId(Long explicitUserId, CurrentUser currentUser) {
        if (currentUser == null || currentUser.getId() == null) {
            throw new IllegalStateException("当前用户未登录或缺少 ID");
        }
        if (explicitUserId == null) {
            return currentUser.getId();
        }
        if (!currentUser.getId().equals(explicitUserId)) {
            throw new IllegalArgumentException("请求中传入的 userId 与当前用户不一致");
        }
        return explicitUserId;
    }

    private void requireAnyRole(CurrentUser currentUser, String... roleNames) {
        if (!hasAnyRole(currentUser, roleNames)) {
            throw new AccessDeniedException("当前用户没有执行此操作的权限");
        }
    }

    private boolean hasAnyRole(CurrentUser currentUser, String... roleNames) {
        if (currentUser == null || currentUser.getAuthorities() == null) {
            return false;
        }
        for (String roleName : roleNames) {
            boolean matched = currentUser.getAuthorities().stream()
                    .anyMatch(authority -> roleName.equals(authority.getAuthority()));
            if (matched) {
                return true;
            }
        }
        return false;
    }
}
