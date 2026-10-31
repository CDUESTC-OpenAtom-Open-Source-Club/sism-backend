package com.sism.strategy.interfaces.rest;

import com.sism.common.ApiResponse;
import com.sism.common.PageResult;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.organization.domain.OrganizationRepository;
import com.sism.strategy.application.BatchIndicatorDistributionApplicationService;
import com.sism.strategy.application.DistributedPlanMutationBlockedException;
import com.sism.strategy.application.StrategyApplicationService;
import com.sism.strategy.domain.indicator.Indicator;
import com.sism.strategy.domain.indicator.IndicatorStatus;
import com.sism.task.infrastructure.persistence.JpaTaskRepositoryInternal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Constructor;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;

class IndicatorControllerTest {

    private StrategyApplicationService strategyApplicationService;
    private BatchIndicatorDistributionApplicationService batchIndicatorDistributionApplicationService;
    private OrganizationRepository organizationRepository;
    private JpaTaskRepositoryInternal jpaTaskRepository;
    private JdbcTemplate jdbcTemplate;
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private IndicatorController controller;

    @BeforeEach
    void setUp() {
        strategyApplicationService = mock(StrategyApplicationService.class);
        batchIndicatorDistributionApplicationService = mock(BatchIndicatorDistributionApplicationService.class);
        organizationRepository = mock(OrganizationRepository.class);
        jpaTaskRepository = mock(JpaTaskRepositoryInternal.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        namedParameterJdbcTemplate = mock(NamedParameterJdbcTemplate.class);

        controller = instantiateController();

        stubJdbcQueries();
    }

    /** 2026-10-26 数据隔离（R13）：战略发展部账号保留全量视图 */
    private com.sism.shared.application.dto.CurrentUser strategyUser() {
        return new com.sism.shared.application.dto.CurrentUser(
                1L, "zlb_admin", "战略部管理员", null, 35L, java.util.List.of());
    }

    /** 2026-10-26 数据隔离（R13）：部门账号按本部门过滤 */
    private com.sism.shared.application.dto.CurrentUser deptUser(Long orgId) {
        return new com.sism.shared.application.dto.CurrentUser(
                2L, "dept_user", "部门账号", null, orgId, java.util.List.of());
    }

    @Test
    @DisplayName("2026-10-26 数据隔离（R13）：部门账号的指标列表按本部门过滤，不返回全量")
    void shouldScopeIndicatorListToCallerOrgForDepartmentUsers() {
        Indicator indicator = createIndicator(2004L, 41003L);
        when(strategyApplicationService.getIndicatorsByVisibleOrg(eq(2026), eq(59L), eq(false), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(indicator), PageRequest.of(0, 20), 1));

        controller.listIndicators(0, 20, null, null, 2026, deptUser(59L));

        verify(strategyApplicationService, never()).getIndicatorsByYear(anyInt(), any(Pageable.class));
        verify(strategyApplicationService).getIndicatorsByVisibleOrg(eq(2026), eq(59L), eq(false), any(Pageable.class));
    }

    @Test
    @DisplayName("2026-10-26 数据隔离（R13）：战略发展部账号保留全量视图")
    void shouldKeepFullVisibilityForStrategyDeptUsers() {
        Indicator indicator = createIndicator(2004L, 41003L);
        when(strategyApplicationService.getIndicatorsByYear(2026, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(indicator), PageRequest.of(0, 20), 1));

        controller.listIndicators(0, 20, null, null, 2026, strategyUser());

        verify(strategyApplicationService).getIndicatorsByYear(2026, PageRequest.of(0, 20));
        verify(strategyApplicationService, never())
                .getIndicatorsByVisibleOrg(any(), any(), anyBoolean(), any(Pageable.class));
    }

    @Test
    @DisplayName("listIndicators should populate cycleId and year from task-plan-cycle chain")
    void shouldPopulateCycleIdAndYearWhenListingIndicators() {
        Indicator indicator = createIndicator(2004L, 41003L);
        when(strategyApplicationService.getIndicatorsByYear(2026, PageRequest.of(0, 1)))
                .thenReturn(new PageImpl<>(List.of(indicator), PageRequest.of(0, 1), 1));

        ResponseEntity<ApiResponse<PageResult<IndicatorController.IndicatorResponse>>> response =
                controller.listIndicators(0, 1, null, null, 2026, strategyUser());

        IndicatorController.IndicatorResponse item = response.getBody().getData().getItems().get(0);
        assertEquals(4L, item.getCycleId());
        assertEquals(2026, item.getYear());
        assertEquals(41003L, item.getTaskId());
        verify(strategyApplicationService).getIndicatorsByYear(2026, PageRequest.of(0, 1));
    }

    @Test
    @DisplayName("searchIndicators should return a paged list when keyword is absent")
    void shouldReturnPagedIndicatorsWhenSearchKeywordIsAbsent() {
        Indicator indicator = createIndicator(2006L, 41003L);
        when(strategyApplicationService.getIndicators(PageRequest.of(0, 5)))
                .thenReturn(new PageImpl<>(List.of(indicator), PageRequest.of(0, 5), 1));

        ResponseEntity<ApiResponse<List<IndicatorController.IndicatorResponse>>> response =
                controller.searchIndicators(null, 0, 5);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().getData().size());
        assertEquals(2006L, response.getBody().getData().get(0).getId());
        verify(strategyApplicationService).getIndicators(PageRequest.of(0, 5));
    }

