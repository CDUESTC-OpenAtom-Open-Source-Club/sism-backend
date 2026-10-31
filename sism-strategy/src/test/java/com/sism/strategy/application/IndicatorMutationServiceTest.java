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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A3 锁死主动通知：异动发起成功后，向被锁组织（indicator.target_org_id）的
 * 全部在岗用户写入 sys_user_notification 通知（标题「指标异动审批中」）。
 */
@ExtendWith(MockitoExtension.class)
class IndicatorMutationServiceTest {

    private static final Long INDICATOR_ID = 2001L;
    private static final Long TASK_ID = 3001L;
    private static final Long OPERATOR_ID = 9L;
    private static final Long STRATEGY_ORG_ID = 35L;
    private static final Long TARGET_ORG_ID = 41003L;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private BasicTaskWeightValidationService basicTaskWeightValidationService;

    @Mock
    private BusinessWorkflowApplicationService businessWorkflowApplicationService;

    @Mock
    private UserProvider userProvider;

    @Mock
    private com.sism.task.application.TaskMutationHistoryService taskMutationHistoryService;

    /** 2026-10-31（重发原地续链）：退回异动链原地复活依赖的 workflow 侧组件。 */
    @Mock
    private com.sism.workflow.domain.runtime.AuditInstanceRepository auditInstanceRepository;

    @Mock
    private com.sism.workflow.application.WorkflowBusinessStatusSyncService workflowBusinessStatusSyncService;

    @Mock
    private com.sism.workflow.application.support.WorkflowEventDispatcher workflowEventDispatcher;

    /** 2026-10-31 缺陷修复（重发后审批人无待办通知）：与首发起同口径的通知链路。 */
    @Mock
    private com.sism.iam.application.service.UserNotificationService userNotificationService;

    @Mock
    private com.sism.workflow.application.support.ApproverResolver approverResolver;

    @Mock
    private com.sism.workflow.application.definition.WorkflowDefinitionQueryService workflowDefinitionQueryService;

    @Mock
    private com.sism.workflow.application.query.WorkflowReadModelService workflowReadModelService;

    private IndicatorMutationService service;

    @BeforeEach
    void setUp() {
        service = new IndicatorMutationService(
                jdbcTemplate,
                // 2026-10-20：异动新增权重闭合校验依赖
                basicTaskWeightValidationService,
                businessWorkflowApplicationService,
                userProvider,
                new StrategyOrgProperties(),
                // 2026-10-21：任务改名异动留痕
                taskMutationHistoryService,
                // 2026-10-31：重发原地续链（save → sync → publish）
                auditInstanceRepository,
                workflowBusinessStatusSyncService,
                workflowEventDispatcher,
                // 2026-10-31 缺陷修复：重发后续链通知下一环节候选审批人
                userNotificationService,
                approverResolver,
                workflowDefinitionQueryService,
                workflowReadModelService
        );
    }

    @Test
    @DisplayName("2026-10-30 定案：职能部门下发审批在途时，战略部不能异动该部门指标")
    void initiateBatch_shouldRejectWhenFunctionalDeptDispatchApprovalInFlight() {
        when(userProvider.getUserOrgId(OPERATOR_ID)).thenReturn(Optional.of(STRATEGY_ORG_ID));
        when(jdbcTemplate.queryForList(
                contains("FROM public.sys_user_role"), eq(String.class), eq(OPERATOR_ID)))
                .thenReturn(List.of("ROLE_REPORTER"));
        Map<String, Object> indicatorRow = new HashMap<>();
        indicatorRow.put("id", INDICATOR_ID);
        indicatorRow.put("indicator_desc", "旧描述");
        indicatorRow.put("weight_percent", null);
        indicatorRow.put("remark", null);
        indicatorRow.put("status", "DISTRIBUTED");
        indicatorRow.put("mutation_status", null);
        indicatorRow.put("plan_status", "DISTRIBUTED");
        when(jdbcTemplate.queryForMap(contains("FROM public.indicator"), eq(INDICATOR_ID)))
                .thenReturn(indicatorRow);

        // 该指标属于职能部门自己的指标：target_org_id = 职能部门，所属计划 cycle=2026
        when(jdbcTemplate.queryForList(
                contains("SELECT i.target_org_id, p.cycle_id"), eq(INDICATOR_ID)))
                .thenReturn(List.of(Map.of("target_org_id", TARGET_ORG_ID, "cycle_id", 2026L)));
        // 该职能部门存在 IN_REVIEW 的 PLAN_DISPATCH_FUNCDEPT 下发审批
        when(jdbcTemplate.queryForObject(
                contains("PLAN_DISPATCH_FUNCDEPT"), eq(Integer.class),
                eq(TARGET_ORG_ID), eq(2026L)))
                .thenReturn(1);

        // 2026-10-30：单条异动入口已整体移除（batch_id 为 null → 快照落库 JOIN 不上 →
        // 变更值静默丢失），异动统一走批量提交；本守卫在批量路径上同口径生效。
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                service.initiateBatch(
                        List.of(Map.of("id", INDICATOR_ID, "indicator_desc", "新描述")),
                        OPERATOR_ID));

