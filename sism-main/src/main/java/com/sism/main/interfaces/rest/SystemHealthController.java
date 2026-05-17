package com.sism.main.interfaces.rest;

import com.sism.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Compatibility endpoint used by frontend health polling.
 */
@RestController
@RequestMapping("/api/v1/actuator")
@Tag(name = "System Health", description = "前端兼容性健康检查接口")
@RequiredArgsConstructor
public class SystemHealthController {

    private final ApplicationAvailability applicationAvailability;

    @GetMapping("/health")
    @Operation(summary = "获取服务健康状态")
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        LivenessState livenessState = applicationAvailability.getLivenessState();
        ReadinessState readinessState = applicationAvailability.getReadinessState();
        boolean up = livenessState == LivenessState.CORRECT;
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "status", up ? "UP" : "DOWN",
                "service", "sism-backend",
                "livenessState", livenessState.name(),
                "readinessState", readinessState.name(),
                "timestamp", Instant.now().toString()
        )));
    }
}
