package com.sism.iam.integration.dingtalk;

import com.sism.iam.integration.dingtalk.domain.DingTalkUserBinding;
import com.sism.iam.integration.dingtalk.domain.DingTalkUserBindingRepository;
import com.sism.iam.domain.user.User;
import com.sism.iam.domain.user.UserRepository;
import com.sism.shared.domain.exception.AuthenticationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Resolves DingTalk logins to SISM accounts. Policy A (no auto-registration):
 * a DingTalk user must match an existing SISM account (by mobile, or by unique
 * real-name fallback) before access is granted; the binding row is created
 * automatically on first match.
 *
 * Bindings are also provisioned proactively ("绑定前置") from the SISM side:
 * sys_user.phone → DingTalk userId via getbymobile, so todo pushes work before
 * the user ever opens the H5 app.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DingTalkUserBindingService {

    private final DingTalkClient dingTalkClient;
    private final DingTalkUserBindingRepository bindingRepository;
    private final UserRepository userRepository;

    @Transactional
    public DingTalkUserBinding resolveByAuthCode(String authCode) {
        if (authCode == null || authCode.isBlank()) {
            throw new IllegalArgumentException("authCode is required");
        }
        if (!dingTalkClient.isAvailable()) {
            throw new IllegalStateException("钉钉集成未启用");
        }

        String dingTalkUserId;
        try {
            dingTalkUserId = dingTalkClient.getUserIdByAuthCode(authCode.trim());
        } catch (DingTalkApiException ex) {
            log.warn("DingTalk getuserinfo failed: {}", ex.getMessage());
            throw new AuthenticationException("DINGTALK_AUTH_FAILED", "钉钉免登失败，请重新进入应用");
        }
        if (dingTalkUserId == null) {
            throw new AuthenticationException("DINGTALK_AUTH_FAILED", "钉钉免登失败，请重新进入应用");
        }

        Optional<DingTalkUserBinding> existing = bindingRepository.findByDingTalkUserId(dingTalkUserId);
        if (existing.isPresent()) {
            return existing.get();
        }

        return autoBind(dingTalkUserId);
    }

    @Transactional
    public DingTalkUserBinding autoBind(String dingTalkUserId) {
        DingTalkClient.DingTalkUserDetail detail;
        try {
            detail = dingTalkClient.getUserDetailByUserId(dingTalkUserId);
        } catch (DingTalkApiException ex) {
            log.warn("DingTalk user/get failed for {}: {}", dingTalkUserId, ex.getMessage());
            throw new AuthenticationException("DINGTALK_AUTH_FAILED", "钉钉免登失败，请重新进入应用");
        }
        if (detail == null) {
            throw new AuthenticationException("DINGTALK_AUTH_FAILED", "钉钉免登失败，请重新进入应用");
        }

        // 首选手机号匹配（依赖通讯录手机号权限）；未开通时退化为"钉钉姓名 ↔ 系统姓名唯一匹配"，
        // 仍只允许绑定到管理员预先开通的账号（方案A），歧义或无匹配一律拒绝。
        User user = null;
        if (detail.mobile() != null && !detail.mobile().isBlank()) {
            user = userRepository.findByPhone(detail.mobile().trim())
                    .filter(User::getIsActive)
                    .orElse(null);
        }
        if (user == null && detail.name() != null && !detail.name().isBlank()) {
            List<User> nameMatches = userRepository.findAll().stream()
                    .filter(User::getIsActive)
                    .filter(u -> detail.name().trim().equals(u.getRealName()))
                    .toList();
            if (nameMatches.size() == 1) {
                user = nameMatches.get(0);
                log.info("DingTalk user {} bound by unique real-name match to SISM user {}",
                        dingTalkUserId, user.getId());
            }
        }
        if (user == null) {
            throw new AuthenticationException(
                    "DINGTALK_UNBOUND",
                    "尚未开通本系统账号，请联系系统管理员开通");
        }

        return upsertBinding(user, dingTalkUserId, detail);
    }

    /**
     * 确保指定系统用户存在钉钉绑定：已有则直接返回，否则用其手机号反查钉钉 userId 补建。
     * 用于待办推送前的"绑定前置"，使用户无需先打开过 H5 应用即可收到钉钉待办。
     */
    @Transactional
    public Optional<DingTalkUserBinding> ensureBinding(Long sysUserId) {
        if (sysUserId == null) {
            return Optional.empty();
        }
        Optional<DingTalkUserBinding> existing = bindingRepository.findBySysUserId(sysUserId);
        if (existing.isPresent()) {
            return existing;
        }

        User user = userRepository.findById(sysUserId)
                .filter(User::getIsActive)
                .orElse(null);
        if (user == null || user.getPhone() == null || user.getPhone().isBlank()) {
            return Optional.empty();
        }

        try {
            String dingTalkUserId = dingTalkClient.getUserIdByMobile(user.getPhone().trim());
            if (dingTalkUserId == null) {
                log.debug("Phone {} has no DingTalk member, skip binding for user {}",
                        user.getPhone(), sysUserId);
                return Optional.empty();
            }
            DingTalkClient.DingTalkUserDetail detail =
                    dingTalkClient.getUserDetailByUserId(dingTalkUserId);
            DingTalkUserBinding binding = upsertBinding(user, dingTalkUserId, detail);
            log.info("Pre-created DingTalk binding for SISM user {} via phone lookup", sysUserId);
            return Optional.of(binding);
        } catch (DingTalkApiException ex) {
            log.warn("Failed to pre-create DingTalk binding for user {}: {}",
                    sysUserId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 全量同步：为所有已配置手机号的活跃用户补建/刷新钉钉绑定。定时执行。
     *
     * @return 本次新建或更新的绑定数量
     */
    @Transactional
    public int syncAllBindings() {
        if (!dingTalkClient.isAvailable()) {
            return 0;
        }
        List<User> candidates = userRepository.findAll().stream()
                .filter(User::getIsActive)
                .filter(u -> u.getPhone() != null && !u.getPhone().isBlank())
                .toList();

        int changed = 0;
        for (User user : candidates) {
            try {
                String dingTalkUserId = dingTalkClient.getUserIdByMobile(user.getPhone().trim());
                if (dingTalkUserId == null) {
                    continue;
                }
                DingTalkUserBinding existing = bindingRepository.findBySysUserId(user.getId()).orElse(null);
                if (existing != null && dingTalkUserId.equals(existing.getDingTalkUserId())) {
                    continue;
                }
                DingTalkClient.DingTalkUserDetail detail =
                        dingTalkClient.getUserDetailByUserId(dingTalkUserId);
                upsertBinding(user, dingTalkUserId, detail);
                changed++;
            } catch (DingTalkApiException ex) {
                log.warn("Binding sync failed for user {}: {}", user.getId(), ex.getMessage());
            }
        }
        if (changed > 0) {
            log.info("DingTalk binding sync completed, {} bindings created/updated", changed);
        }
        return changed;
    }

    private DingTalkUserBinding upsertBinding(
            User user, String dingTalkUserId, DingTalkClient.DingTalkUserDetail detail) {
        DingTalkUserBinding binding = bindingRepository.findBySysUserId(user.getId())
                .orElseGet(DingTalkUserBinding::new);
        binding.setSysUserId(user.getId());
        binding.setDingTalkUserId(dingTalkUserId);
        if (detail != null) {
            if (detail.unionId() != null) {
                binding.setDingTalkUnionId(detail.unionId());
            }
            if (detail.name() != null) {
                binding.setDingTalkName(detail.name());
            }
            if (detail.mobile() != null) {
                binding.setMobile(detail.mobile());
            }
        }
        if (binding.getMobile() == null && user.getPhone() != null) {
            binding.setMobile(user.getPhone());
        }
        DingTalkUserBinding saved = bindingRepository.save(binding);
        log.info("Bound DingTalk user {} to SISM user {}",
                dingTalkUserId, user.getId());
        return saved;
    }

    @Transactional
    public Optional<DingTalkUserBinding> findBySysUserId(Long sysUserId) {
        return bindingRepository.findBySysUserId(sysUserId);
    }

    /**
     * 为缺失 unionId 的旧绑定回填 unionId（待办接口按 unionId 寻址）。
     * 调用方传入的可能是脱管实体，这里按 sysUserId 重查后落库。
     */
    @Transactional
    public void refreshUnionId(DingTalkUserBinding detached) {
        DingTalkClient.DingTalkUserDetail detail =
                dingTalkClient.getUserDetailByUserId(detached.getDingTalkUserId());
        if (detail == null || detail.unionId() == null) {
            return;
        }
        bindingRepository.findBySysUserId(detached.getSysUserId()).ifPresent(managed -> {
            managed.setDingTalkUnionId(detail.unionId());
            managed.setUpdatedAt(java.time.LocalDateTime.now());
            bindingRepository.save(managed);
            detached.setDingTalkUnionId(detail.unionId());
        });
    }
}
