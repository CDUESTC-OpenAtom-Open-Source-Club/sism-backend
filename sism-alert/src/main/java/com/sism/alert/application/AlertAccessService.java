package com.sism.alert.application;

import com.sism.alert.domain.Alert;
import com.sism.alert.domain.enums.AlertSeverity;
import com.sism.alert.domain.enums.AlertStatus;
import com.sism.alert.domain.repository.AlertRepository;
import com.sism.alert.interfaces.dto.AlertStatsDTO;
import com.sism.shared.application.dto.CurrentUser;
import com.sism.shared.domain.exception.AuthorizationException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AlertAccessService - 预警访问控制与可见性判定
 * 将控制器中的仓储访问与权限过滤收口到应用层。
 */
@Service
@RequiredArgsConstructor
public class AlertAccessService {

    private final AlertRepository alertRepository;
    private final IndicatorAccessPort indicatorAccessPort;

    public Alert requireAccessibleAlert(Long alertId, Authentication authentication) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("Alert not found"));
        validateAlertAccess(alert, authentication);
        return alert;
    }

    public void ensureIndicatorAccess(Long indicatorId, Authentication authentication) {
        if (indicatorId == null || indicatorId <= 0) {
            throw new AuthorizationException("无权访问该指标");
        }
        if (!hasIndicatorAccess(indicatorId, authentication)) {
            throw new AuthorizationException("无权访问该指标");
        }
    }

    public void validateAlertAccess(Alert alert, Authentication authentication) {
        if (isAdmin(authentication)) {
            return;
        }
        if (alert == null || alert.getIndicatorId() == null) {
            throw new AuthorizationException("无权访问该预警");
        }
        if (!hasIndicatorAccess(alert.getIndicatorId(), authentication)) {
            throw new AuthorizationException("无权访问该预警");
        }
    }

    public List<Alert> filterAlertsByPermission(List<Alert> alerts, Authentication authentication) {
        if (isAdmin(authentication)) {
            return alerts;
        }
        Set<Long> accessibleIndicatorIds = resolveAccessibleIndicatorIds(authentication);
        if (accessibleIndicatorIds.isEmpty()) {
            return List.of();
        }
        return alerts.stream()
                .filter(alert -> alert != null
                        && alert.getIndicatorId() != null
                        && accessibleIndicatorIds.contains(alert.getIndicatorId()))
                .toList();
    }

    public Map<String, Long> countAlertsForCurrentOrg(Authentication authentication) {
        Set<Long> accessibleIndicatorIds = resolveAccessibleIndicatorIds(authentication);
        if (accessibleIndicatorIds.isEmpty()) {
            return zeroCounts();
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("total", alertRepository.countByIndicatorIdIn(accessibleIndicatorIds));
        counts.put("pending", alertRepository.countByIndicatorIdInAndStatus(accessibleIndicatorIds, AlertStatus.OPEN));
        counts.put("triggered", alertRepository.countByIndicatorIdInAndStatus(accessibleIndicatorIds, AlertStatus.IN_PROGRESS));
        counts.put("resolved", alertRepository.countByIndicatorIdInAndStatus(accessibleIndicatorIds, AlertStatus.RESOLVED));
        return counts;
    }

    public AlertStatsDTO buildAlertStatsForCurrentOrg(Authentication authentication) {
        Set<Long> accessibleIndicatorIds = resolveAccessibleIndicatorIds(authentication);
        if (accessibleIndicatorIds.isEmpty()) {
            return emptyStats();
        }

        Map<String, Long> countBySeverity = toSeverityCountMap(alertRepository.countOpenBySeverityForIndicators(accessibleIndicatorIds));
        long totalOpen = countBySeverity.values().stream().mapToLong(Long::longValue).sum();

        return new AlertStatsDTO(totalOpen, countBySeverity);
    }

    public List<Alert> getAccessibleAlerts(Authentication authentication) {
        if (isAdmin(authentication)) {
            return alertRepository.findAll();
        }
        Set<Long> accessibleIndicatorIds = resolveAccessibleIndicatorIds(authentication);
        if (accessibleIndicatorIds.isEmpty()) {
            return List.of();
        }
        return alertRepository.findByIndicatorIdIn(accessibleIndicatorIds);
    }

    public Page<Alert> getAccessibleAlerts(Authentication authentication, Pageable pageable) {
        if (isAdmin(authentication)) {
            return alertRepository.findAll(pageable);
        }
        Set<Long> accessibleIndicatorIds = resolveAccessibleIndicatorIds(authentication);
        if (accessibleIndicatorIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return alertRepository.findByIndicatorIdIn(accessibleIndicatorIds, pageable);
    }

    public List<Alert> getAccessibleAlertsByStatus(String status, Authentication authentication) {
        AlertStatus normalizedStatus = Alert.normalizeStatus(status);
        if (normalizedStatus == null) {
            return List.of();
        }
        if (isAdmin(authentication)) {
            return alertRepository.findByStatus(normalizedStatus);
        }
        Set<Long> accessibleIndicatorIds = resolveAccessibleIndicatorIds(authentication);
        if (accessibleIndicatorIds.isEmpty()) {
            return List.of();
        }
        return alertRepository.findByIndicatorIdInAndStatus(accessibleIndicatorIds, normalizedStatus);
    }

    public Page<Alert> getAccessibleAlertsByStatus(String status, Authentication authentication, Pageable pageable) {
        AlertStatus normalizedStatus = Alert.normalizeStatus(status);
        if (normalizedStatus == null) {
            return Page.empty(pageable);
        }
        if (isAdmin(authentication)) {
            return alertRepository.findByStatus(normalizedStatus, pageable);
        }
        Set<Long> accessibleIndicatorIds = resolveAccessibleIndicatorIds(authentication);
        if (accessibleIndicatorIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return alertRepository.findByIndicatorIdInAndStatus(accessibleIndicatorIds, normalizedStatus, pageable);
    }

    public List<Alert> getAccessibleAlertsBySeverity(String severity, Authentication authentication) {
        AlertSeverity normalizedSeverity = AlertSeverity.normalize(severity);
        if (normalizedSeverity == null) {
            return List.of();
        }
        if (isAdmin(authentication)) {
            return alertRepository.findBySeverity(normalizedSeverity);
        }
        Set<Long> accessibleIndicatorIds = resolveAccessibleIndicatorIds(authentication);
        if (accessibleIndicatorIds.isEmpty()) {
            return List.of();
        }
        return alertRepository.findByIndicatorIdInAndSeverity(accessibleIndicatorIds, normalizedSeverity);
    }

    public Page<Alert> getAccessibleAlertsBySeverity(String severity, Authentication authentication, Pageable pageable) {
        AlertSeverity normalizedSeverity = AlertSeverity.normalize(severity);
        if (normalizedSeverity == null) {
            return Page.empty(pageable);
        }
        if (isAdmin(authentication)) {
            return alertRepository.findBySeverity(normalizedSeverity, pageable);
        }
        Set<Long> accessibleIndicatorIds = resolveAccessibleIndicatorIds(authentication);
        if (accessibleIndicatorIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return alertRepository.findByIndicatorIdInAndSeverity(accessibleIndicatorIds, normalizedSeverity, pageable);
    }

    public Page<Alert> searchAccessibleAlerts(
            String status,
            String severity,
            Authentication authentication,
            Pageable pageable
    ) {
        AlertStatus normalizedStatus = Alert.normalizeStatus(status);
        AlertSeverity normalizedSeverity = AlertSeverity.normalize(severity);

        if (hasText(status) && normalizedStatus == null) {
            return Page.empty(pageable);
        }
        if (hasText(severity) && normalizedSeverity == null) {
            return Page.empty(pageable);
        }
        if (normalizedStatus == null && normalizedSeverity == null) {
            return getAccessibleAlerts(authentication, pageable);
        }
        if (normalizedStatus != null && normalizedSeverity == null) {
            return getAccessibleAlertsByStatus(normalizedStatus.name(), authentication, pageable);
        }
        if (normalizedStatus == null) {
            return getAccessibleAlertsBySeverity(normalizedSeverity.name(), authentication, pageable);
        }

        if (isAdmin(authentication)) {
            return alertRepository.findByStatusAndSeverity(normalizedStatus, normalizedSeverity, pageable);
        }
        Set<Long> accessibleIndicatorIds = resolveAccessibleIndicatorIds(authentication);
        if (accessibleIndicatorIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return alertRepository.findByIndicatorIdInAndStatusAndSeverity(
                accessibleIndicatorIds,
                normalizedStatus,
                normalizedSeverity,
                pageable
        );
    }

    public List<Alert> getAccessibleAlertsByIndicator(Long indicatorId, Authentication authentication) {
        if (isAdmin(authentication)) {
            return alertRepository.findByIndicatorId(indicatorId);
        }
        ensureIndicatorAccess(indicatorId, authentication);
        return alertRepository.findByIndicatorId(indicatorId);
    }

    public Page<Alert> getAccessibleAlertsByIndicator(Long indicatorId, Authentication authentication, Pageable pageable) {
        if (isAdmin(authentication)) {
            return alertRepository.findByIndicatorId(indicatorId, pageable);
        }
        ensureIndicatorAccess(indicatorId, authentication);
        return alertRepository.findByIndicatorId(indicatorId, pageable);
    }

    public List<Alert> getAccessibleUnresolvedAlerts(Authentication authentication) {
        List<AlertStatus> unresolvedStatuses = List.of(AlertStatus.OPEN, AlertStatus.IN_PROGRESS);
        if (isAdmin(authentication)) {
            return alertRepository.findByStatusIn(unresolvedStatuses);
        }
        Set<Long> accessibleIndicatorIds = resolveAccessibleIndicatorIds(authentication);
        if (accessibleIndicatorIds.isEmpty()) {
            return List.of();
        }
        return alertRepository.findByIndicatorIdInAndStatusIn(accessibleIndicatorIds, unresolvedStatuses);
    }

    public Page<Alert> getAccessibleUnresolvedAlerts(Authentication authentication, Pageable pageable) {
        List<AlertStatus> unresolvedStatuses = List.of(AlertStatus.OPEN, AlertStatus.IN_PROGRESS);
        if (isAdmin(authentication)) {
            return alertRepository.findByStatusIn(unresolvedStatuses, pageable);
        }
        Set<Long> accessibleIndicatorIds = resolveAccessibleIndicatorIds(authentication);
        if (accessibleIndicatorIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return alertRepository.findByIndicatorIdInAndStatusIn(accessibleIndicatorIds, unresolvedStatuses, pageable);
    }

    /**
     * 从给定的指标ID集合中筛出当前用户有权访问的部分。
     * 批量查询场景不应"全有或全无"：列表中混入无权指标时，只返回有权的部分即可。
     */
    public List<Long> filterAccessibleIndicatorIds(List<Long> indicatorIds, Authentication authentication) {
        if (indicatorIds == null || indicatorIds.isEmpty()) {
            return List.of();
        }
        if (isAdmin(authentication)) {
            return indicatorIds.stream().filter(id -> id != null && id > 0).distinct().toList();
        }

        Set<Long> accessibleIndicatorIds = resolveAccessibleIndicatorIds(authentication);
        if (accessibleIndicatorIds.isEmpty()) {
            return List.of();
        }
        return indicatorIds.stream()
                .filter(id -> id != null && id > 0 && accessibleIndicatorIds.contains(id))
                .distinct()
                .toList();
    }

    private boolean hasIndicatorAccess(Long indicatorId, Authentication authentication) {
        if (isAdmin(authentication)) {
            return true;
        }

        Set<Long> accessibleIndicatorIds = resolveAccessibleIndicatorIds(authentication);
        return accessibleIndicatorIds.contains(indicatorId);
    }

    private Set<Long> resolveAccessibleIndicatorIds(Authentication authentication) {
        CurrentUser currentUser = requireCurrentUser(authentication);
        if (currentUser.getOrgId() == null) {
            return Set.of();
        }

        Set<Long> indicatorIds = new LinkedHashSet<>();
        indicatorAccessPort.findAccessibleIndicatorIds(currentUser.getOrgId()).stream()
                .filter(Objects::nonNull)
                .forEach(indicatorIds::add);
        return indicatorIds;
    }

    private Set<Long> resolveAllIndicatorIds() {
        return indicatorAccessPort.findAllIndicatorIds();
    }

    private Map<String, Long> zeroCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("total", 0L);
        counts.put("pending", 0L);
        counts.put("triggered", 0L);
        counts.put("resolved", 0L);
        return counts;
    }

    private AlertStatsDTO emptyStats() {
        return new AlertStatsDTO(0L, Map.of(
                "CRITICAL", 0L,
                "WARNING", 0L,
                "INFO", 0L
        ));
    }

    private Map<String, Long> toSeverityCountMap(List<AlertRepository.SeverityCount> counts) {
        Map<String, Long> countBySeverity = new LinkedHashMap<>();
        countBySeverity.put(AlertSeverity.CRITICAL.name(), 0L);
        countBySeverity.put(AlertSeverity.WARNING.name(), 0L);
        countBySeverity.put(AlertSeverity.INFO.name(), 0L);
        for (AlertRepository.SeverityCount count : counts) {
            if (count.getSeverity() != null) {
                countBySeverity.put(count.getSeverity().name(), count.getCount());
            }
        }
        return countBySeverity;
    }

    public boolean isAdmin(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(authority -> "ROLE_VICE_PRESIDENT".equals(authority)
                        || "ROLE_STRATEGY_DEPT_HEAD".equals(authority));
    }

    private CurrentUser requireCurrentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser currentUser)) {
            throw new AuthorizationException("无法获取当前用户信息，请联系管理员");
        }
        return currentUser;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
