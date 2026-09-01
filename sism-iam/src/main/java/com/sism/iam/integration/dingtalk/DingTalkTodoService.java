package com.sism.iam.integration.dingtalk;

import com.sism.iam.integration.dingtalk.domain.DingTalkTodoTask;
import com.sism.iam.integration.dingtalk.domain.DingTalkTodoTaskRepository;
import com.sism.iam.integration.dingtalk.domain.DingTalkUserBinding;
import com.sism.iam.integration.dingtalk.domain.DingTalkUserBindingRepository;
import com.sism.shared.domain.integration.DingTalkTodoProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mirrors SISM approval todos into the DingTalk todo center. When a
 * todo-card-type key is configured, todos render as custom dual-button cards
 * (native complete button + a jump button to the H5 approval page); completion
 * is synced back through the executor status API. Every operation is fail-safe:
 * DingTalk outages must never break the core approval flow.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DingTalkTodoService implements DingTalkTodoProvider {

    private static final int PRIORITY_HIGH = 30;

    private final DingTalkClient dingTalkClient;
    private final DingTalkProperties properties;
    private final DingTalkUserBindingService bindingService;
    private final DingTalkTodoTaskRepository todoTaskRepository;

    @Override
    public void pushApprovalTodo(ApprovalTodoPush todo) {
        if (todo == null || todo.sysUserId() == null || todo.approvalInstanceId() == null) {
            return;
        }
        try {
            pushInternal(todo);
        } catch (Exception ex) {
            log.warn("Failed to push DingTalk todo for instance {} user {}: {}",
                    todo.approvalInstanceId(), todo.sysUserId(), ex.getMessage());
        }
    }

    @Override
    @Transactional
    public void completeStepTodos(Long approvalInstanceId, Long stepInstanceId) {
        if (approvalInstanceId == null || stepInstanceId == null) {
            return;
        }
        completeInternal(todoTaskRepository
                .findByApprovalInstanceIdAndStepInstanceIdAndStatus(
                        approvalInstanceId, stepInstanceId, DingTalkTodoTask.STATUS_PENDING));
    }

    @Override
    @Transactional
    public void completeInstanceTodos(Long approvalInstanceId) {
        if (approvalInstanceId == null) {
            return;
        }
        completeInternal(todoTaskRepository
                .findByApprovalInstanceIdAndStatus(
                        approvalInstanceId, DingTalkTodoTask.STATUS_PENDING));
    }

    private void pushInternal(ApprovalTodoPush todo) {
        if (!dingTalkClient.isAvailable()) {
            return;
        }
        DingTalkUserBinding binding = bindingService.ensureBinding(todo.sysUserId()).orElse(null);
        if (binding == null) {
            log.debug("Skip DingTalk todo: user {} has no dingtalk binding and phone lookup failed",
                    todo.sysUserId());
            return;
        }
        if (binding.getDingTalkUnionId() == null || binding.getDingTalkUnionId().isBlank()) {
            refreshUnionId(binding);
            if (binding.getDingTalkUnionId() == null) {
                log.warn("Skip DingTalk todo: binding {} has no unionId", binding.getId());
                return;
            }
        }
        if (properties.getH5BaseUrl() == null || properties.getH5BaseUrl().isBlank()) {
            log.warn("Skip DingTalk todo: app.dingtalk.h5-base-url is not configured");
            return;
        }

        String sourceId = buildSourceId(entityTypeNormalizedSafe(todo), todo.entityId(),
                todo.approvalInstanceId(), todo.stepInstanceId());
        Optional<DingTalkTodoTask> existing = todoTaskRepository
                .findBySourceIdAndSysUserIdAndStatus(sourceId, todo.sysUserId(), DingTalkTodoTask.STATUS_PENDING);
        if (existing.isPresent()) {
            log.debug("Skip duplicate DingTalk todo: sourceId={} user={}", sourceId, todo.sysUserId());
            return;
        }

        String entityType = todo.entityTypeNormalized();
        String detailUrl = buildDetailUrl(entityType, todo.entityId(), todo.approvalInstanceId(),
                todo.departmentName());
        String subject = "【待审批】" + resolveBusinessName(todo);
        String description = todo.stepName() == null ? "请进入系统处理审批" : "当前环节：" + todo.stepName();

        String taskId = dingTalkClient.createTodoTask(
                binding.getDingTalkUnionId(),
                subject,
                description,
                detailUrl,
                buildPcAppLink(detailUrl),
                List.of(binding.getDingTalkUnionId()),
                sourceId,
                PRIORITY_HIGH,
                properties.getTodoCardTypeKey(),
                buildCardData(todo));

        DingTalkTodoTask record = new DingTalkTodoTask();
        record.setApprovalInstanceId(todo.approvalInstanceId());
        record.setStepInstanceId(todo.stepInstanceId());
        record.setSysUserId(todo.sysUserId());
        record.setDingTalkUnionId(binding.getDingTalkUnionId());
        record.setDingTalkTaskId(taskId);
        record.setSourceId(sourceId);
        record.setDetailUrl(detailUrl);
        record.setStatus(DingTalkTodoTask.STATUS_PENDING);
        todoTaskRepository.save(record);
        log.info("Pushed DingTalk todo {} for instance {} user {}",
                taskId, todo.approvalInstanceId(), todo.sysUserId());
    }

    private void completeInternal(List<DingTalkTodoTask> pending) {
        for (DingTalkTodoTask task : pending) {
            boolean remoteDone = false;
            try {
                remoteDone = dingTalkClient.completeTodoTask(
                        task.getDingTalkUnionId(),
                        task.getDingTalkTaskId(),
                        List.of(task.getDingTalkUnionId()));
            } catch (Exception ex) {
                log.warn("Failed to complete DingTalk todo {} remotely: {}",
                        task.getDingTalkTaskId(), ex.getMessage());
            }
            if (remoteDone) {
                task.setStatus(DingTalkTodoTask.STATUS_COMPLETED);
                task.setCompletedAt(LocalDateTime.now());
                task.setUpdatedAt(LocalDateTime.now());
                todoTaskRepository.save(task);
            }
        }
    }

    /**
     * 卡片类型内容区字段值（fieldKey 与卡片类型配置 contentFieldList 对应）。
     */
    private Map<String, String> buildCardData(ApprovalTodoPush todo) {
        if (properties.getTodoCardTypeKey() == null || properties.getTodoCardTypeKey().isBlank()) {
            return null;
        }
        Map<String, String> data = new HashMap<>();
        if (todo.submitterName() != null && !todo.submitterName().isBlank()) {
            data.put("submitter", todo.submitterName().trim());
        }
        if (todo.departmentName() != null && !todo.departmentName().isBlank()) {
            data.put("department", todo.departmentName().trim());
        }
        if (todo.stepName() != null && !todo.stepName().isBlank()) {
            data.put("step", todo.stepName().trim());
        }
        return data;
    }

    private void refreshUnionId(DingTalkUserBinding binding) {
        try {
            bindingService.refreshUnionId(binding);
        } catch (Exception ex) {
            log.warn("Failed to refresh unionId for binding {}: {}",
                    binding.getId(), ex.getMessage());
        }
    }

    private String buildDetailUrl(String entityType, Long entityId, Long approvalInstanceId,
                                  String departmentName) {
        StringBuilder url = new StringBuilder(properties.getH5BaseUrl())
                .append("/strategic-tasks?tab=approval&openApproval=1");
        if (entityType != null) {
            url.append("&approvalEntityType=").append(urlencode(entityType));
        }
        if (entityId != null) {
            url.append("&approvalEntityId=").append(entityId);
        }
        if (approvalInstanceId != null) {
            url.append("&approvalInstanceId=").append(approvalInstanceId);
        }
        if (departmentName != null && !departmentName.isBlank()) {
            url.append("&approvalDepartment=").append(urlencode(departmentName.trim()));
        }
        // 钉钉容器对卡片链接默认半屏打开，官方参数强制全屏
        url.append("&dd_full_screen=true");
        return url.toString();
    }

    /**
     * sourceId 同时承载跳转所需的业务标识（卡片按钮 URL 模板只回传 sourceId）：
     * sism-approval-{entityType}-{entityId}-{instanceId}[-{stepId}]
     */
    private String buildSourceId(String entityType, Long entityId, Long approvalInstanceId,
                                  Long stepInstanceId) {
        String type = entityType == null || entityType.isBlank() ? "UNKNOWN" : entityType;
        return "sism-approval-" + type
                + (entityId == null ? "-0" : "-" + entityId)
                + "-" + approvalInstanceId
                + (stepInstanceId == null ? "" : "-" + stepInstanceId);
    }

    private String entityTypeNormalizedSafe(ApprovalTodoPush todo) {
        String normalized = todo.entityTypeNormalized();
        return normalized == null || normalized.isBlank() ? "UNKNOWN" : normalized;
    }

    private String resolveBusinessName(ApprovalTodoPush todo) {
        if (todo.businessName() != null && !todo.businessName().isBlank()) {
            return todo.businessName().trim();
        }
        return todo.entityId() == null ? "审批事项" : "业务对象#" + todo.entityId();
    }

    /**
     * PC 桌面端 AppLink：h5_app_open 以应用身份在工作台容器打开（占满主窗口，
     * 保留免登上下文）；appId 为 AgentId（DINGTALK_AGENT_ID）。
     * 应用首页须配置为根地址（如 https://host/），path 拼装才不会出现叠加路径。
     */
    private String buildPcAppLink(String targetUrl) {
        String path = targetUrl.replaceFirst("^https?://[^/]+", "");
        return "https://applink.dingtalk.com/page/h5_app_open"
                + "?appId=" + urlencode(properties.resolveAppLinkAppId())
                + "&appType=2"
                + "&corpId=" + urlencode(properties.getCorpId())
                + "&path=" + urlencode(path)
                + "&target=fullScreen"
                + "&targetDesktop=workbench"
                + "&fallbackLink=" + urlencode(targetUrl);
    }

    private static String urlencode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
