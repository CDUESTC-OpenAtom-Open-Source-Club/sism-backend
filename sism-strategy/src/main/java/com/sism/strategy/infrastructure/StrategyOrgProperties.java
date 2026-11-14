package com.sism.strategy.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sism.strategy.org")
public class StrategyOrgProperties {

    /**
     * 2026-11-05（minor 口径统一）：战略发展部 org id 的兜底值。
     *
     * <p>为什么要有兜底：配置项 {@code sism.strategy.org.strategy-org-id} 被显式置空时，
     * 各处若各写各的取法，就会出现「controller 按空值放行、service 按 35 拦截」这种
     * 互相打架的判定——同一条业务规则在两层给出相反结论。统一到本类后，
     * 全仓库只有 {@link #resolveStrategyOrgId()} 一个事实源。
     */
    public static final Long DEFAULT_STRATEGY_ORG_ID = 35L;

    /**
     * Strategy organization ID used for strategy-to-functional plan creation.
     */
    private Long strategyOrgId = DEFAULT_STRATEGY_ORG_ID;

    /**
     * System admin organization ID used by integrity checks.
     */
    private Long systemAdminOrgId = DEFAULT_STRATEGY_ORG_ID;

    /**
     * 2026-11-05（minor 口径统一）：战略发展部 org id 的<b>唯一</b>取法。
     *
     * <p>配置存在则用配置值，配置缺失或被显式置空则兜底 35——与导入侧
     * {@code BusinessImportApplicationService#strategyOrgId()}、计划侧
     * {@code PlanApplicationService#strategyOrgId()}、控制器侧
     * {@code IndicatorController} 的组织维度校验保持同一口径。
     */
    public Long resolveStrategyOrgId() {
        return strategyOrgId == null ? DEFAULT_STRATEGY_ORG_ID : strategyOrgId;
    }
}
