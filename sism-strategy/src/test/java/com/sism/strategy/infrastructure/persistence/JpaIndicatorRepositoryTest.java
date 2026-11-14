package com.sism.strategy.infrastructure.persistence;

import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.indicator.IndicatorStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.domain.PageRequest;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Jpa Indicator Repository Tests")
class JpaIndicatorRepositoryTest {

    @Mock
    private JpaIndicatorRepositoryInternal jpaRepository;

    @Test
    @DisplayName("Should delegate first level query to internal repository")
    void shouldDelegateFirstLevelQuery() {
        Indicator indicator = new Indicator();
        when(jpaRepository.findFirstLevelIndicators(com.sism.organization.domain.OrgType.functional))
                .thenReturn(List.of(indicator));

        JpaIndicatorRepository repository = new JpaIndicatorRepository(jpaRepository);

        assertEquals(1, repository.findFirstLevelIndicators().size());
        verify(jpaRepository).findFirstLevelIndicators(com.sism.organization.domain.OrgType.functional);
        verify(jpaRepository, never()).findAllByIsDeletedFalse();
    }

    @Test
    @DisplayName("Should delegate keyword search to internal repository")
    void shouldDelegateKeywordSearch() {
        Indicator indicator = new Indicator();
        when(jpaRepository.findByIndicatorDescContainingIgnoreCaseAndIsDeletedFalse("关键字"))
                .thenReturn(List.of(indicator));

        JpaIndicatorRepository repository = new JpaIndicatorRepository(jpaRepository);

        assertEquals(1, repository.findByKeyword("关键字").size());
        verify(jpaRepository).findByIndicatorDescContainingIgnoreCaseAndIsDeletedFalse("关键字");
        verify(jpaRepository, never()).findAllByIsDeletedFalse();
    }

    @Test
    @DisplayName("Should normalize status string before delegating status query")
    void shouldNormalizeStatusBeforeDelegating() {
        Indicator indicator = new Indicator();
        when(jpaRepository.findByStatusAndIsDeletedFalse(IndicatorStatus.DISTRIBUTED))
                .thenReturn(List.of(indicator));

        JpaIndicatorRepository repository = new JpaIndicatorRepository(jpaRepository);

        assertEquals(1, repository.findByStatus("distributed").size());
        verify(jpaRepository).findByStatusAndIsDeletedFalse(IndicatorStatus.DISTRIBUTED);
    }

    @Test
    @DisplayName("Should return empty page when status string is invalid")
    void shouldReturnEmptyPageWhenStatusInvalid() {
        JpaIndicatorRepository repository = new JpaIndicatorRepository(jpaRepository);

        assertTrue(repository.findByStatus("bad-status", PageRequest.of(0, 10)).isEmpty());
        verify(jpaRepository, never()).findByStatusAndIsDeletedFalse(any(IndicatorStatus.class), any());
    }

    // ===== 2026-11-10（用户拍板：草稿指标不能做父级锚点）=====
    //
    // 锚点集合的状态过滤落在仓储层原生 SQL（i.status = 'DISTRIBUTED'，已下发字面量，
    // 与 PlanApplicationService#syncIndicatorStatusWithPlan 的下发流转一致）；
    // 委托层负责参数守卫与透传。同圈定但不过滤状态的 findCoreIndicatorsInCycle
    // 供学院导入行级提示区分「系统无此核心指标」与「存在但尚未下发」。

    @Test
    @DisplayName("Should delegate core dispatch anchors query to internal repository")
    void shouldDelegateCoreDispatchAnchors() {
        Indicator distributedIndicator = new Indicator();
        when(jpaRepository.findCoreDispatchAnchors(35L, 2026L))
                .thenReturn(List.of(distributedIndicator));

        JpaIndicatorRepository repository = new JpaIndicatorRepository(jpaRepository);

        assertEquals(1, repository.findCoreDispatchAnchors(35L, 2026L).size());
        verify(jpaRepository).findCoreDispatchAnchors(35L, 2026L);
    }

    @Test
    @DisplayName("Should return empty core dispatch anchors when owner org or cycle is null")
    void shouldReturnEmptyCoreDispatchAnchorsWhenArgsNull() {
        JpaIndicatorRepository repository = new JpaIndicatorRepository(jpaRepository);

        assertTrue(repository.findCoreDispatchAnchors(null, 2026L).isEmpty());
        assertTrue(repository.findCoreDispatchAnchors(35L, null).isEmpty());
        verify(jpaRepository, never()).findCoreDispatchAnchors(any(), any());
    }

    @Test
    @DisplayName("Should delegate core indicators in cycle query (no status filter)")
    void shouldDelegateCoreIndicatorsInCycle() {
        Indicator draftIndicator = new Indicator();
        when(jpaRepository.findCoreIndicatorsInCycle(35L, 2026L))
                .thenReturn(List.of(draftIndicator));

        JpaIndicatorRepository repository = new JpaIndicatorRepository(jpaRepository);

        assertEquals(1, repository.findCoreIndicatorsInCycle(35L, 2026L).size());
        verify(jpaRepository).findCoreIndicatorsInCycle(35L, 2026L);
    }

    @Test
    @DisplayName("Should return empty core indicators when owner org or cycle is null")
    void shouldReturnEmptyCoreIndicatorsWhenArgsNull() {
        JpaIndicatorRepository repository = new JpaIndicatorRepository(jpaRepository);

        assertTrue(repository.findCoreIndicatorsInCycle(null, 2026L).isEmpty());
        assertTrue(repository.findCoreIndicatorsInCycle(35L, null).isEmpty());
        verify(jpaRepository, never()).findCoreIndicatorsInCycle(any(), any());
    }
}
