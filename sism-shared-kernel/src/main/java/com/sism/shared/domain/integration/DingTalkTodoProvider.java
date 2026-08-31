package com.sism.shared.domain.integration;

/**
 * DingTalk todo push port implemented by the IAM module so that workflow/strategy
 * modules can mirror approval todos into DingTalk without depending on sism-iam.
 */
public interface DingTalkTodoProvider {

    void pushApprovalTodo(ApprovalTodoPush todo);

    void completeStepTodos(Long approvalInstanceId, Long stepInstanceId);

    void completeInstanceTodos(Long approvalInstanceId);

    record ApprovalTodoPush(
            Long sysUserId,
            Long approvalInstanceId,
            Long stepInstanceId,
            String entityType,
            Long entityId,
            String businessName,
            String stepName,
            String submitterName,
            String departmentName
    ) {
        public String entityTypeNormalized() {
            return entityType == null ? null : entityType.trim().toUpperCase();
        }
    }
}
