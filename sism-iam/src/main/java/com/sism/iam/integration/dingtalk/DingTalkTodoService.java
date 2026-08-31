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
import java.util.List;
import java.util.Optional;

/**
 * Mirrors SISM approval todos into DingTalk as custom robot cards (ActionCard
 * with a jump button to the H5 approval page). Native todo center entries are
 * intentionally not used: their complete-button behavior cannot be customized.
 * Every operation is fail-safe: DingTalk outages must never break the core
 * approval flow.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DingTalkTodoService implements DingTalkTodoProvider {

    private static final int RECALL_BATCH_SIZE = 20;

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
            log.debug("Skip DingTalk card: user {} has no dingtalk binding and phone lookup failed",
                    todo.sysUserId());
            return;
        }
        if (binding.getDingTalkUserId() == null || binding.getDingTalkUserId().isBlank()) {
            log.warn("Skip DingTalk card: binding {} has no dingtalk user id", binding.getId());
            return;
        }
        if (properties.getH5BaseUrl() == null || properties.getH5BaseUrl().isBlank()) {
            log.warn("Skip DingTalk card: app.dingtalk.h5-base-url is not configured");
            return;
        }

        String sourceId = buildSourceId(todo.approvalInstanceId(), todo.stepInstanceId());
        Optional<DingTalkTodoTask> existing = todoTaskRepository
                .findBySourceIdAndSysUserIdAndStatus(sourceId, todo.sysUserId(), DingTalkTodoTask.STATUS_PENDING);
        if (existing.isPresent()) {
            log.debug("Skip duplicate DingTalk card: sourceId={} user={}", sourceId, todo.sysUserId());
            return;
        }

        String entityType = todo.entityTypeNormalized();
        String detailUrl = buildDetailUrl(entityType, todo.entityId(), todo.approvalInstanceId(),
                todo.departmentName());
        String businessName = resolveBusinessName(todo);
        String title = "【待审批】" + businessName;
        String markdown = buildCardMarkdown(businessName, todo);
        if (binding.getDingTalkUnionId() == null || binding.getDingTalkUnionId().isBlank()) {
            refreshUnionId(binding);
        }

        String processQueryKey = dingTalkClient.sendApprovalCard(
                binding.getDingTalkUserId(),
                title,
                markdown,
                "点击查看审批详情",
                detailUrl);

        DingTalkTodoTask record = new DingTalkTodoTask();
        record.setApprovalInstanceId(todo.approvalInstanceId());
        record.setStepInstanceId(todo.stepInstanceId());
        record.setSysUserId(todo.sysUserId());
        record.setDingTalkUnionId(binding.getDingTalkUnionId());
        record.setDingTalkTaskId(processQueryKey);
        record.setSourceId(sourceId);
        record.setDetailUrl(detailUrl);
        record.setStatus(DingTalkTodoTask.STATUS_PENDING);
        todoTaskRepository.save(record);
        log.info("Pushed DingTalk approval card {} for instance {} user {}",
                processQueryKey, todo.approvalInstanceId(), todo.sysUserId());
    }

    private void completeInternal(List<DingTalkTodoTask> pending) {
        for (int from = 0; from < pending.size(); from += RECALL_BATCH_SIZE) {
            List<DingTalkTodoTask> batch = pending.subList(from,
                    Math.min(from + RECALL_BATCH_SIZE, pending.size()));
            List<String> keys = batch.stream()
                    .map(DingTalkTodoTask::getDingTalkTaskId)
                    .filter(id -> id != null && !id.isBlank())
                    .toList();
            boolean recalled = false;
            try {
                recalled = dingTalkClient.recallCards(keys);
            } catch (Exception ex) {
                log.warn("Failed to recall DingTalk cards {}: {}", keys.size(), ex.getMessage());
            }
            if (!recalled) {
                log.warn("DingTalk card recall reported failures for {} cards", keys.size());
            }
            for (DingTalkTodoTask task : batch) {
                task.setStatus(DingTalkTodoTask.STATUS_COMPLETED);
                task.setCompletedAt(LocalDateTime.now());
                task.setUpdatedAt(LocalDateTime.now());
                todoTaskRepository.save(task);
            }
        }
    }

    private String buildCardMarkdown(String businessName, ApprovalTodoPush todo) {
        StringBuilder md = new StringBuilder("### 【待审批】").append(businessName);
        if (todo.submitterName() != null && !todo.submitterName().isBlank()) {
            md.append("\n\n**提交人**：").append(todo.submitterName().trim());
        }
        if (todo.departmentName() != null && !todo.departmentName().isBlank()) {
            md.append("\n\n**部门**：").append(todo.departmentName().trim());
        }
        if (todo.stepName() != null && !todo.stepName().isBlank()) {
            md.append("\n\n**当前环节**：").append(todo.stepName().trim());
        }
        md.append("\n\n请点击下方按钮打开审批页面");
        return md.toString();
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
        return url.toString();
    }

    private String buildSourceId(Long approvalInstanceId, Long stepInstanceId) {
        return "sism-approval-" + approvalInstanceId
                + (stepInstanceId == null ? "" : "-" + stepInstanceId);
    }

    private String resolveBusinessName(ApprovalTodoPush todo) {
        if (todo.businessName() != null && !todo.businessName().isBlank()) {
            return todo.businessName().trim();
        }
        return todo.entityId() == null ? "审批事项" : "业务对象#" + todo.entityId();
    }

    private static String urlencode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