    @Test
    @DisplayName("getIndicatorById should populate cycleId and year from task-plan-cycle chain")
    void shouldPopulateCycleIdAndYearWhenGettingById() {
        Indicator indicator = createIndicator(2005L, 41003L);
        when(strategyApplicationService.getIndicatorById(2005L)).thenReturn(indicator);

        ResponseEntity<ApiResponse<IndicatorController.IndicatorResponse>> response =
                controller.getIndicatorById(2005L);

        IndicatorController.IndicatorResponse item = response.getBody().getData();
        assertNotNull(item);
        assertEquals(4L, item.getCycleId());
        assertEquals(2026, item.getYear());
        assertEquals(41003L, item.getTaskId());
        verifyNoInteractions(jpaTaskRepository);
    }

    @Test
    @DisplayName("sendIndicatorReminder should use owner-org filtered lookup before dispatching reminder")
    void shouldRejectReminderWhenIndicatorIsNotOwnedByCurrentUser() {
        Indicator indicator = createIndicator(3005L, 41003L);
        indicator.setOwnerOrg(new com.sism.organization.domain.SysOrg());
        indicator.getOwnerOrg().setId(35L);
        indicator.getOwnerOrg().setName("责任组织");
        indicator.setTargetOrg(new com.sism.organization.domain.SysOrg());
        indicator.getTargetOrg().setId(61L);
        indicator.getTargetOrg().setName("目标组织");
        when(strategyApplicationService.getIndicatorByIdAndOwnerOrgId(3005L, 99L))
                .thenThrow(new IllegalArgumentException("Indicator not found for owner org: 3005"));

        CurrentUser currentUser = new CurrentUser(
                9L,
                "tester",
                "Tester",
                null,
                99L,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_STRATEGY_DEPT"))
        );

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.sendIndicatorReminder(3005L, new IndicatorController.ReminderRequest(), currentUser);

        assertEquals(403, response.getStatusCode().value());
        assertFalse(response.getBody().isSuccess());
        verify(strategyApplicationService).getIndicatorByIdAndOwnerOrgId(3005L, 99L);
    }

    @Test
    @DisplayName("deleteIndicator should return specific conflict message when plan is distributed")
    void shouldReturnSpecificConflictMessageWhenDeletingDistributedPlanIndicator() {
        doThrow(new DistributedPlanMutationBlockedException("当前任务已下发，不能重复导入或下发"))
                .when(strategyApplicationService)
                .deleteIndicator(2007L);

        ResponseEntity<ApiResponse<Void>> response = controller.deleteIndicator(2007L);

        assertEquals(409, response.getStatusCode().value());
        assertFalse(response.getBody().isSuccess());
        assertEquals("当前任务已下发，不能重复导入或下发", response.getBody().getMessage());
        verify(strategyApplicationService).deleteIndicator(2007L);
    }

