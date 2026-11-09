package com.sism.strategy.application;

import com.sism.shared.application.dto.CurrentUser;
import com.sism.strategy.infrastructure.StrategyOrgProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 2026-11-05（R6/R7/R8/R9）：指标写入的<b>组织维度</b>授权守卫。
 *
 * <p>为什么不能只看角色码：仓库里存在 27 个 {@code *_demo} 账号持有
 * {@code ROLE_STRATEGY_DEPT_HEAD} 但并不在战略发展部；只靠 {@code @PreAuthorize} 的角色码，
 * 它们仍能以战略部的名义写指标、发起分发。
 *
 * <p>为什么不能按角色名一刀切：需求 3 的口径是「战略部下发的数据在职能部门<b>只读</b>」，
 * 同时「职能部门可改/撤回自己部门的指标不受影响」。所以判定基准只能是
 * <b>指标当前的归属组织</b>，而不是操作者的角色：
 * <ul>
 *   <li>指标归属组织 = 战略发展部 → 操作者必须在战略发展部（系统管理员豁免）；</li>
 *   <li>指标归属组织 = 其它组织 → 放行（职能部门维护本部门指标是正常业务，
 *       且历史上「A 部门改 B 部门指标」并未被限制，本次改造不扩大改动面）。</li>
 * </ul>
 *
 * <p>调用方必须回查<b>指标的真实归属组织</b>再调用本守卫；绝不能用可空的请求入参
 * （例如批量分发里的 {@code ownerOrgId}）当组织维度判据——那样等于没校验。
 */
@Component
@RequiredArgsConstructor
public class StrategyOrgWriteGuard {

    /** 2026-11-05：写入「战略发展部名下」指标时的操作者归属校验文案 */
    public static final String STRATEGY_ORG_WRITE_REQUIRED =
            "只有战略发展部成员可以维护战略发展部名下指标";

    /** 系统管理员角色码：运维代操作的唯一合法入口，与导入侧豁免口径一致 */
    private static final String SYSTEM_ADMIN_ROLE_CODE = "ROLE_SYSTEM_ADMIN";

    private final StrategyOrgProperties strategyOrgProperties;

    /**
     * 战略发展部 org id（配置缺失时兜底 35，与 service 层同一口径）。
     */
    public Long strategyOrgId() {
        return strategyOrgProperties.resolveStrategyOrgId();
    }

    public boolean isStrategyOrg(Long orgId) {
        return orgId != null && Objects.equals(orgId, strategyOrgId());
    }

    /**
     * 便捷重载：直接用登录态判定（系统管理员豁免由 authorities 判定）。
     *
     * @param operator  当前登录用户（可为 null，表示未登录）
     * @param ownerOrgId 指标<b>真实</b>的归属组织 id
     */
    public void assertOwnerOrgWriteAllowed(CurrentUser operator, Long ownerOrgId) {
        Long operatorOrgId = operator == null ? null : operator.getOrgId();
        assertOwnerOrgWriteAllowed(operatorOrgId, isSystemAdmin(operator), ownerOrgId);
    }

    /**
     * 指标写入的组织维度授权判定（详见类注释的口径）。
     *
     * @param operatorOrgId      操作者所属组织 id
     * @param operatorSystemAdmin 操作者是否系统管理员（豁免）
     * @param ownerOrgId         指标<b>真实</b>的归属组织 id
     */
    public void assertOwnerOrgWriteAllowed(Long operatorOrgId,
                                           boolean operatorSystemAdmin,
                                           Long ownerOrgId) {
        if (operatorSystemAdmin) {
            return;
        }
        if (!isStrategyOrg(ownerOrgId)) {
            // 归属组织不是战略发展部 → 保持既有行为，不误伤职能部门维护本部门指标
            return;
        }
        if (!isStrategyOrg(operatorOrgId)) {
            throw new SecurityException(STRATEGY_ORG_WRITE_REQUIRED);
        }
    }

    private boolean isSystemAdmin(CurrentUser operator) {
        return operator != null
                && operator.getAuthorities() != null
                && operator.getAuthorities().stream()
                .anyMatch(authority -> SYSTEM_ADMIN_ROLE_CODE.equals(authority.getAuthority()));
    }
}
