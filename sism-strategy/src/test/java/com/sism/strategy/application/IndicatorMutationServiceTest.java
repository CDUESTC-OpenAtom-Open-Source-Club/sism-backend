package com.sism.strategy.application;

import com.sism.shared.domain.user.UserProvider;
import com.sism.strategy.infrastructure.StrategyOrgProperties;
import com.sism.workflow.application.BusinessWorkflowApplicationService;
import com.sism.workflow.interfaces.dto.StartWorkflowRequest;
import com.sism.workflow.interfaces.dto.WorkflowInstanceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A3 锁死主动通知：异动发起成功后，向被锁组织（indicator.target_org_id）的
 * 全部在岗用户写入 sys_user_notification 通知（标题「指标异动审批中」）。
 */
@ExtendWith(MockitoExtension.class)
class IndicatorMutationServiceTest {

    private static final Long INDICATOR_ID = 2001L;
    private static final Long OPERATOR_ID = 9L;
    private static final Long STRATEGY_ORG_ID = 35L;
    private static final Long TARGET_ORG_ID = 41003L;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private BusinessWorkflowApplicationService businessWorkflowApplicationService;

    @Mock
    private UserProvider userProvider;

    private IndicatorMutationService service;

    @BeforeEach
    void setUp() {
        service = new IndicatorMutationService(
                jdbcTemplate,
                businessWorkflowApplicationService,
                userProvider,
                new StrategyOrgProperties()
        );
    }

    @Test
    @DisplayName("initiate 不再向被锁组织写站内通知（通知只随整体审批链发送）")
    void initiate_shouldNotWriteLockNotice() {
        stubSuccessfulInitiation(List.of(TARGET_ORG_ID));

        service.initiate(INDICATOR_ID, Map.of("indicator_desc", "新描述"), OPERATOR_ID);

        verify(jdbcTemplate, never()).update(
                contains("INSERT INTO public.sys_user_notification"), any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    private void stubSuccessfulInitiation(List<Long> targetOrgIds) {
        when(userProvider.getUserOrgId(OPERATOR_ID)).thenReturn(Optional.of(STRATEGY_ORG_ID));

        Map<String, Object> indicatorRow = new HashMap<>();
        indicatorRow.put("id", INDICATOR_ID);
        indicatorRow.put("indicator_desc", "旧描述");
        indicatorRow.put("weight_percent", null);
        indicatorRow.put("remark", null);
        indicatorRow.put("status", "DISTRIBUTED");
        indicatorRow.put("mutation_status", null);
        when(jdbcTemplate.queryForMap(contains("FROM public.indicator"), eq(INDICATOR_ID)))
                .thenReturn(indicatorRow);

        when(businessWorkflowApplicationService.startWorkflow(
                any(StartWorkflowRequest.class), eq(OPERATOR_ID), eq(STRATEGY_ORG_ID)))
                .thenReturn(WorkflowInstanceResponse.builder().instanceId("501").build());

        // 2026-10-19：异动发起不再查询被锁组织用户（不写站内通知），
        // 故不再为 target_org_id / 用户列表打桩。
    }
}
