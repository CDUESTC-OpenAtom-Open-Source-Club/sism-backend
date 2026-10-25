package com.sism.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sism.iam.application.JwtTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SISM WebSocket Handler
 * SISM WebSocket 处理器
 *
 * Handles WebSocket connections at /ws endpoint.
 * Manages session lifecycle and message routing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SismWebSocketHandler extends TextWebSocketHandler {

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final JwtTokenService jwtTokenService;
    private final Map<String, Set<WebSocketSession>> sessionsByUserId = new ConcurrentHashMap<>();
    private final Map<String, String> userIdBySessionId = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String userId = extractUserId(session);
        if (userId == null || userId.isBlank()) {
            log.warn("Rejecting WebSocket connection without valid token: sessionId={}", session.getId());
            session.close(CloseStatus.BAD_DATA.withReason("Missing or invalid token"));
            return;
        }

        sessionsByUserId
                .computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet())
                .add(session);
        userIdBySessionId.put(session.getId(), userId);
        log.info("WebSocket connected: sessionId={}, userId={}, remoteAddress={}",
                session.getId(), userId, session.getRemoteAddress());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("Received WebSocket message from session {}: {}", session.getId(), payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        unregisterSession(session);
        log.info("WebSocket disconnected: sessionId={}, status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error: sessionId={}, error={}",
                session.getId(), exception.getMessage());
        unregisterSession(session);
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    /**
     * 2026-10-24 跨窗口数据同步：广播型推送（DATA_CHANGED）。
     * 数据变更事件不带敏感内容（仅实体类型+ID），发给所有在线客户端；
     * 各客户端自行判断该实体是否在自己当前视图内，命中才做局部刷新。
     */
    public int sendToAll(NotificationMessagePayload message) {
        String payload = toJson(message);
        TextMessage textMessage = new TextMessage(payload);
        int delivered = 0;

        for (Set<WebSocketSession> sessions : sessionsByUserId.values()) {
            for (WebSocketSession session : sessions) {
                try {
                    if (session.isOpen()) {
                        synchronized (session) {
                            session.sendMessage(textMessage);
                        }
                        delivered++;
                    } else {
                        unregisterSession(session);
                    }
                } catch (IOException e) {
                    log.error("Error broadcasting WebSocket message to session {}: {}",
                            session.getId(), e.getMessage());
                    unregisterSession(session);
                }
            }
        }

        return delivered;
    }

    public boolean sendToUser(String userId, NotificationMessagePayload message) {
        Set<WebSocketSession> sessions = sessionsByUserId.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            log.debug("No active WebSocket sessions for userId={}", userId);
            return false;
        }

        String payload = toJson(message);
        TextMessage textMessage = new TextMessage(payload);
        boolean delivered = false;

        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                    delivered = true;
                } else {
                    unregisterSession(session);
                }
            } catch (IOException e) {
                log.error("Error sending WebSocket notification to session {}: {}", session.getId(), e.getMessage());
                unregisterSession(session);
            }
        }

        return delivered;
    }

    /**
     * Get number of active connections
     */
    public int getActiveConnections() {
        cleanupClosedSessions();
        return userIdBySessionId.size();
    }

    public int getActiveConnections(String userId) {
        cleanupClosedSessions();
        Set<WebSocketSession> sessions = sessionsByUserId.get(userId);
        return sessions == null ? 0 : sessions.size();
    }

    @Scheduled(fixedDelayString = "${app.websocket.session-cleanup-interval-ms:300000}")
    public void cleanupClosedSessions() {
        sessionsByUserId.values().forEach(sessions -> sessions.removeIf(session -> !session.isOpen()));
        userIdBySessionId.entrySet().removeIf(entry -> {
            Set<WebSocketSession> sessions = sessionsByUserId.get(entry.getValue());
            boolean missing = sessions == null || sessions.stream().noneMatch(session -> session.getId().equals(entry.getKey()));
            if (missing && sessions != null && sessions.isEmpty()) {
                sessionsByUserId.remove(entry.getValue(), sessions);
            }
            return missing;
        });
        sessionsByUserId.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isEmpty());
    }

    private void unregisterSession(WebSocketSession session) {
        String userId = userIdBySessionId.remove(session.getId());
        if (userId == null) {
            return;
        }

        Set<WebSocketSession> sessions = sessionsByUserId.get(userId);
        if (sessions == null) {
            return;
        }

        sessions.remove(session);
        if (sessions.isEmpty()) {
            sessionsByUserId.remove(userId);
        }
    }

    private String extractUserId(WebSocketSession session) {
        if (session.getUri() == null) {
            return null;
        }

        String token = UriComponentsBuilder.fromUri(session.getUri())
                .build()
                .getQueryParams()
                .getFirst("token");
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            if (!jwtTokenService.validateToken(token)) {
                return null;
            }
            Long userId = jwtTokenService.getUserIdFromToken(token);
            return userId == null ? null : String.valueOf(userId);
        } catch (Exception e) {
            log.warn("WebSocket token validation failed: {}", e.getMessage());
            return null;
        }
    }

    private String toJson(NotificationMessagePayload message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize WebSocket notification payload", e);
        }
    }

    public record NotificationMessagePayload(
            String type,
            String title,
            String content,
            String entityType,
            Long entityId,
            Long approvalInstanceId,
            String stepName,
            String timestamp
    ) {
    }
}
