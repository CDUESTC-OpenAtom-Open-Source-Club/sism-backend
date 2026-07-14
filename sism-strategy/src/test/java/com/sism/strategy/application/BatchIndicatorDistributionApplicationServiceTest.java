package com.sism.strategy.application;

import com.sism.organization.domain.OrganizationRepository;
import com.sism.organization.domain.SysOrg;
import com.sism.strategy.domain.indicator.Indicator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchIndicatorDistributionApplicationServiceTest {

    @Test
    void shouldLoadOrganizationsOnceAndPreserveCommandOrder() {
        StrategyApplicationService strategyService = mock(StrategyApplicationService.class);
        OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
        BatchIndicatorDistributionApplicationService service =
                new BatchIndicatorDistributionApplicationService(strategyService, organizationRepository);

        SysOrg ownerOrg = organization(10L);
        SysOrg targetOrg = organization(20L);
        when(organizationRepository.findAllByIds(List.of(20L, 10L)))
                .thenReturn(List.of(targetOrg, ownerOrg));

        Indicator existingDistributed = new Indicator();
        existingDistributed.setId(101L);
        when(strategyService.distributeIndicator(100L, targetOrg, "existing"))
                .thenReturn(existingDistributed);

        Indicator created = new Indicator();
        created.setId(201L);
        when(strategyService.createIndicator(
                "new indicator",
                ownerOrg,
                targetOrg,
                300L,
                null,
                "定量",
                BigDecimal.TEN,
                1,
                "remark",
                0
        )).thenReturn(created);
        Indicator createdDistributed = new Indicator();
        createdDistributed.setId(202L);
        when(strategyService.distributeIndicator(201L, targetOrg, "new custom"))
                .thenReturn(createdDistributed);

        List<BatchIndicatorDistributionApplicationService.ItemResult> results = service.distribute(List.of(
                command("first", 100L, null, null, null, 20L, "existing"),
                command("second", null, "new indicator", "定量", 10L, 20L, "new custom")
        ));

        assertEquals(List.of("first", "second"), results.stream()
                .map(BatchIndicatorDistributionApplicationService.ItemResult::clientRequestId)
                .toList());
        assertEquals(List.of(101L, 202L), results.stream()
                .map(BatchIndicatorDistributionApplicationService.ItemResult::indicatorId)
                .toList());
        verify(organizationRepository).findAllByIds(List.of(20L, 10L));
        verify(organizationRepository, never()).findById(any());
    }

    @Test
    void shouldDeclareBatchTransactionBoundary() throws NoSuchMethodException {
        Method method = BatchIndicatorDistributionApplicationService.class
                .getMethod("distribute", List.class);

        assertNotNull(method.getAnnotation(Transactional.class));
    }

    private BatchIndicatorDistributionApplicationService.ItemCommand command(
            String requestId,
            Long indicatorId,
            String description,
            String indicatorType,
            Long ownerOrgId,
            Long targetOrgId,
            String customDescription) {
        return new BatchIndicatorDistributionApplicationService.ItemCommand(
                requestId,
                indicatorId,
                description,
                indicatorType,
                300L,
                null,
                ownerOrgId,
                targetOrgId,
                BigDecimal.TEN,
                1,
                "remark",
                0,
                customDescription
        );
    }

    private SysOrg organization(Long id) {
        SysOrg organization = new SysOrg();
        organization.setId(id);
        return organization;
    }
}
