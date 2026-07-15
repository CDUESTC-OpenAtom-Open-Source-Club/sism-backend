package com.sism.strategy.application;

import com.sism.organization.domain.OrganizationRepository;
import com.sism.organization.domain.SysOrg;
import com.sism.strategy.domain.indicator.Indicator;
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

        Indicator distributed;
        if (command.indicatorId() != null) {
            distributed = strategyApplicationService.distributeIndicator(
                    command.indicatorId(),
                    targetOrg,
                    command.customDesc()
            );
        } else {
            SysOrg ownerOrg = requireOrganization(
                    organizations,
                    command.ownerOrgId(),
                    "责任组织未找到: "
            );
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
