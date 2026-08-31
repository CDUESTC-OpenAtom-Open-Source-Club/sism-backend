package com.sism.iam.integration.dingtalk.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DingTalkTodoTaskRepository extends JpaRepository<DingTalkTodoTask, Long> {

    List<DingTalkTodoTask> findByApprovalInstanceIdAndStatus(Long approvalInstanceId, String status);

    List<DingTalkTodoTask> findByApprovalInstanceIdAndStepInstanceIdAndStatus(
            Long approvalInstanceId, Long stepInstanceId, String status);

    Optional<DingTalkTodoTask> findBySourceIdAndSysUserIdAndStatus(String sourceId, Long sysUserId, String status);
}