        assertTrue(error.getMessage().contains("下发审批正在进行中"));
        // 不允许出现「下发在审 + 异动在审」并存：不得启动审批链、不得打异动标记
        verifyNoInteractions(businessWorkflowApplicationService);
        verify(jdbcTemplate, never()).update(
                contains("SET mutation_status = ?"), any(Object[].class));
    }

    @Test
    @DisplayName("批量异动不再向被锁组织写站内通知（通知只随整体审批链发送）")
    void initiateBatch_shouldNotWriteLockNotice() {
        stubSuccessfulInitiation(List.of(TARGET_ORG_ID));

        service.initiateBatch(
                List.of(Map.of("id", INDICATOR_ID, "indicator_desc", "新描述")),
                OPERATOR_ID);

        verify(jdbcTemplate, never()).update(
                contains("INSERT INTO public.sys_user_notification"), any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    private void stubSuccessfulInitiation(List<Long> targetOrgIds) {
        when(userProvider.getUserOrgId(OPERATOR_ID)).thenReturn(Optional.of(STRATEGY_ORG_ID));

        // 2026-10-21：发起权收紧为「必须是填报人」，角色查询需返回 ROLE_REPORTER
        when(jdbcTemplate.queryForList(
                contains("FROM public.sys_user_role"), eq(String.class), eq(OPERATOR_ID)))
                .thenReturn(List.of("ROLE_REPORTER"));

        Map<String, Object> indicatorRow = new HashMap<>();
        indicatorRow.put("id", INDICATOR_ID);
        indicatorRow.put("indicator_desc", "旧描述");
        indicatorRow.put("weight_percent", null);
        indicatorRow.put("remark", null);
        indicatorRow.put("status", "DISTRIBUTED");
        indicatorRow.put("mutation_status", null);
        // 2026-10-22：异动资格改按所属计划状态判定（不再是单条指标状态）
        indicatorRow.put("plan_status", "DISTRIBUTED");
        when(jdbcTemplate.queryForMap(contains("FROM public.indicator"), eq(INDICATOR_ID)))
                .thenReturn(indicatorRow);

        when(businessWorkflowApplicationService.startWorkflow(
                any(StartWorkflowRequest.class), eq(OPERATOR_ID), eq(STRATEGY_ORG_ID)))
                .thenReturn(WorkflowInstanceResponse.builder().instanceId("501").build());

        // 2026-10-19：异动发起不再查询被锁组织用户（不写站内通知），
        // 故不再为 target_org_id / 用户列表打桩。
    }

    @Test
    @DisplayName("2026-10-27 回归：上报审批在途时任务改名异动必须被拒（与指标路径同口径）")
    void initiateBatch_shouldRejectTaskMutationWhenReportApprovalInFlight() {
        when(userProvider.getUserOrgId(OPERATOR_ID)).thenReturn(Optional.of(STRATEGY_ORG_ID));
        when(jdbcTemplate.queryForList(
                contains("FROM public.sys_user_role"), eq(String.class), eq(OPERATOR_ID)))
                .thenReturn(List.of("ROLE_REPORTER"));

        Map<String, Object> taskRow = new HashMap<>();
        taskRow.put("task_id", TASK_ID);
        taskRow.put("name", "旧任务名");
        taskRow.put("plan_id", 4040L);
        taskRow.put("mutation_status", null);
        taskRow.put("plan_status", "DISTRIBUTED");
        when(jdbcTemplate.queryForMap(contains("FROM public.sys_task t"), eq(TASK_ID)))
                .thenReturn(taskRow);

        // 该计划存在 IN_REVIEW 的上报审批实例（PLAN_REPORT 实体）
        when(jdbcTemplate.queryForObject(
                contains("PLAN_APPROVAL_FUNCDEPT"), eq(Integer.class), eq(4040L)))
                .thenReturn(1);

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                service.initiateBatch(
                        List.of(Map.of("id", TASK_ID, "entity_type", "TASK", "name", "新任务名")),
                        OPERATOR_ID));

        assertTrue(error.getMessage().contains("上报正在审批中"));
        // 不允许出现「上报在审 + 异动在审」并存：不得启动审批链、不得打异动标记
        verifyNoInteractions(businessWorkflowApplicationService);
        verify(jdbcTemplate, never()).update(
                contains("SET mutation_status = ?"), any(Object[].class));
    }

    @Test
    @DisplayName("批量异动中的任务改名条目：写任务快照、打异动标记，并以 TASK 实体启动同一审批链")
    void initiateBatch_shouldStartTaskMutationWithTaskEntityType() {
        when(userProvider.getUserOrgId(OPERATOR_ID)).thenReturn(Optional.of(STRATEGY_ORG_ID));
        // canInitiate 的角色查询：非分管校领导即可发起
        when(jdbcTemplate.queryForList(
                contains("FROM public.sys_user_role"), eq(String.class), eq(OPERATOR_ID)))
                .thenReturn(List.of("ROLE_REPORTER"));

        Map<String, Object> taskRow = new HashMap<>();
        taskRow.put("task_id", TASK_ID);
        taskRow.put("name", "旧任务名");
        taskRow.put("plan_id", 4040L);
        taskRow.put("mutation_status", null);
        taskRow.put("plan_status", "DISTRIBUTED");
        when(jdbcTemplate.queryForMap(contains("FROM public.sys_task t"), eq(TASK_ID)))
                .thenReturn(taskRow);

        when(businessWorkflowApplicationService.startWorkflow(
                any(StartWorkflowRequest.class), eq(OPERATOR_ID), eq(STRATEGY_ORG_ID)))
                .thenReturn(WorkflowInstanceResponse.builder().instanceId("601").build());

        List<String> instanceIds = service.initiateBatch(
                List.of(Map.of("id", TASK_ID, "entity_type", "TASK", "name", "新任务名")),
                OPERATOR_ID);

        assertEquals(List.of("601"), instanceIds);
        // 任务改名必须留痕（「已更改 N 次」数据源），并带上本次异动批次号
        verify(taskMutationHistoryService)
                .recordRename(eq(TASK_ID), eq("旧任务名"), eq("新任务名"), eq(OPERATOR_ID), any(String.class));
        // 2026-10-23：发起只打异动标记，新任务名要等审批通过才落库
        verify(jdbcTemplate).update(
                contains("UPDATE public.sys_task"), eq("IN_MUTATION"), eq(TASK_ID));
        verify(jdbcTemplate, never()).update(
                contains("SET name ="), any(Object[].class));

        // 审批实例的实体类型必须是 TASK（前端据此取任务变更历史）
        ArgumentCaptor<StartWorkflowRequest> captor =
                ArgumentCaptor.forClass(StartWorkflowRequest.class);
        verify(businessWorkflowApplicationService)
                .startWorkflow(captor.capture(), eq(OPERATOR_ID), eq(STRATEGY_ORG_ID));
        StartWorkflowRequest request = captor.getValue();
        assertEquals("PLAN_MUTATION_STRATEGY", request.getWorkflowCode());
        assertEquals("TASK", request.getBusinessEntityType());
        assertEquals(TASK_ID, request.getBusinessEntityId());
        assertEquals(TASK_ID, request.getVariables().get("taskId"));
        // 任务异动不涉及权重，不应触发权重闭合校验
        verifyNoInteractions(basicTaskWeightValidationService);
    }

    @Test
    @DisplayName("批量异动中的删除条目：只写 before 快照并打标记、不立即软删除；删除批次同样做闭合校验")
    void initiateBatch_shouldMarkIndicatorForDeletionWithoutArchiving() {
        when(userProvider.getUserOrgId(OPERATOR_ID)).thenReturn(Optional.of(STRATEGY_ORG_ID));
        when(jdbcTemplate.queryForList(
                contains("FROM public.sys_user_role"), eq(String.class), eq(OPERATOR_ID)))
                .thenReturn(List.of("ROLE_REPORTER"));

        Map<String, Object> row = new HashMap<>();
        row.put("id", INDICATOR_ID);
        row.put("indicator_desc", "待删除指标");
        row.put("weight_percent", new java.math.BigDecimal("40.00"));
        row.put("remark", "备注");
        row.put("status", "DISTRIBUTED");
        row.put("mutation_status", null);
        // 2026-10-22：异动资格改按所属计划状态判定
        row.put("plan_status", "DISTRIBUTED");
        when(jdbcTemplate.queryForMap(contains("FROM public.indicator"), eq(INDICATOR_ID)))
                .thenReturn(row);

        when(businessWorkflowApplicationService.startWorkflow(
                any(StartWorkflowRequest.class), eq(OPERATOR_ID), eq(STRATEGY_ORG_ID)))
                .thenReturn(WorkflowInstanceResponse.builder().instanceId("701").build());

        // 2026-10-22 修复「只删不补」缺口后，删除条目也要走（计划、目标部门）反查
        when(jdbcTemplate.query(
                contains("SELECT t.plan_id"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Long>>any(),
                any()))
                .thenReturn(4040L);
        when(jdbcTemplate.query(
                contains("SELECT target_org_id"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Long>>any(),
                any()))
                .thenReturn(TARGET_ORG_ID);

        List<String> instanceIds = service.initiateBatch(
                List.of(Map.of("id", INDICATOR_ID, "delete", true)),
                OPERATOR_ID);

        assertEquals(List.of("701"), instanceIds);
        // 提交阶段只打异动标记，绝不能直接归档（审批通过后才删）
        verify(jdbcTemplate).update(
                contains("SET mutation_status = ?"), eq("IN_MUTATION"), eq(INDICATOR_ID));
        verify(jdbcTemplate, never()).update(
                contains("SET is_deleted = true"), any(), any());

        // 删除条目同样作为权重校验入口：合计按「剔除待删指标」口径校验（=100 通过）。
        // 校验通过后照常启动审批链；不满足时由下一个用例覆盖报错提示。
        ArgumentCaptor<java.util.Set<Long>> excludedCaptor =
                ArgumentCaptor.forClass(java.util.Set.class);
        verify(basicTaskWeightValidationService).validatePlanBasicWeight(
                eq(4040L), eq(TARGET_ORG_ID), excludedCaptor.capture(), any());
        assertTrue(excludedCaptor.getValue().contains(INDICATOR_ID));

        ArgumentCaptor<StartWorkflowRequest> captor =
                ArgumentCaptor.forClass(StartWorkflowRequest.class);
        verify(businessWorkflowApplicationService)
                .startWorkflow(captor.capture(), eq(OPERATOR_ID), eq(STRATEGY_ORG_ID));
        StartWorkflowRequest request = captor.getValue();
        assertEquals("PLAN_MUTATION_STRATEGY", request.getWorkflowCode());
        assertEquals("INDICATOR", request.getBusinessEntityType());
        assertEquals("DELETE", request.getVariables().get("mutationType"));
    }

    @Test
    @DisplayName("只删不补的批次被拒绝：明确提示同批次调整其他指标权重补齐（修复删除后卡死后续异动）")
    void initiateBatch_shouldRejectDeleteOnlyBatchWhenWeightCannotStayClosed() {
        when(userProvider.getUserOrgId(OPERATOR_ID)).thenReturn(Optional.of(STRATEGY_ORG_ID));
        when(jdbcTemplate.queryForList(
                contains("FROM public.sys_user_role"), eq(String.class), eq(OPERATOR_ID)))
                .thenReturn(List.of("ROLE_REPORTER"));

        Map<String, Object> row = new HashMap<>();
        row.put("id", INDICATOR_ID);
        row.put("indicator_desc", "待删除指标");
        row.put("weight_percent", new java.math.BigDecimal("40.00"));
        row.put("remark", "备注");
        row.put("status", "DISTRIBUTED");
        row.put("mutation_status", null);
        row.put("plan_status", "DISTRIBUTED");
        when(jdbcTemplate.queryForMap(contains("FROM public.indicator"), eq(INDICATOR_ID)))
                .thenReturn(row);

        when(jdbcTemplate.query(
                contains("SELECT t.plan_id"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Long>>any(),
                any()))
                .thenReturn(4040L);
        when(jdbcTemplate.query(
                contains("SELECT target_org_id"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Long>>any(),
                any()))
                .thenReturn(TARGET_ORG_ID);

        // 模拟「剔除待删指标后合计=60」的闭合校验失败
        doThrow(new IllegalStateException("基础性任务指标权重合计必须为100，当前为60"))
                .when(basicTaskWeightValidationService)
                .validatePlanBasicWeight(eq(4040L), eq(TARGET_ORG_ID), any(), any());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.initiateBatch(
                        List.of(Map.of("id", INDICATOR_ID, "delete", true)),
                        OPERATOR_ID));

        assertTrue(error.getMessage().contains("请在同批次内调整其他指标权重补齐到100"));
        // 校验未通过：不得启动审批链
        verify(businessWorkflowApplicationService, never())
                .startWorkflow(any(StartWorkflowRequest.class), eq(OPERATOR_ID), eq(STRATEGY_ORG_ID));
    }

    @Test
    @DisplayName("发起权只给填报人：负责人和校领导都不能发起异动")
    void canInitiate_shouldOnlyAllowReporter() {        when(userProvider.getUserOrgId(OPERATOR_ID)).thenReturn(Optional.of(STRATEGY_ORG_ID));

        // 填报人：可以发起
        when(jdbcTemplate.queryForList(
                contains("FROM public.sys_user_role"), eq(String.class), eq(OPERATOR_ID)))
                .thenReturn(List.of("ROLE_REPORTER"));
        assertTrue(service.canInitiate(OPERATOR_ID));

        // 战略发展部负责人：不能发起（否则等于自己发起自己审批）
        when(jdbcTemplate.queryForList(
                contains("FROM public.sys_user_role"), eq(String.class), eq(OPERATOR_ID)))
                .thenReturn(List.of("ROLE_STRATEGY_DEPT_HEAD"));
        assertFalse(service.canInitiate(OPERATOR_ID));

        // 分管校领导：不能发起（只是终审节点）
        when(jdbcTemplate.queryForList(
                contains("FROM public.sys_user_role"), eq(String.class), eq(OPERATOR_ID)))
                .thenReturn(List.of("ROLE_VICE_PRESIDENT"));
        assertFalse(service.canInitiate(OPERATOR_ID));

        // 非战略部组织：不能发起（组织校验先于角色校验，此处无需角色桩）
        when(userProvider.getUserOrgId(OPERATOR_ID)).thenReturn(Optional.of(99L));
        assertFalse(service.canInitiate(OPERATOR_ID));
    }

    @Test
    @DisplayName("异动资格按计划判定：计划已下发时，指标本身是 DRAFT 也能发起（新增指标即此形态）")
    void initiateBatch_shouldAllowDraftIndicatorWhenPlanIsDistributed() {
        stubReporter();
        // 新增行落库的指标：status=DRAFT，但所属计划已下发
        stubIndicatorRow("DRAFT", "DISTRIBUTED");
        stubWorkflowStart("801");

        List<String> instanceIds = service.initiateBatch(
                List.of(Map.of("id", INDICATOR_ID, "create", true)),
                OPERATOR_ID);

        assertEquals(List.of("801"), instanceIds);
    }

    @Test
    @DisplayName("2026-10-30 缺陷回归：单条新增异动的 changed_fields 必须含三个字段键"
            + "（权重/下发备注列的「(新增)」角标按各自字段键匹配审计记录）")
    void initiateForCreatedIndicator_shouldRecordAllThreeFieldsInChangedFields() {
        stubReporter();
        Map<String, Object> row = indicatorRow("DRAFT", "DISTRIBUTED");
        row.put("indicator_desc", "新增指标A");
        row.put("weight_percent", new java.math.BigDecimal("10"));
        row.put("remark", "战略新增备注");
        when(jdbcTemplate.queryForMap(contains("FROM public.indicator"), eq(INDICATOR_ID)))
                .thenReturn(row);
        stubWorkflowStart("1101");

        String instanceId = service.initiateForCreatedIndicator(INDICATOR_ID, OPERATOR_ID);

        assertEquals("1101", instanceId);
        Map<String, Map<String, Object>> changedFields = capturedCreateChangedFields();
        // 三个键齐备：核心指标 / 权重 / 下发备注
        assertEquals(Set.of("indicator_desc", "weight_percent", "remark"), changedFields.keySet());
        // before 均为「(新增)」，after 与指标当前值一致（数值权重沿用 String.valueOf）
        changedFields.forEach((field, diff) -> assertEquals(
                "(新增)", String.valueOf(diff.get("before")), field + " 的 before 应为 (新增)"));
        assertEquals("新增指标A", changedFields.get("indicator_desc").get("after"));
        assertEquals("10", changedFields.get("weight_percent").get("after"));
        assertEquals("战略新增备注", changedFields.get("remark").get("after"));
        // 异动标记照常打上，审批链照常启动
        verify(jdbcTemplate).update(
                contains("SET mutation_status = ?"), eq("IN_MUTATION"), eq(INDICATOR_ID));
    }

    @Test
    @DisplayName("2026-10-30 缺陷回归：批量新增条目（create=true）的 changed_fields 同样含三个字段键"
            + "（前端「发起异动」对新增行即走此批量路径）")
    void initiateBatch_shouldRecordAllThreeFieldsForCreatedIndicator() {
        stubReporter();
        Map<String, Object> row = indicatorRow("DRAFT", "DISTRIBUTED");
        row.put("indicator_desc", "批量新增指标");
        row.put("weight_percent", new java.math.BigDecimal("10.00"));
        row.put("remark", "下发备注内容");
        when(jdbcTemplate.queryForMap(contains("FROM public.indicator"), eq(INDICATOR_ID)))
                .thenReturn(row);
        stubWorkflowStart("1201");

        List<String> instanceIds = service.initiateBatch(
                List.of(Map.of("id", INDICATOR_ID, "create", true)),
                OPERATOR_ID);

        assertEquals(List.of("1201"), instanceIds);
        Map<String, Map<String, Object>> changedFields = capturedCreateChangedFields();
        assertEquals(Set.of("indicator_desc", "weight_percent", "remark"), changedFields.keySet());
        changedFields.forEach((field, diff) -> assertEquals(
                "(新增)", String.valueOf(diff.get("before")), field + " 的 before 应为 (新增)"));
        assertEquals("批量新增指标", changedFields.get("indicator_desc").get("after"));
        assertEquals("10.00", changedFields.get("weight_percent").get("after"));
        assertEquals("下发备注内容", changedFields.get("remark").get("after"));
    }

    /**
     * 捕获 audit_log 落库参数并取出 changed_fields 快照：
     * varargs 顺序为 [0]=action, [1]=before_json, [2]=after_json, [3]=changed_fields,
     * [4]=entity_id, [5]=reason, [6]=actor_user_id, [7]=batch_id。
     * insertAuditLog 落库前用 toJson 序列化，这里按 JSON 字符串反序列化后断言。
     */
    private Map<String, Map<String, Object>> capturedCreateChangedFields() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("INSERT INTO public.audit_log"), args.capture());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    String.valueOf(args.getValue()[3]), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("解析 changed_fields JSON 失败", e);
        }
    }

    @Test
    @DisplayName("异动资格按计划判定：计划未下发时，指标即便是 DISTRIBUTED 也拒绝")
    void initiateBatch_shouldRejectWhenPlanIsNotDistributed() {
        stubReporter();
        stubIndicatorRow("DISTRIBUTED", "DRAFT");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.initiateBatch(
                        List.of(Map.of("id", INDICATOR_ID, "indicator_desc", "新描述")),
                        OPERATOR_ID));

        assertTrue(error.getMessage().contains("仅已下发（稳定态）的计划"));
        verify(businessWorkflowApplicationService, never())
                .startWorkflow(any(StartWorkflowRequest.class), any(), any());
    }

    @Test
    @DisplayName("同批次「删一条 + 增一条」：待删除指标从权重合计中排除，不会被算成 110")
    void initiateBatch_shouldExcludePendingDeletesFromWeightValidation() {
        stubReporter();
        Long deleteId = INDICATOR_ID;
        Long createId = 2002L;

        Map<String, Object> deleteRow = indicatorRow("DISTRIBUTED", "DISTRIBUTED");
        deleteRow.put("weight_percent", new java.math.BigDecimal("10.00"));
        when(jdbcTemplate.queryForMap(contains("FROM public.indicator"), eq(deleteId)))
                .thenReturn(deleteRow);

        Map<String, Object> createRow = indicatorRow("DRAFT", "DISTRIBUTED");
        createRow.put("id", createId);
        createRow.put("weight_percent", new java.math.BigDecimal("10.00"));
        when(jdbcTemplate.queryForMap(contains("FROM public.indicator"), eq(createId)))
                .thenReturn(createRow);

        // 权重锚点查询：两个指标都归属同一（计划 4040、部门 41003）
        // 注意用 ResultSetExtractor 显式匹配——query(String, RowMapper, Object...) 的同名重载
        // 会让 lambda 推断歧义，导致 thenReturn 命中 List 版本而编译失败。
        when(jdbcTemplate.query(
                contains("SELECT t.plan_id"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Long>>any(),
                any()))
                .thenReturn(4040L);
        when(jdbcTemplate.query(
                contains("SELECT target_org_id"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Long>>any(),
                any()))
                .thenReturn(TARGET_ORG_ID);
        stubWorkflowStart("901", "902");

        service.initiateBatch(
                List.of(
                        Map.of("id", deleteId, "delete", true),
                        Map.of("id", createId, "create", true)),
                OPERATOR_ID);

        // 关键：校验时把待删除的指标显式排除，否则 10（待删）+ 10（新增）= 20 会被算进 110
        ArgumentCaptor<java.util.Set<Long>> excludedCaptor =
                ArgumentCaptor.forClass(java.util.Set.class);
        verify(basicTaskWeightValidationService).validatePlanBasicWeight(
                eq(4040L), eq(TARGET_ORG_ID), excludedCaptor.capture(), any());
        assertTrue(excludedCaptor.getValue().contains(deleteId));
        assertFalse(excludedCaptor.getValue().contains(createId));
    }

    @Test
    @DisplayName("唯一性锁定：同实体已有 IN_REVIEW 异动实例时，再次发起被拦下（驳回退回场景不产生第二条流程）")
    void initiateBatch_shouldRejectWhenActiveMutationInstanceExists() {
        stubReporter();
        stubIndicatorRow("DISTRIBUTED", "DISTRIBUTED");
        // 已有一条进行中的异动审批实例（如驳回退回填报人后仍存活的那条）
        when(jdbcTemplate.queryForObject(
                contains("SELECT COUNT(*)"), eq(Integer.class), eq("INDICATOR"), eq(INDICATOR_ID)))
                .thenReturn(1);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.initiateBatch(
                        List.of(Map.of("id", INDICATOR_ID, "indicator_desc", "再次修改")),
                        OPERATOR_ID));

        assertTrue(error.getMessage().contains("同一对象同时只能有一条异动流程"));
        verify(businessWorkflowApplicationService, never())
                .startWorkflow(any(StartWorkflowRequest.class), any(), any());
    }

    @Test
    @DisplayName("发起只打标记不落业务值：修改型异动提交后表里仍是原值（与上报口径一致，审批通过才更新）")
    void initiateBatch_shouldNotApplyBusinessValuesAtInitiation() {
        stubReporter();
        stubIndicatorRow("DISTRIBUTED", "DISTRIBUTED");
        // 权重锚点（计划、目标部门）反查，让闭合校验真正执行
        when(jdbcTemplate.query(
                contains("SELECT t.plan_id"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Long>>any(),
                any()))
                .thenReturn(4040L);
        when(jdbcTemplate.query(
                contains("SELECT target_org_id"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Long>>any(),
                any()))
                .thenReturn(TARGET_ORG_ID);
        stubWorkflowStart("951");

        service.initiateBatch(
                List.of(Map.of("id", INDICATOR_ID, "indicator_desc", "新描述", "weight_percent", 20)),
                OPERATOR_ID);

        // 只写异动标记，绝不原地写业务字段
        verify(jdbcTemplate).update(
                contains("SET mutation_status"), eq("IN_MUTATION"), eq(INDICATOR_ID));
        verify(jdbcTemplate, never()).update(
                contains("SET indicator_desc"), any(Object[].class));
        // 拟变更权重以 override 参与闭合校验（校验口径 = 本批生效后的快照）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<Long, java.math.BigDecimal>> overrideCaptor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(basicTaskWeightValidationService).validatePlanBasicWeight(
                any(), any(), any(), overrideCaptor.capture());
        assertEquals(
                java.math.BigDecimal.valueOf(20),
                overrideCaptor.getValue().get(INDICATOR_ID));
    }

    private void stubReporter() {
        when(userProvider.getUserOrgId(OPERATOR_ID)).thenReturn(Optional.of(STRATEGY_ORG_ID));
        when(jdbcTemplate.queryForList(
                contains("FROM public.sys_user_role"), eq(String.class), eq(OPERATOR_ID)))
                .thenReturn(List.of("ROLE_REPORTER"));
    }

    @Test
    @DisplayName("退回待重报清单：只查 WITHDRAWN 且 completed_at 为空的活链（含退回提交步骤），异动取消按钮数据源")
    @SuppressWarnings("unchecked")
    void listReturnedMutationChains_shouldOnlyQueryReturnedActiveChains() {
        when(jdbcTemplate.query(
                contains("public.audit_instance"),
                any(RowMapper.class),
                eq(STRATEGY_ORG_ID), eq(STRATEGY_ORG_ID), eq(STRATEGY_ORG_ID), eq(STRATEGY_ORG_ID)))
                .thenReturn(List.of(Map.of("instance_id", 88L, "batch_id", "batch-88")));

        var result = service.listReturnedMutationChains(STRATEGY_ORG_ID);

        assertEquals(1, result.size());
        // 断言查询口径：终态在审批历史（listInstanceHistoryByBusiness），此处只取驳回退回的活链
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                any(RowMapper.class),
                eq(STRATEGY_ORG_ID), eq(STRATEGY_ORG_ID), eq(STRATEGY_ORG_ID), eq(STRATEGY_ORG_ID));
        assertTrue(sqlCaptor.getValue().contains("ai.status = 'WITHDRAWN'"));
        assertTrue(sqlCaptor.getValue().contains("ai.completed_at IS NULL"));
        assertTrue(sqlCaptor.getValue().contains("PLAN_MUTATION_STRATEGY"));
        // 2026-10-30 二次定案（撤回零新增行）：退回提交步骤双形态识别——
        // 新形态：提交类步骤被原地置 WITHDRAWN（step_no=1 / 审批人=发起人）；
        // 旧形态：存量追加的退回提交行（步骤名含「提交」）
        assertTrue(sqlCaptor.getValue().contains("rsi.step_no = 1"));
        assertTrue(sqlCaptor.getValue().contains("rsi.approver_id = ai.requester_id"));
        assertTrue(sqlCaptor.getValue().contains("rsi.step_name LIKE '%提交%'"));
        // 不含审批中实例：IN_REVIEW 过滤（审批中心异动卡口径）不被本查询破坏
        assertFalse(sqlCaptor.getValue().contains("ai.status = 'IN_REVIEW'"));
    }

    @Test
    @DisplayName("退回待重报清单：orgId 缺失直接返回空，不打数据库")
    void listReturnedMutationChains_shouldReturnEmptyForNullOrg() {
        assertTrue(service.listReturnedMutationChains(null).isEmpty());
        verifyNoInteractions(jdbcTemplate);
    }

    // ==================== 2026-10-31 用户定案：重发 = 原地续链（resubmitBatch） ====================
    // 口径：一次异动的结束 = 取消异动或到达最终节点通过；期间重新发起（原样/改后）
    // 不生成新实例、不生成新 report、数据库零新增行——只是把审批节点的状态改回来。

    private static final Long RESUBMIT_INSTANCE_ID = 881L;
    private static final String RESUBMIT_BATCH_ID = "batch-881";
    private static final Long RESUBMIT_LOG_ID = 5001L;

    /** audit_instance 行（带 flow_code），形态与 mutationInstanceRow 一致。 */
    private Map<String, Object> mutationInstanceRow(Long instanceId, Long entityId, String entityType,
                                                    Long requesterId, String status,
                                                    Object completedAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", instanceId);
        row.put("entity_id", entityId);
        row.put("entity_type", entityType);
        row.put("batch_id", RESUBMIT_BATCH_ID);
        row.put("requester_id", requesterId);
        row.put("status", status);
        row.put("completed_at", completedAt);
        row.put("created_at", java.sql.Timestamp.valueOf("2026-10-30 10:00:00"));
        row.put("flow_code", "PLAN_MUTATION_STRATEGY");
        return row;
    }

    @SuppressWarnings("unchecked")
    private void stubResubmitInstance(Map<String, Object> instanceRow) {
        when(jdbcTemplate.query(
                contains("FROM public.audit_instance ai"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Map<String, Object>>>any(),
                eq(RESUBMIT_INSTANCE_ID)))
                .thenReturn(instanceRow);
    }

    @SuppressWarnings("unchecked")
    private void stubResubmitBatchRows(List<Map<String, Object>> rows) {
        when(jdbcTemplate.queryForList(contains("WHERE ai.batch_id = ?"), eq(RESUBMIT_BATCH_ID)))
                .thenReturn(rows);
    }

    /** 本批快照行装载（latestRoundAuditLog）打桩：按 (entity_type, entity_id, batch_id) 精确匹配。 */
    @SuppressWarnings("unchecked")
    private void stubResubmitAuditLog(String entityType, Long entityId, Map<String, Object> logRow) {
        when(jdbcTemplate.query(
                contains("FROM public.audit_log"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Map<String, Object>>>any(),
                eq(entityType), eq(entityId), eq(RESUBMIT_BATCH_ID)))
                .thenReturn(logRow);
    }

    /** 本批快照行（首轮 initiateBatch 写入的 audit_log 行，含 log_id）。 */
    private Map<String, Object> batchAuditLogRow() {
        Map<String, Object> log = new HashMap<>();
        log.put("log_id", RESUBMIT_LOG_ID);
        log.put("changed_fields", Map.of(
                "indicator_desc", Map.of("before", "旧描述", "after", "首轮拟描述"),
                "weight_percent", Map.of("before", 10, "after", 20)));
        log.put("after_json", Map.of("indicator_desc", "首轮拟描述", "weight_percent", 20));
        log.put("before_json", Map.of("indicator_desc", "旧描述", "weight_percent", 10));
        return log;
    }

    /**
     * 构造「退回待重报」活链的领域实例（withdrawToRequester 新形态链形：
     * step_no=1 提交步与 step_no=2 审批步均原地 WITHDRAWN，实例 WITHDRAWN、
     * completedAt 为空），并打桩仓储 save 原样返回（领域方法原地修改聚合）。
     */
    private com.sism.workflow.domain.runtime.AuditInstance stubReturnedInstanceLoaded() {
        var instance = new com.sism.workflow.domain.runtime.AuditInstance();
        instance.setId(RESUBMIT_INSTANCE_ID);
        instance.setEntityType("INDICATOR");
        instance.setEntityId(INDICATOR_ID);
        instance.setRequesterId(OPERATOR_ID);
        instance.setRequesterOrgId(STRATEGY_ORG_ID);
        // 2026-10-31（重发后续链通知）：审批步带 stepDefId，供通知链路解析节点定义
        instance.setFlowDefId(60L);
        instance.setBatchId(RESUBMIT_BATCH_ID);
        instance.setStatus("WITHDRAWN");
        instance.setCompletedAt(null);
        var submitStep = new com.sism.workflow.domain.runtime.AuditStepInstance();
        submitStep.setStepNo(1);
        submitStep.setStepName("填报人提交");
        submitStep.setStatus("WITHDRAWN");
        instance.addStepInstance(submitStep);
        var approvalStep = new com.sism.workflow.domain.runtime.AuditStepInstance();
        approvalStep.setStepNo(2);
        // 2026-10-31（重发后续链通知）：审批步带 stepDefId，供通知链路解析节点定义
        approvalStep.setStepDefId(501L);
        approvalStep.setStepName("战略部负责人审批");
        approvalStep.setStatus("WITHDRAWN");
        instance.addStepInstance(approvalStep);
        when(auditInstanceRepository.findById(RESUBMIT_INSTANCE_ID))
                .thenReturn(Optional.of(instance));
        when(auditInstanceRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return instance;
    }

    @Test
    @DisplayName("重发仅限发起人：非发起人操作被拒（requester-only，与取消异动同口径）")
    void resubmitBatch_shouldRejectNonRequester() {
        stubResubmitInstance(mutationInstanceRow(
                RESUBMIT_INSTANCE_ID, INDICATOR_ID, "INDICATOR", 777L, "WITHDRAWN", null));

        SecurityException error = assertThrows(SecurityException.class, () ->
                service.resubmitBatch(RESUBMIT_INSTANCE_ID, OPERATOR_ID, null));

        assertTrue(error.getMessage().contains("仅异动发起人可重新发起"));
        verify(businessWorkflowApplicationService, never())
                .startWorkflow(any(StartWorkflowRequest.class), any(), any());
    }

    @Test
    @DisplayName("重发仅限退回待重报活链：实例已终结（completed_at 非空）被拒")
    void resubmitBatch_shouldRejectFinishedChain() {
        stubResubmitInstance(mutationInstanceRow(
                RESUBMIT_INSTANCE_ID, INDICATOR_ID, "INDICATOR", OPERATOR_ID, "WITHDRAWN",
                java.sql.Timestamp.valueOf("2026-10-30 12:00:00")));

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                service.resubmitBatch(RESUBMIT_INSTANCE_ID, OPERATOR_ID, null));

        assertTrue(error.getMessage().contains("退回待重报"));
        verify(businessWorkflowApplicationService, never())
                .startWorkflow(any(StartWorkflowRequest.class), any(), any());
    }

    @Test
    @DisplayName("非异动链（如上报流程）实例不能走重发（与 assertMutationChainInstance 等价校验）")
    void resubmitBatch_shouldRejectNonMutationChain() {
        Map<String, Object> row = mutationInstanceRow(
                RESUBMIT_INSTANCE_ID, INDICATOR_ID, "INDICATOR", OPERATOR_ID, "WITHDRAWN", null);
        row.put("flow_code", "PLAN_APPROVAL_FUNCDEPT");
        stubResubmitInstance(row);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.resubmitBatch(RESUBMIT_INSTANCE_ID, OPERATOR_ID, null));

        assertTrue(error.getMessage().contains("仅异动审批链"));
        verify(businessWorkflowApplicationService, never())
                .startWorkflow(any(StartWorkflowRequest.class), any(), any());
    }

    @Test
    @DisplayName("原样重发原地续链：零新增 audit_log 行、batch_id 不变、异动锁重建、"
            + "实例回 IN_REVIEW（提交步 APPROVED + 审批步 PENDING）")
    void resubmitBatch_shouldResumeInPlaceWithoutAnyNewRows() {
        stubResubmitInstance(mutationInstanceRow(
                RESUBMIT_INSTANCE_ID, INDICATOR_ID, "INDICATOR", OPERATOR_ID, "WITHDRAWN", null));
        stubResubmitBatchRows(List.of(mutationInstanceRow(
                RESUBMIT_INSTANCE_ID, INDICATOR_ID, "INDICATOR", OPERATOR_ID, "WITHDRAWN", null)));
        stubResubmitAuditLog("INDICATOR", INDICATOR_ID, batchAuditLogRow());
        stubReturnedInstanceLoaded();

        var result = service.resubmitBatch(RESUBMIT_INSTANCE_ID, OPERATOR_ID, null);

        // 返回的实例 ID 就是原链 ID（原地续链，不产生新实例）
        assertEquals(List.of(String.valueOf(RESUBMIT_INSTANCE_ID)), result.instanceIds());
        assertEquals("异动已重新发起", result.message());
        // 零新增：不写 audit_log INSERT、不走发起路径（不产生新 batchId/新实例/新快照行）
        verify(jdbcTemplate, never()).update(
                contains("INSERT INTO public.audit_log"), any(Object[].class));
        verify(jdbcTemplate, never()).update(
                contains("UPDATE public.audit_log"), any(Object[].class));
        verify(businessWorkflowApplicationService, never())
                .startWorkflow(any(StartWorkflowRequest.class), any(), any());
        // batch_id 不改写（旧实现每次重发都 UPDATE audit_instance SET batch_id）
        verify(jdbcTemplate, never()).update(contains("batch_id = ?"), any(Object[].class));
        // 业务锁重建（不写日志，列写入口径与发起一致）
        verify(jdbcTemplate).update(
                contains("SET mutation_status"), eq("IN_MUTATION"), eq(INDICATOR_ID));
        // 实例原地复活：IN_REVIEW、completedAt 保持 NULL、提交步 APPROVED、审批步 PENDING
        ArgumentCaptor<com.sism.workflow.domain.runtime.AuditInstance> saved =
                ArgumentCaptor.forClass(com.sism.workflow.domain.runtime.AuditInstance.class);
        verify(auditInstanceRepository).save(saved.capture());
        assertEquals("IN_REVIEW", saved.getValue().getStatus());
        assertNull(saved.getValue().getCompletedAt());
        assertEquals(RESUBMIT_BATCH_ID, saved.getValue().getBatchId());
        assertEquals("APPROVED", saved.getValue().getStepInstances().get(0).getStatus());
        assertEquals("PENDING", saved.getValue().getStepInstances().get(1).getStatus());
        // 保持现有通知行为：逐实例业务同步 + 事件广播
        verify(workflowBusinessStatusSyncService).syncAfterWorkflowChanged(any());
        verify(workflowEventDispatcher).publish(any());
        // 内容未变：权重闭合校验不必重跑
        verifyNoInteractions(basicTaskWeightValidationService);
    }

    /**
     * 2026-10-31 缺陷修复（原样重发后审批人无待办通知，线上实证 02 号日志异常3）：
     * 重发续链成功后，下一环节候选审批人收到与首发起同口径的「待审批」站内信
     * （createSubmissionNotification，与上报链首发起 PlanWorkflowEventListener 同一
     * 通知方法）；批次只发 1 条（首个复活实例代表整批）。
     */
    @Test
    @DisplayName("重发后通知下一环节候选审批人：与首发起同口径的待审批站内信（批次只发 1 条）")
    void resubmitBatch_shouldNotifyNextStepCandidatesLikeFirstSubmission() {
        stubResubmitInstance(mutationInstanceRow(
                RESUBMIT_INSTANCE_ID, INDICATOR_ID, "INDICATOR", OPERATOR_ID, "WITHDRAWN", null));
        stubResubmitBatchRows(List.of(mutationInstanceRow(
                RESUBMIT_INSTANCE_ID, INDICATOR_ID, "INDICATOR", OPERATOR_ID, "WITHDRAWN", null)));
        stubResubmitAuditLog("INDICATOR", INDICATOR_ID, batchAuditLogRow());
        var instance = stubReturnedInstanceLoaded();

        // 当前审批节点定义（复活后的战略部负责人审批节点，stepDefId=501）
        var flowDef = new com.sism.workflow.domain.definition.AuditFlowDef();
        var approvalDef = new com.sism.workflow.domain.definition.AuditStepDef();
        approvalDef.setId(501L);
        approvalDef.setStepName("战略部负责人审批");
        flowDef.setSteps(List.of(approvalDef));
        when(workflowDefinitionQueryService.getAuditFlowDefById(60L)).thenReturn(flowDef);
        when(userProvider.getUserOrgId(OPERATOR_ID)).thenReturn(Optional.of(STRATEGY_ORG_ID));
        when(approverResolver.resolveApproverName(OPERATOR_ID)).thenReturn("战略部填报人");
        var candidate = com.sism.workflow.interfaces.dto.ApproverCandidateResponse.builder()
                .userId(21L).username("zlb_final1").realName("战略部负责人1").orgId(STRATEGY_ORG_ID).build();
        when(approverResolver.resolveCandidates(eq(approvalDef), eq(STRATEGY_ORG_ID), any()))
                .thenReturn(List.of(candidate));
        var detail = new com.sism.workflow.interfaces.dto.WorkflowInstanceDetailResponse();
        detail.setPlanName("计算机学院年度竞赛专项");
        when(workflowReadModelService.getInstanceDetail(String.valueOf(RESUBMIT_INSTANCE_ID)))
                .thenReturn(detail);

        var result = service.resubmitBatch(RESUBMIT_INSTANCE_ID, OPERATOR_ID, null);

        assertEquals(List.of(String.valueOf(RESUBMIT_INSTANCE_ID)), result.instanceIds());
        // 与首发起同口径：type=APPROVAL_SUBMITTED 由 createSubmissionNotification 承载，
        // 收件人=候选审批人，正文带提交人与当前待处理环节
        verify(userNotificationService).createSubmissionNotification(
                eq(Long.valueOf(21L)), eq(OPERATOR_ID), eq(STRATEGY_ORG_ID), eq(RESUBMIT_INSTANCE_ID),
                eq("INDICATOR"), eq(INDICATOR_ID), eq("计算机学院年度竞赛专项"),
                eq("战略部负责人审批"), eq("战略部填报人"));
        // 重发主流程不变：原地续链 + 业务锁重建
        verify(jdbcTemplate).update(
                contains("SET mutation_status"), eq("IN_MUTATION"), eq(INDICATOR_ID));
        verify(workflowEventDispatcher).publish(any());
    }

    @Test
    @DisplayName("重发后通知失败不影响重发主流程（fail-safe，与既有通知策略同口径）")
    void resubmitBatch_shouldNotFailWhenNotificationFails() {
        stubResubmitInstance(mutationInstanceRow(
                RESUBMIT_INSTANCE_ID, INDICATOR_ID, "INDICATOR", OPERATOR_ID, "WITHDRAWN", null));
        stubResubmitBatchRows(List.of(mutationInstanceRow(
                RESUBMIT_INSTANCE_ID, INDICATOR_ID, "INDICATOR", OPERATOR_ID, "WITHDRAWN", null)));
        stubResubmitAuditLog("INDICATOR", INDICATOR_ID, batchAuditLogRow());
        stubReturnedInstanceLoaded();

        var flowDef = new com.sism.workflow.domain.definition.AuditFlowDef();
        var approvalDef = new com.sism.workflow.domain.definition.AuditStepDef();
        approvalDef.setId(501L);
        approvalDef.setStepName("战略部负责人审批");
        flowDef.setSteps(List.of(approvalDef));
        when(workflowDefinitionQueryService.getAuditFlowDefById(60L)).thenReturn(flowDef);
        when(workflowReadModelService.getInstanceDetail(any()))
                .thenThrow(new RuntimeException("read model down"));

        var result = service.resubmitBatch(RESUBMIT_INSTANCE_ID, OPERATOR_ID, null);

        // 重发照常完成（原地续链），仅通知被跳过
        assertEquals(List.of(String.valueOf(RESUBMIT_INSTANCE_ID)), result.instanceIds());
        assertEquals("异动已重新发起", result.message());
        verify(jdbcTemplate).update(
                contains("SET mutation_status"), eq("IN_MUTATION"), eq(INDICATOR_ID));
        verify(auditInstanceRepository).save(any());
        verifyNoInteractions(userNotificationService);
    }

    @Test
    @DisplayName("改后重发（带 items）原地 UPDATE 本批日志行：行数不变、after 更新、"
            + "before 保留；合并后的 after 权重重跑闭合校验")
    @SuppressWarnings("unchecked")
    void resubmitBatch_shouldUpdateAuditLogInPlaceWhenItemsProvided() {
        stubResubmitInstance(mutationInstanceRow(
                RESUBMIT_INSTANCE_ID, INDICATOR_ID, "INDICATOR", OPERATOR_ID, "WITHDRAWN", null));
        stubResubmitBatchRows(List.of(mutationInstanceRow(
                RESUBMIT_INSTANCE_ID, INDICATOR_ID, "INDICATOR", OPERATOR_ID, "WITHDRAWN", null)));
        stubResubmitAuditLog("INDICATOR", INDICATOR_ID, batchAuditLogRow());
        stubReturnedInstanceLoaded();
        // 权重闭合校验的（计划、目标部门）反查
        when(jdbcTemplate.query(
                contains("SELECT t.plan_id"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Long>>any(),
                any()))
                .thenReturn(4040L);
        when(jdbcTemplate.query(
                contains("SELECT target_org_id"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Long>>any(),
                any()))
                .thenReturn(TARGET_ORG_ID);

        var result = service.resubmitBatch(RESUBMIT_INSTANCE_ID, OPERATOR_ID,
                List.of(Map.of("id", INDICATOR_ID, "indicator_desc", "改后描述", "weight_percent", 25)));

        assertEquals(List.of(String.valueOf(RESUBMIT_INSTANCE_ID)), result.instanceIds());
        assertEquals("异动已按修改内容重新发起", result.message());
        // 行数不变：绝不 INSERT，只原地 UPDATE 本批既有行；UPDATE 不触碰 before_json/action
        verify(jdbcTemplate, never()).update(
                contains("INSERT INTO public.audit_log"), any(Object[].class));
        ArgumentCaptor<Object[]> logArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.contains("UPDATE public.audit_log")
                                && !sql.contains("before_json")
                                && !sql.contains("action")),
                logArgs.capture());
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            Map<String, Object> changedFields =
                    objectMapper.readValue(String.valueOf(logArgs.getValue()[0]), Map.class);
            Map<String, Object> afterJson =
                    objectMapper.readValue(String.valueOf(logArgs.getValue()[1]), Map.class);
            // after 更新：desc 用条目新值；条目未涉及的既有变更字段（weight）也一并
            // 保留首轮 after 值——重发是「同一异动的微调」，不静默丢弃已提交变更
            Map<String, Object> descDelta = (Map<String, Object>) changedFields.get("indicator_desc");
            assertEquals("改后描述", descDelta.get("after"));
            assertEquals("旧描述", descDelta.get("before"));
            Map<String, Object> weightDelta = (Map<String, Object>) changedFields.get("weight_percent");
            assertEquals(25, weightDelta.get("after"));
            assertEquals(10, weightDelta.get("before"));
            assertEquals("改后描述", afterJson.get("indicator_desc"));
            assertEquals(25, afterJson.get("weight_percent"));
            // 按 log_id 定位本批既有行（绝不 INSERT 新行）
            assertEquals(RESUBMIT_LOG_ID.longValue(),
                    ((Number) logArgs.getValue()[2]).longValue());
        } catch (Exception e) {
            throw new IllegalStateException("解析原地改写后的快照 JSON 失败", e);
        }
        // 权重校验重跑：合并后的 after 权重（25）以 override 参与，口径与 initiateBatch 一致
        ArgumentCaptor<java.util.Map<Long, java.math.BigDecimal>> overrideCaptor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(basicTaskWeightValidationService).validatePlanBasicWeight(
                eq(4040L), eq(TARGET_ORG_ID), any(), overrideCaptor.capture());
        assertEquals(java.math.BigDecimal.valueOf(25), overrideCaptor.getValue().get(INDICATOR_ID));
        // 照常重建业务锁并原地续链（save → sync → publish）
        verify(jdbcTemplate).update(
                contains("SET mutation_status"), eq("IN_MUTATION"), eq(INDICATOR_ID));
        verify(auditInstanceRepository).save(any(com.sism.workflow.domain.runtime.AuditInstance.class));
        verify(workflowBusinessStatusSyncService).syncAfterWorkflowChanged(any());
        verify(workflowEventDispatcher).publish(any());
    }

    @Test
    @DisplayName("改后重发条目必须指向批内既有对象：批外实体被拒（重发不新增异动对象）")
    void resubmitBatch_shouldRejectItemsNotInBatch() {
        stubResubmitInstance(mutationInstanceRow(
                RESUBMIT_INSTANCE_ID, INDICATOR_ID, "INDICATOR", OPERATOR_ID, "WITHDRAWN", null));
        stubResubmitBatchRows(List.of(mutationInstanceRow(
                RESUBMIT_INSTANCE_ID, INDICATOR_ID, "INDICATOR", OPERATOR_ID, "WITHDRAWN", null)));
        stubResubmitAuditLog("INDICATOR", INDICATOR_ID, batchAuditLogRow());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.resubmitBatch(RESUBMIT_INSTANCE_ID, OPERATOR_ID,
                        List.of(Map.of("id", 9999L, "indicator_desc", "批外对象"))));

        assertTrue(error.getMessage().contains("重发条目与原批次不匹配"));
        // 不产生任何日志行写入与链操作
        verify(jdbcTemplate, never()).update(
                contains("UPDATE public.audit_log"), any(Object[].class));
        verify(auditInstanceRepository, never()).save(any());
    }

    @Test
    @DisplayName("快照缺失：新模型下发起必写快照，缺失视为数据异常直接报错不再静默跳过")
    void resubmitBatch_shouldRejectWhenSnapshotMissing() {
        Long missingSnapshotInstanceId = 882L;
        Long missingSnapshotEntityId = 2002L;
        stubResubmitInstance(mutationInstanceRow(
                RESUBMIT_INSTANCE_ID, INDICATOR_ID, "INDICATOR", OPERATOR_ID, "WITHDRAWN", null));
        stubResubmitBatchRows(List.of(
                mutationInstanceRow(RESUBMIT_INSTANCE_ID, INDICATOR_ID, "INDICATOR",
                        OPERATOR_ID, "WITHDRAWN", null),
                mutationInstanceRow(missingSnapshotInstanceId, missingSnapshotEntityId, "INDICATOR",
                        OPERATOR_ID, "WITHDRAWN", null)));
        // 第一条实例有本批快照；第二条实例快照缺失（未打桩 → 查询返回 null）
        stubResubmitAuditLog("INDICATOR", INDICATOR_ID, batchAuditLogRow());

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                service.resubmitBatch(RESUBMIT_INSTANCE_ID, OPERATOR_ID, null));

        assertTrue(error.getMessage().contains("缺少本批异动快照"));
        assertTrue(error.getMessage().contains("指标#" + missingSnapshotEntityId));
        // 整批拒绝：不重建锁、不续链、不走发起路径
        verify(jdbcTemplate, never()).update(
                contains("SET mutation_status"), any(Object[].class));
        verify(auditInstanceRepository, never()).save(any());
        verify(businessWorkflowApplicationService, never())
                .startWorkflow(any(StartWorkflowRequest.class), any(), any());
    }

    private Map<String, Object> indicatorRow(String indicatorStatus, String planStatus) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", INDICATOR_ID);
        row.put("indicator_desc", "测试指标");
        row.put("weight_percent", new java.math.BigDecimal("10.00"));
        row.put("remark", null);
        row.put("status", indicatorStatus);
        row.put("mutation_status", null);
        row.put("plan_status", planStatus);
        return row;
    }

    private void stubIndicatorRow(String indicatorStatus, String planStatus) {
        when(jdbcTemplate.queryForMap(contains("FROM public.indicator"), eq(INDICATOR_ID)))
                .thenReturn(indicatorRow(indicatorStatus, planStatus));
    }

    private void stubWorkflowStart(String... instanceIds) {
        WorkflowInstanceResponse[] responses = java.util.Arrays.stream(instanceIds)
                .map(id -> WorkflowInstanceResponse.builder().instanceId(id).build())
                .toArray(WorkflowInstanceResponse[]::new);
        var stub = when(businessWorkflowApplicationService.startWorkflow(
                any(StartWorkflowRequest.class), eq(OPERATOR_ID), eq(STRATEGY_ORG_ID)));
        org.mockito.stubbing.OngoingStubbing<WorkflowInstanceResponse> ongoing = stub;
        for (WorkflowInstanceResponse response : responses) {
            ongoing = ongoing.thenReturn(response);
        }
    }
}
