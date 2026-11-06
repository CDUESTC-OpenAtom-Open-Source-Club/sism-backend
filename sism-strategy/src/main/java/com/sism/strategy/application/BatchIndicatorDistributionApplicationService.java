package com.sism.strategy.application;

import com.sism.organization.domain.OrganizationRepository;
import com.sism.organization.domain.SysOrg;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.repository.IndicatorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coordinates one batch distribution request inside a single transaction.
 * Organization references are loaded once per batch to avoid per-item lookups.
 */
@Service
@RequiredArgsConstructor
public class BatchIndicatorDistributionApplicationService {

    private final StrategyApplicationService strategyApplicationService;
    private final OrganizationRepository organizationRepository;
    /**
     * 2026-11-05（学院指标直发改造）：批量分发要判定「归属组织 + 目标组织」是否属于旧链路
     * （职能部门 → 学院）。已有指标分支（indicatorId != null）的归属组织不在命令里，
     * 必须回查指标本身，否则判定会被绕过。
     */
    private final IndicatorRepository indicatorRepository;

    @Transactional
    public List<ItemResult> distribute(List<ItemCommand> commands) {
        Map<Long, SysOrg> organizations = loadOrganizations(commands);

        return commands.stream()
                .map(command -> distribute(command, organizations))
                .toList();
    }

    private ItemResult distribute(ItemCommand command, Map<Long, SysOrg> organizations) {
        SysOrg targetOrg = requireOrganization(
                organizations,
                command.targetOrgId(),
                command.indicatorId() != null ? "Target organization not found: " : "目标组织未找到: "
        );

        // 2026-11-05（学院指标直发改造）：「职能部门 → 学院」的分发链路已整体取消，
        // 批量分发是旧旁路之一（复活后前端无入口、无痕迹），必须在落库前拦掉。
        // 归属组织来源：新建分支取命令里的 ownerOrgId；已有指标分支回查指标自身的归属组织。
        SysOrg ownerOrg = command.indicatorId() != null
                ? requireIndicatorOwnerOrg(command.indicatorId())
                : requireOrganization(
                        organizations,
                        command.ownerOrgId(),
                        "责任组织未找到: "
                );
        strategyApplicationService.assertDispatchScopeAllowed(ownerOrg, targetOrg);

        Indicator distributed;
        if (command.indicatorId() != null) {
            distributed = strategyApplicationService.distributeIndicator(
                    command.indicatorId(),
                    targetOrg,
                    command.customDesc()
            );
        } else {
            Indicator created = strategyApplicationService.createIndicator(
                    command.indicatorDesc(),
                    ownerOrg,
                    targetOrg,
                    command.taskId(),
                    command.parentIndicatorId(),
                    command.indicatorType(),
                    command.weightPercent(),
                    command.sortOrder(),
                    command.remark(),
                    command.progress()
            );
            distributed = strategyApplicationService.distributeIndicator(
                    created.getId(),
                    targetOrg,
                    command.customDesc()
            );
        }

        return new ItemResult(command.clientRequestId(), distributed.getId());
    }

    /** 已有指标分支的归属组织：以指标自身为准，指标查不到或无归属组织一律拒绝，不静默放行。 */
    private SysOrg requireIndicatorOwnerOrg(Long indicatorId) {
        return indicatorRepository.findById(indicatorId)
                .map(Indicator::getOwnerOrg)
                .orElseThrow(() -> new IllegalArgumentException("责任组织未找到: 指标 " + indicatorId + " 无归属组织"));
    }

    private Map<Long, SysOrg> loadOrganizations(List<ItemCommand> commands) {
        Set<Long> organizationIds = new LinkedHashSet<>();
        for (ItemCommand command : commands) {
            if (command.ownerOrgId() != null) {
                organizationIds.add(command.ownerOrgId());
            }
            if (command.targetOrgId() != null) {
                organizationIds.add(command.targetOrgId());
            }
        }

        Map<Long, SysOrg> organizations = new LinkedHashMap<>();
        organizationRepository.findAllByIds(List.copyOf(organizationIds))
                .forEach(organization -> organizations.put(organization.getId(), organization));
        return organizations;
    }

    private SysOrg requireOrganization(Map<Long, SysOrg> organizations, Long id, String messagePrefix) {
        SysOrg organization = organizations.get(id);
        if (organization == null) {
            throw new IllegalArgumentException(messagePrefix + id);
        }
        return organization;
    }

    public record ItemCommand(
            String clientRequestId,
            Long indicatorId,
            String indicatorDesc,
            String indicatorType,
            Long taskId,
            Long parentIndicatorId,
            Long ownerOrgId,
            Long targetOrgId,
            BigDecimal weightPercent,
            Integer sortOrder,
            String remark,
            Integer progress,
            String customDesc
    ) {}

    public record ItemResult(String clientRequestId, Long indicatorId) {}
}