    @Test
    @DisplayName("listIndicators should use named parameters for task and report lookups")
    void shouldUseNamedParameterQueriesForIndicatorAggregation() {
        Indicator indicator = createIndicator(2004L, 41003L);
        when(strategyApplicationService.getIndicatorsByYear(2026, PageRequest.of(0, 1)))
                .thenReturn(new PageImpl<>(List.of(indicator), PageRequest.of(0, 1), 1));

        controller.listIndicators(0, 1, null, null, 2026, strategyUser());

        verify(namedParameterJdbcTemplate).queryForList(
                contains("FROM public.sys_task"),
                any(MapSqlParameterSource.class)
        );
        verify(namedParameterJdbcTemplate).queryForList(
                contains("FROM public.plan_report_indicator"),
                any(MapSqlParameterSource.class)
        );
        verifyNoMoreInteractions(jpaTaskRepository);
    }

    private IndicatorController instantiateController() {
        try {
            Constructor<IndicatorController> constructor = IndicatorController.class.getDeclaredConstructor(
                    com.sism.strategy.application.IndicatorMutationService.class,
                    // 2026-10-30（异动驳回后重报/取消闭环）：「取消异动」终态动作走工作流应用服务
                    com.sism.workflow.application.BusinessWorkflowApplicationService.class,
                    StrategyApplicationService.class,
                    BatchIndicatorDistributionApplicationService.class,
                    OrganizationRepository.class,
                    JpaTaskRepositoryInternal.class,
                    JdbcTemplate.class,
                    NamedParameterJdbcTemplate.class,
                    Class.forName("com.sism.iam.application.service.UserNotificationService"),
                    // 2026-10-24 跨窗口数据同步：新增广播端口依赖（本测试不触发，传 null）
                    Class.forName("com.sism.shared.domain.notification.DataChangeBroadcaster")
            );
            Object userNotificationService = null;
            return constructor.newInstance(
                    new com.sism.strategy.application.IndicatorMutationService(
                            jdbcTemplate,
                            // 2026-10-20：异动新增权重闭合校验依赖（本测试不触发异动，传 null）
                            null,
                            null,
                            null,
                            null,
                            // 2026-10-21：任务改名异动留痕依赖（本测试不触发异动，传 null）
                            null,
                            // 2026-10-31：重发原地续链依赖（本测试不触发重发，传 null）
                            null,
                            null,
                            null),
                    // 取消异动依赖（本测试不触发取消，传 null）
                    null,
                    strategyApplicationService,
                    batchIndicatorDistributionApplicationService,
                    organizationRepository,
                    jpaTaskRepository,
                    jdbcTemplate,
                    namedParameterJdbcTemplate,
                    userNotificationService,
                    null
            );
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to create IndicatorController for test", ex);
        }
    }

    private void stubJdbcQueries() {
        when(namedParameterJdbcTemplate.queryForList(
                contains("FROM public.sys_task"),
                any(MapSqlParameterSource.class)
        )).thenReturn(List.of(Map.of(
                "task_id", 41003L,
                "task_name", "重点任务",
                "task_type", "QUANTITATIVE",
                "cycle_id", 4L,
                "year", 2026
        )));
        when(namedParameterJdbcTemplate.queryForList(
                contains("FROM public.plan_report_indicator"),
                any(MapSqlParameterSource.class)
        )).thenReturn(List.of());
    }

    private Indicator createIndicator(Long indicatorId, Long taskId) {
        Indicator indicator = new Indicator();
        indicator.setId(indicatorId);
        indicator.setTaskId(taskId);
        indicator.setIndicatorDesc("年度重点指标");
        indicator.setStatus(IndicatorStatus.DISTRIBUTED);
        indicator.setProgress(20);
        indicator.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        indicator.setUpdatedAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        indicator.setWeightPercent(java.math.BigDecimal.TEN);
        indicator.setSortOrder(1);
        indicator.setType("定量");
        return indicator;
    }
}
