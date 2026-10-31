package com.sism.exception;

import com.sism.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldConvertMethodArgumentValidationErrorsWithoutFieldCastFailure() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/items");
        request.addHeader("X-Request-ID", "req-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("sampleEndpoint", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new SamplePayload(), "samplePayload");
        bindingResult.addError(new ObjectError("samplePayload", "payload is invalid"));
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        var response = handler.handleValidationException(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertFalse(body.isSuccess());
        assertEquals(1001, body.getCode());
        assertEquals("参数验证失败", body.getMessage());
    }

    @Test
    void shouldMapSharedBusinessExceptionsToBadRequestResponse() {
        var response = handler.handleSharedBusinessException(
                new com.sism.shared.domain.exception.BusinessException("BUSINESS_ERROR", "shared failure"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ApiResponse<Void> body = response.getBody();
        assertNotNull(body);
        assertEquals(400, body.getCode());
        assertEquals("请求参数不合法", body.getMessage());
    }

    @Test
    void shouldMapLegacyBusinessExceptionThroughSharedHandler() {
        var response = handler.handleSharedBusinessException(
                new com.sism.exception.BusinessException(404, "legacy not found"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        ApiResponse<Void> body = response.getBody();
        assertNotNull(body);
        assertEquals(1003, body.getCode());
        assertEquals("资源不存在", body.getMessage());
    }

    @Test
    void shouldMapLegacyResourceNotFoundExceptionThroughSharedHandler() {
        var response = handler.handleSharedResourceNotFoundException(
                new ResourceNotFoundException("Plan", 1L));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        ApiResponse<Void> body = response.getBody();
        assertNotNull(body);
        assertEquals(1003, body.getCode());
        assertEquals("资源不存在", body.getMessage());
    }

    @Test
    void shouldPassThroughBusinessConflictMessageWithoutResourceConflictPrefix() {
        // 2026-10-30 用户定案：自带完整指引的审批拦截文案原样透传，
        // 不叠加「资源冲突」前缀（HTTP 409、错误码 1002 与 ConflictException 一致）。
        String guidance = "未完成审核：还有 1 项指标的审核进度未鉴定（『科研产出增长率』）。"
                + "请到 计算机学院 的指标填报页完成全部指标的审核进度鉴定，再回到审批中心通过。";
        var response = handler.handleBusinessConflictException(new BusinessConflictException(guidance));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ApiResponse<Void> body = response.getBody();
        assertNotNull(body);
        assertFalse(body.isSuccess());
        assertEquals(1002, body.getCode());
        assertEquals(guidance, body.getMessage());
        assertFalse(body.getMessage().contains("资源冲突"));
    }

    @Test
    void shouldKeepResourceConflictPrefixForGenericConflictException() {
        // 其余 ConflictException 的全局映射行为不变：仍统一加「资源冲突」前缀
        var response = handler.handleConflictException(
                new ConflictException("当前月份已有报告正在审批中，请等待审批完成或先撤回"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ApiResponse<Void> body = response.getBody();
        assertNotNull(body);
        assertEquals(1002, body.getCode());
        assertEquals("资源冲突：当前月份已有报告正在审批中，请等待审批完成或先撤回", body.getMessage());
    }

    @Test
    void shouldConvertMalformedJsonToBadRequestResponse() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/indicators/reminders/statuses");
        request.addHeader("X-Request-ID", "req-malformed-json");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        HttpMessageNotReadableException exception =
                new HttpMessageNotReadableException("JSON parse error");

        var response = handler.handleHttpMessageNotReadableException(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ApiResponse<Void> body = response.getBody();
        assertNotNull(body);
        assertFalse(body.isSuccess());
        assertEquals(1001, body.getCode());
        assertEquals("请求体格式错误", body.getMessage());
    }

    private static final class SamplePayload {
    }

    private void sampleEndpoint(String value) {
        // test fixture only
    }
}
