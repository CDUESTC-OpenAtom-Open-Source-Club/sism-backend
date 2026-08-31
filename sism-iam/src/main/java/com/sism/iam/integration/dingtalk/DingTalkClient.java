package com.sism.iam.integration.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin DingTalk Open API client. Endpoint contracts verified against the live API
 * (2026-08): todo executorIds require unionId (not userid); executor status update
 * body is {"executorStatusList":[{"id":unionId,"isDone":true}]}.
 */
@Component
@Slf4j
public class DingTalkClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 300;

    private final DingTalkProperties properties;
    private final HttpClient httpClient;
    private volatile TokenState tokenState;

    private record TokenState(String accessToken, long expiresAtEpochMs) {}

    public record DingTalkUserDetail(
            String userid,
            String unionId,
            String name,
            String mobile
    ) {}

    public DingTalkClient(DingTalkProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .build();
    }

    public boolean isAvailable() {
        return properties.isConfigured();
    }

    public synchronized String getAccessToken() {
        TokenState state = tokenState;
        if (state != null && Instant.now().toEpochMilli() < state.expiresAtEpochMs()) {
            return state.accessToken();
        }
        if (!isAvailable()) {
            throw new DingTalkApiException("DingTalk integration is not configured");
        }
        String url = properties.getOapiBaseUrl() + "/gettoken?appkey="
                + urlencode(properties.getAppKey())
                + "&appsecret=" + urlencode(properties.getAppSecret());
        JsonNode body = readJson(exchange(HttpRequest.newBuilder(URI.create(url)).GET().build()));
        assertLegacyOk(body, "gettoken");
        String token = requiredText(body, "access_token", "gettoken");
        long expiresIn = body.path("expires_in").asLong(7200L);
        tokenState = new TokenState(token,
                Instant.now().toEpochMilli() + Math.max(60, expiresIn - TOKEN_REFRESH_MARGIN_SECONDS) * 1000L);
        return token;
    }

    public String getUserIdByMobile(String mobile) {
        JsonNode result = legacyPost(
                "/topapi/v2/user/getbymobile",
                Map.of("mobile", mobile),
                "getbymobile");
        return result == null ? null : textOrNull(result.path("userid"));
    }

    public DingTalkUserDetail getUserDetailByUserId(String dingTalkUserId) {
        JsonNode result = legacyPost(
                "/topapi/v2/user/get",
                Map.of("userid", dingTalkUserId),
                "user/get");
        if (result == null) {
            return null;
        }
        return new DingTalkUserDetail(
                textOrNull(result.path("userid")),
                textOrNull(result.path("unionid")),
                textOrNull(result.path("name")),
                textOrNull(result.path("mobile"))
        );
    }

    /**
     * Exchanges an H5 JSAPI authCode for the DingTalk userid of the logged-in user.
     */
    public String getUserIdByAuthCode(String authCode) {
        JsonNode result = legacyPost(
                "/topapi/v2/user/getuserinfo",
                Map.of("code", authCode),
                "getuserinfo");
        return result == null ? null : textOrNull(result.path("userid"));
    }

    public String createTodoTask(
            String unionId,
            String subject,
            String description,
            String detailUrl,
            List<String> executorUnionIds,
            String sourceId,
            int priority
    ) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("subject", subject);
        payload.put("content", description == null ? "" : description);
        // 钉钉 v1.0 待办的跳转链接必须是对象结构；纯字符串会被静默丢弃，待办将无法点击跳转
        payload.put("detailUrl", Map.of(
                "appUrl", detailUrl == null ? "" : detailUrl,
                "pcUri", detailUrl == null ? "" : detailUrl
        ));
        payload.put("executorIds", executorUnionIds);
        payload.put("sourceId", sourceId);
        payload.put("priorities", priority);
        JsonNode body = modernJson(
                HttpRequest.newBuilder()
                        .uri(URI.create(properties.getApiBaseUrl() + "/v1.0/todo/users/"
                                + urlencode(unionId) + "/tasks"))
                        .header("Content-Type", "application/json")
                        .POST(jsonBody(payload))
                        .build(),
                "todo-create");
        return requiredText(body, "id", "todo-create");
    }

    public boolean completeTodoTask(String unionId, String taskId, List<String> executorUnionIds) {
        List<Map<String, Object>> statusList = executorUnionIds.stream()
                .map(id -> Map.<String, Object>of("id", id, "isDone", true))
                .toList();
        JsonNode body = modernJson(
                HttpRequest.newBuilder()
                        .uri(URI.create(properties.getApiBaseUrl() + "/v1.0/todo/users/"
                                + urlencode(unionId) + "/tasks/" + urlencode(taskId)
                                + "/executorStatus"))
                        .header("Content-Type", "application/json")
                        .PUT(jsonBody(Map.of("executorStatusList", statusList)))
                        .build(),
                "todo-complete");
        return body.path("result").asBoolean(false);
    }

    /**
     * 以应用机器人身份向单个用户发送自定义审批卡片（ActionCard，单个跳转按钮）。
     * 返回 processQueryKey 供后续撤回；已实测：卡片点击按钮在内置浏览器打开 H5。
     */
    public String sendApprovalCard(
            String dingTalkUserId,
            String title,
            String markdown,
            String buttonTitle,
            String detailUrl
    ) {
        Map<String, Object> msgParam = Map.of(
                "title", title,
                "text", markdown,
                "singleTitle", buttonTitle,
                "singleURL", detailUrl
        );
        Map<String, Object> payload = Map.of(
                "robotCode", properties.resolveRobotCode(),
                "userIds", List.of(dingTalkUserId),
                "msgKey", "sampleActionCard",
                "msgParam", serializeMsgParam(msgParam)
        );
        JsonNode body = modernJson(
                HttpRequest.newBuilder()
                        .uri(URI.create(properties.getApiBaseUrl() + "/v1.0/robot/oToMessages/batchSend"))
                        .header("Content-Type", "application/json")
                        .POST(jsonBody(payload))
                        .build(),
                "card-send");
        return requiredText(body, "processQueryKey", "card-send");
    }

    /**
     * 批量撤回机器人卡片（processQueryKey 发送后 24 小时内有效）。
     */
    public boolean recallCards(List<String> processQueryKeys) {
        if (processQueryKeys == null || processQueryKeys.isEmpty()) {
            return true;
        }
        JsonNode body = modernJson(
                HttpRequest.newBuilder()
                        .uri(URI.create(properties.getApiBaseUrl() + "/v1.0/robot/otoMessages/batchRecall"))
                        .header("Content-Type", "application/json")
                        .POST(jsonBody(Map.of(
                                "robotCode", properties.resolveRobotCode(),
                                "processQueryKeys", processQueryKeys)))
                        .build(),
                "card-recall");
        return body.path("successResult").isArray()
                && body.path("failedResult").isObject()
                && body.path("failedResult").isEmpty();
    }

    private JsonNode legacyPost(String path, Map<String, Object> payload, String operation) {
        JsonNode body = readJson(exchange(HttpRequest.newBuilder()
                .uri(URI.create(properties.getOapiBaseUrl() + path
                        + "?access_token=" + urlencode(getAccessToken())))
                .header("Content-Type", "application/json")
                .POST(jsonBody(payload))
                .build()));
        assertLegacyOk(body, operation);
        return body.path("result").isMissingNode() ? null : body.path("result");
    }

    private static String serializeMsgParam(Map<String, Object> msgParam) {
        try {
            return MAPPER.writeValueAsString(msgParam);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to serialize DingTalk card msgParam", ex);
        }
    }

    private JsonNode modernJson(HttpRequest request, String operation) {
        HttpRequest authorized = HttpRequest.newBuilder(request, (k, v) -> true)
                .header("x-acs-dingtalk-access-token", getAccessToken())
                .build();
        return readJson(exchange(authorized));
    }

    private HttpResponse<String> exchange(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String snippet = response.body() == null ? "" : response.body();
                if (snippet.length() > 300) {
                    snippet = snippet.substring(0, 300);
                }
                throw new DingTalkApiException("DingTalk API HTTP " + response.statusCode()
                        + " for " + request.uri() + ": " + snippet);
            }
            return response;
        } catch (IOException ex) {
            throw new DingTalkApiException("DingTalk API call failed for " + request.uri(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DingTalkApiException("DingTalk API call interrupted", ex);
        }
    }

    private static HttpRequest.BodyPublisher jsonBody(Map<String, Object> payload) {
        try {
            return HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload),
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to serialize DingTalk request payload", ex);
        }
    }

    private static JsonNode readJson(HttpResponse<String> response) {
        try {
            return MAPPER.readTree(response.body() == null ? "" : response.body());
        } catch (IOException ex) {
            throw new DingTalkApiException("DingTalk API returned non-JSON payload", ex);
        }
    }

    private static void assertLegacyOk(JsonNode body, String operation) {
        int errcode = body.path("errcode").asInt(0);
        if (errcode != 0) {
            throw new DingTalkApiException("DingTalk API " + operation + " failed: errcode="
                    + errcode + ", errmsg=" + body.path("errmsg").asText(""));
        }
    }

    private static String requiredText(JsonNode body, String field, String operation) {
        String value = textOrNull(body.path(field));
        if (value == null) {
            throw new DingTalkApiException("DingTalk API " + operation + " response missing field: " + field);
        }
        return value;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText("");
        return text.isBlank() ? null : text;
    }

    private static String urlencode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
