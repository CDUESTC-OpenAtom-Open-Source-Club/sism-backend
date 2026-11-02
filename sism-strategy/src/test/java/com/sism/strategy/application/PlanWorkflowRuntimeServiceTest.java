package com.sism.strategy.application;

import com.sism.strategy.domain.repository.PlanRepository;
import com.sism.strategy.infrastructure.StrategyOrgProperties;
import com.sism.shared.infrastructure.event.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-31（P-06 复活边界加固）单测：
 * 撤回链「原地复活」的正常路径仍工作；复活时既找不到可复活的 WAITING 步骤、
 * 也没有下一节点定义的异常路径必须抛 IllegalStateException（外层事务回滚），
 * 不再产生「零 PENDING 步骤的在审实例」。
 */
@ExtendWith(MockitoExtension.class)
class PlanWorkflowRuntimeServiceTest {

    private static final Long INSTANCE_ID = 501L;
    private static final Long FLOW_DEF_ID = 900L;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PlanRepository planRepository;

    @Mock
    private DomainEventPublisher eventPublisher;

    private PlanWorkflowRuntimeService service;

    @BeforeEach
    void setUp() {
        service = new PlanWorkflowRuntimeService(
                jdbcTemplate,
                planRepository,
                mock(StrategyOrgProperties.class),
                eventPublisher
        );
    }

    @Test
    @DisplayName("正常复活仍工作：有 WAITING 步骤时恢复 PENDING、实例回 IN_REVIEW")
    void reactivateShouldStillWorkWhenWaitingStepExists() throws Exception {
        stubWithdrawnSubmitStep();
        stubInstanceContext();

        // 第一条 WAITING 步骤（待复活的审批节点）
        ResultSet waitingRs = mock(ResultSet.class);
        when(waitingRs.getLong("id")).thenReturn(777L);
        when(waitingRs.getInt("step_no")).thenReturn(2);
        when(waitingRs.getLong("step_def_id")).thenReturn(888L);
        when(jdbcTemplate.query(contains("AND status = 'WAITING'"),
                anyRowMapper(), eq(INSTANCE_ID)))
                .thenAnswer(withMappedRow(waitingRs));

        // WAITING 步骤的定义（role_id 为空 → 审批组织回落到请求组织）
        ResultSet stepDefRs = mock(ResultSet.class);
        when(stepDefRs.getLong("id")).thenReturn(888L);
        when(stepDefRs.getString("step_name")).thenReturn("部门负责人审批");
        when(stepDefRs.getInt("step_no")).thenReturn(2);
        when(stepDefRs.getString("step_type")).thenReturn("APPROVE");
        when(jdbcTemplate.query(contains("SELECT id, step_name, step_no, step_type, role_id"),
                anyRowMapper(), eq(888L)))
                .thenAnswer(withMappedRow(stepDefRs));

        // WAITING → PENDING 恢复命中 1 行（走既有复活分支，不再触发下一节点插入）
        when(jdbcTemplate.update(contains("SET status = 'PENDING',"), any(Object[].class)))
                .thenReturn(1);
        // 撤回的「提交」步骤复活时先被置回 APPROVED（严格 stub 下需显式覆盖该调用形态）
        when(jdbcTemplate.update(contains("SET status = 'APPROVED'"), any(Object[].class)))
                .thenReturn(1);

        Long revivedInstanceId = service.reactivateWithdrawnWorkflowCurrentStep(INSTANCE_ID, "重新提交");

        assertEquals(INSTANCE_ID, revivedInstanceId);
        verify(jdbcTemplate).update(contains("SET status = 'PENDING',"), any(Object[].class));
        verify(jdbcTemplate).update(contains("SET status = 'IN_REVIEW'"), any(Object[].class));
    }

    @Test
    @DisplayName("异常路径回滚：无 WAITING 步骤且无下一节点定义时抛 IllegalStateException，实例不再被置回 IN_REVIEW")
    void reactivateShouldThrowInsteadOfCreatingZeroPendingInReviewInstance() throws Exception {
        stubWithdrawnSubmitStep();
        stubInstanceContext();
        // 无 WAITING 步骤、无下一节点定义（相关查询走 Mockito 缺省空列表）

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.reactivateWithdrawnWorkflowCurrentStep(INSTANCE_ID, "重新提交")
        );

        assertTrue(exception.getMessage().contains("找不到可复活的等待步骤"));
        // 关键断言：实例绝不能被置回 IN_REVIEW（否则产生零 PENDING 的在审实例）
        verify(jdbcTemplate, never()).update(contains("SET status = 'IN_REVIEW'"), any(Object[].class));
    }

    // ==================== stub helpers ====================

    /** 撤回的「提交」步骤（is_submit=true, step_no=1）与实例上下文（有 flow_def_id）。 */
    private void stubWithdrawnSubmitStep() throws Exception {
        ResultSet withdrawnRs = mock(ResultSet.class);
        when(withdrawnRs.getLong(1)).thenReturn(601L);    // asi.id
        when(withdrawnRs.getInt(2)).thenReturn(1);        // asi.step_no
        when(withdrawnRs.getLong(3)).thenReturn(711L);    // asi.step_def_id
        when(withdrawnRs.getBoolean(4)).thenReturn(true); // is_submit
        when(withdrawnRs.getLong(5)).thenReturn(8001L);   // ai.requester_id
        when(withdrawnRs.getLong(6)).thenReturn(39L);     // ai.requester_org_id
        when(jdbcTemplate.query(contains("asi.status = 'WITHDRAWN'"),
                anyRowMapper(), eq(INSTANCE_ID)))
                .thenAnswer(withMappedRow(withdrawnRs));
    }

    private void stubInstanceContext() throws Exception {
        ResultSet contextRs = mock(ResultSet.class);
        when(contextRs.getLong("id")).thenReturn(INSTANCE_ID);
        when(contextRs.getLong("flow_def_id")).thenReturn(FLOW_DEF_ID);
        when(contextRs.getLong("requester_id")).thenReturn(8001L);
        when(contextRs.getLong("requester_org_id")).thenReturn(39L);
        when(contextRs.getString("entity_type")).thenReturn("PLAN");
        when(contextRs.getLong("entity_id")).thenReturn(111L);
        when(jdbcTemplate.query(contains("flow_def_id, requester_id, requester_org_id"),
                anyRowMapper(), eq(INSTANCE_ID)))
                .thenAnswer(withMappedRow(contextRs));
    }

    @SuppressWarnings("unchecked")
    private static <T> RowMapper<T> anyRowMapper() {
        return any(RowMapper.class);
    }

    private static org.mockito.stubbing.Answer<List<Object>> withMappedRow(ResultSet rs) {
        return invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        };
    }
}
