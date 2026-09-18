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
    @DisplayName("initiate 成功后应向被锁组织的在岗用户逐人写入锁死通知")
    void initiate_shouldWriteLockNoticeToTargetOrgActiveUsers() {
        stubSuccessfulInitiation(List.of(TARGET_ORG_ID));

        service.initiate(INDICATOR_ID, Map.of("indicator_desc", "新描述"), OPERATOR_ID);

        // 两个在岗用户各一条，标题/文案/归属字段为会议定案口径
        verify(jdbcTemplate).update(
                contains("INSERT INTO public.sys_user_notification"),
                eq(501L),
                eq(OPERATOR_ID),
                eq(STRATEGY_ORG_ID),
                eq("INDICATOR_MUTATION"),
                eq("指标异动审批中"),
                eq("上级正在对该部门的指标进行异动审批，期间暂不能填报或提交，请稍后再试"),
                eq("/indicators/" + INDICATOR_ID),
                eq(INDICATOR_ID),
                any());
        verify(jdbcTemplate).update(
                contains("INSERT INTO public.sys_user_notification"),
                eq(502L),
                eq(OPERATOR_ID),
                eq(STRATEGY_ORG_ID),
                eq("INDICATOR_MUTATION"),
                eq("指标异动审批中"),
                eq("上级正在对该部门的指标进行异动审批，期间暂不能填报或提交，请稍后再试"),
                eq("/indicators/" + INDICATOR_ID),
                eq(INDICATOR_ID),
                any());
    }

    @Test
    @DisplayName("指标无 target_org_id 时不写锁死通知")
    void initiate_shouldSkipLockNoticeWhenIndicatorHasNoTargetOrg() {
        stubSuccessfulInitiation(List.of());

        service.initiate(INDICATOR_ID, Map.of("indicator_desc", "新描述"), OPERATOR_ID);

        verify(jdbcTemplate, never()).update(
                contains("sys_user_notification"), any(Object.class));
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

        when(jdbcTemplate.queryForList(contains("SELECT target_org_id"), eq(Long.class), eq(INDICATOR_ID)))
                .thenReturn(targetOrgIds);

        if (!targetOrgIds.isEmpty()) {
            when(jdbcTemplate.query(
                    contains("FROM public.sys_user u"),
                    any(RowMapper.class),
                    eq(TARGET_ORG_ID)))
                    .thenReturn(List.of(501L, 502L));
        }
    }
}
