package com.sism.iam.integration.dingtalk.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DingTalkUserBindingRepository extends JpaRepository<DingTalkUserBinding, Long> {

    Optional<DingTalkUserBinding> findBySysUserId(Long sysUserId);

    Optional<DingTalkUserBinding> findByDingTalkUserId(String dingTalkUserId);

    Optional<DingTalkUserBinding> findByDingTalkUnionId(String dingTalkUnionId);
}
