package com.sism.iam.interfaces.rest;

import com.sism.iam.application.dto.LoginResponse;
import com.sism.iam.application.service.AuthService;
import com.sism.iam.integration.dingtalk.DingTalkProperties;
import com.sism.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 钉钉免登入口：H5 微应用内用免登码换取本系统登录态。
 */
@RestController
@RequestMapping("/api/v1/auth/dingtalk")
@RequiredArgsConstructor
@Tag(name = "钉钉免登", description = "钉钉 H5 微应用免登相关接口")
public class DingTalkAuthController {

    private final AuthService authService;
    private final DingTalkProperties dingTalkProperties;

    public record DingTalkLoginRequest(@NotBlank(message = "免登码不能为空") String authCode) {
    }

    @PostMapping("/login")
    @Operation(summary = "钉钉免登")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody DingTalkLoginRequest request) {
        LoginResponse response = authService.loginByDingTalk(request.authCode());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/status")
    @Operation(summary = "钉钉集成开关状态")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "enabled", dingTalkProperties.isEnabled(),
                "configured", dingTalkProperties.isConfigured(),
                "corpId", dingTalkProperties.getCorpId() == null ? "" : dingTalkProperties.getCorpId()
        )));
    }
}
