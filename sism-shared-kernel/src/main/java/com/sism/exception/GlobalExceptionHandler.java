package com.sism.exception;

import com.sism.common.ApiResponse;
import com.sism.common.ErrorCodes;
import com.sism.shared.domain.exception.AuthenticationException;
import com.sism.shared.domain.exception.AuthorizationException;
import com.sism.shared.domain.exception.TechnicalException;
import com.sism.shared.infrastructure.nplusone.NPlusOneQueryException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.ObjectError;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Global exception handler for REST controllers.
 * Converts exceptions to unified ApiResponse format matching API documentation.
 * 
 * Response format:
 * - Success: {"code": 200, "message": "...", "data": {...}}
 * - Error: {"code": 1001, "message": "...", "errors": [...], "timestamp": "..."}
 * 
 * Validates: Requirements 3.1.1, 3.1.3, 3.1.5
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String TIMESTAMP_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern(TIMESTAMP_FORMAT);
    private static final String MESSAGE_VALIDATION_FAILED = "参数验证失败";
    private static final String MESSAGE_INVALID_REQUEST = "请求参数不合法";
    private static final String MESSAGE_INVALID_STATE = "当前请求状态不合法";
    private static final String MESSAGE_UNAUTHORIZED = "未授权访问";
    private static final String MESSAGE_CONFLICT = "资源冲突";
    private static final String MESSAGE_RESOURCE_NOT_FOUND = "资源不存在";
    private static final String MESSAGE_AUTHENTICATION_FAILED = "认证失败";
    private static final String MESSAGE_AUTHORIZATION_FAILED = "无权限访问";
    private static final String MESSAGE_INTERNAL_ERROR = "系统错误";
    private static final String MESSAGE_REQUEST_PROCESSING_FAILED = "请求处理失败";

    /**
     * Handle unauthorized exceptions.
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorizedException(UnauthorizedException e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();
        
        log.warn("Unauthorized access: message={}, requestId={}, context=[{}]", 
                e.getMessage(), requestId, requestContext);
        
        ApiResponse<Void> response = ApiResponse.error(ErrorCodes.INVALID_TOKEN, MESSAGE_UNAUTHORIZED);
        
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(response);
    }
    
    /**
     * Handle conflict exceptions.
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflictException(ConflictException e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();
        
        log.warn("Conflict: message={}, requestId={}, context=[{}]", 
                e.getMessage(), requestId, requestContext);
        
        ApiResponse<Void> response = ApiResponse.error(ErrorCodes.DATA_ALREADY_EXISTS, MESSAGE_CONFLICT);
        
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(response);
    }
    
    /**
     * Handle validation exceptions from @Valid annotations.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidationException(
            MethodArgumentNotValidException e) {
        String requestId = getOrGenerateRequestId();

        List<Map<String, String>> fieldErrors = new ArrayList<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = error instanceof FieldError fieldError
                    ? fieldError.getField()
                    : error.getObjectName();
            String errorMessage = error.getDefaultMessage();
            Map<String, String> fieldError = new HashMap<>();
            fieldError.put("field", fieldName);
            fieldError.put("message", errorMessage);
            fieldErrors.add(fieldError);
        });

        String timestamp = LocalDateTime.now().format(FORMATTER);
        String requestContext = getRequestContext();
        log.warn("Validation failed: errors={}, requestId={}, context=[{}]",
                fieldErrors, requestId, requestContext);

        ApiResponse<Object> response = ApiResponse.error(1001, MESSAGE_VALIDATION_FAILED, fieldErrors, timestamp);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    /**
     * Handle validation exceptions thrown by @Validated method constraints.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleConstraintViolationException(
            ConstraintViolationException e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();

        List<Map<String, String>> fieldErrors = new ArrayList<>();
        for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
            Map<String, String> fieldError = new HashMap<>();
            fieldError.put("field", violation.getPropertyPath() != null
                    ? violation.getPropertyPath().toString()
                    : "request");
            fieldError.put("message", violation.getMessage());
            fieldErrors.add(fieldError);
        }

        String timestamp = LocalDateTime.now().format(FORMATTER);
        log.warn("Constraint validation failed: errors={}, requestId={}, context=[{}]",
                fieldErrors, requestId, requestContext);

        ApiResponse<Object> response = ApiResponse.error(1001, MESSAGE_VALIDATION_FAILED, fieldErrors, timestamp);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }
    
    /**
     * Handle validation exceptions.
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(ValidationException e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();
        
        log.warn("Validation error: message={}, requestId={}, context=[{}]", 
                e.getMessage(), requestId, requestContext);
        
        ApiResponse<Void> response = ApiResponse.error(1001, MESSAGE_VALIDATION_FAILED);
        
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    /**
     * Handle malformed request bodies such as invalid JSON.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();

        log.warn("Malformed request body: message={}, requestId={}, context=[{}]",
                e.getMessage(), requestId, requestContext);

        ApiResponse<Void> response = ApiResponse.error(1001, "请求体格式错误");

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }
    
    /**
     * Handle illegal argument exceptions.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();

        log.warn("Illegal argument: message={}, requestId={}, context=[{}]",
                e.getMessage(), requestId, requestContext);

        ApiResponse<Void> response = ApiResponse.error(1001, MESSAGE_INVALID_REQUEST);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    /**
     * Handle illegal state exceptions.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalStateException(IllegalStateException e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();

        log.warn("Illegal state: message={}, requestId={}, context=[{}]",
                e.getMessage(), requestId, requestContext);

        ApiResponse<Void> response = ApiResponse.error(ErrorCodes.BAD_REQUEST, MESSAGE_INVALID_STATE);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }
    
    /**
     * Handle shared business exceptions from DDD layer.
     */
    @ExceptionHandler(com.sism.shared.domain.exception.BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleSharedBusinessException(com.sism.shared.domain.exception.BusinessException e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();

        log.warn("Shared business exception: code={}, message={}, requestId={}, context=[{}]",
                e.getCode(), e.getMessage(), requestId, requestContext);

        ApiResponse<Void> response = ApiResponse.error(
                mapBusinessCodeToApiErrorCode(e.getCode()),
                mapBusinessMessage(e.getCode()));

        return ResponseEntity
                .status(getHttpStatus(e.getCode()))
                .body(response);
    }

    /**
     * Handle shared resource not found exceptions from DDD layer.
     */
    @ExceptionHandler(com.sism.shared.domain.exception.ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleSharedResourceNotFoundException(com.sism.shared.domain.exception.ResourceNotFoundException e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();

        log.warn("Shared resource not found: message={}, requestId={}, context=[{}]",
                e.getMessage(), requestId, requestContext);

        ApiResponse<Void> response = ApiResponse.error(ErrorCodes.DATA_NOT_FOUND, MESSAGE_RESOURCE_NOT_FOUND);

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(response);
    }

    /**
     * Handle authentication exceptions from DDD layer.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(AuthenticationException e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();

        log.warn("Authentication failed: message={}, requestId={}, context=[{}]",
                e.getMessage(), requestId, requestContext);

        ApiResponse<Void> response = ApiResponse.error(
                mapBusinessCodeToApiErrorCode(e.getCode()),
                e.getMessage() == null || e.getMessage().isBlank() ? MESSAGE_AUTHENTICATION_FAILED : e.getMessage()
        );

        return ResponseEntity
                .status(getHttpStatus(e.getCode()))
                .body(response);
    }

    /**
     * Handle authorization exceptions from DDD layer.
     */
    @ExceptionHandler(AuthorizationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthorizationException(AuthorizationException e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();

        log.warn("Authorization failed: message={}, requestId={}, context=[{}]",
                e.getMessage(), requestId, requestContext);

        ApiResponse<Void> response = ApiResponse.error(ErrorCodes.INSUFFICIENT_PERMISSION, MESSAGE_AUTHORIZATION_FAILED);

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(response);
    }

    /**
     * Handle technical exceptions from DDD layer.
     */
    /**
     * Handle security exceptions (e.g. role-gated business actions).
     * Returns 403 with the specific reason so the frontend can surface it.
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<Void>> handleSecurityException(SecurityException e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();

        log.warn("Security rejected: message={}, requestId={}, context=[{}]",
                e.getMessage(), requestId, requestContext);

        ApiResponse<Void> response = ApiResponse.error(ErrorCodes.INSUFFICIENT_PERMISSION, e.getMessage());

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(response);
    }

    @ExceptionHandler(TechnicalException.class)
    public ResponseEntity<ApiResponse<Void>> handleTechnicalException(TechnicalException e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();

        log.error("Technical error: message={}, requestId={}, context=[{}]",
                e.getMessage(), requestId, requestContext, e);

        ApiResponse<Void> response = ApiResponse.error(ErrorCodes.INTERNAL_ERROR, MESSAGE_INTERNAL_ERROR);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(response);
    }

    /**
     * Handle workflow exceptions.
     */
    @ExceptionHandler(WorkflowException.class)
    public ResponseEntity<ApiResponse<Void>> handleWorkflowException(WorkflowException e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();

        log.warn("Workflow exception: message={}, requestId={}, context=[{}]",
                e.getMessage(), requestId, requestContext);

        ApiResponse<Void> response = ApiResponse.error(ErrorCodes.BAD_REQUEST, MESSAGE_INVALID_STATE);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    /**
     * Handle N+1 query detection exceptions.
     */
    @ExceptionHandler(NPlusOneQueryException.class)
    public ResponseEntity<ApiResponse<Void>> handleNPlusOneQueryException(NPlusOneQueryException e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();

        log.warn("N+1 query detected: message={}, requestId={}, context=[{}]",
                e.getMessage(), requestId, requestContext);

        ApiResponse<Void> response = ApiResponse.error(ErrorCodes.BAD_REQUEST, MESSAGE_REQUEST_PROCESSING_FAILED);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    /**
     * Handle endpoint not found (404).
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();

        log.warn("Resource endpoint not found: message={}, requestId={}, context=[{}]",
                e.getMessage(), requestId, requestContext);

        ApiResponse<Void> response = ApiResponse.error(ErrorCodes.DATA_NOT_FOUND, "接口不存在");

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(response);
    }

    /**
     * Handle method not supported (405).
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();

        log.warn("Method not supported: method={}, message={}, requestId={}, context=[{}]",
                e.getMethod(), e.getMessage(), requestId, requestContext);

        ApiResponse<Void> response = ApiResponse.error(ErrorCodes.BAD_REQUEST, "请求方法不支持");

        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(response);
    }

    /**
     * Handle access denied (403).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();

        log.warn("Access denied: message={}, requestId={}, context=[{}]",
                e.getMessage(), requestId, requestContext);

        ApiResponse<Void> response = ApiResponse.error(ErrorCodes.INSUFFICIENT_PERMISSION, "无权限访问");

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(response);
    }

    /**
     * Handle all other unexpected exceptions with full stack trace.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception e) {
        String requestId = getOrGenerateRequestId();
        String requestContext = getRequestContext();

        log.error("Unexpected error occurred: message={}, type={}, requestId={}, context=[{}]",
                e.getMessage(),
                e.getClass().getName(),
                requestId,
                requestContext,
                e);

        ApiResponse<Void> response = ApiResponse.error(ErrorCodes.INTERNAL_ERROR, MESSAGE_INTERNAL_ERROR);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(response);
    }
    
    /**
     * Get request ID from MDC or generate a new one.
     */
    private String getOrGenerateRequestId() {
        String requestId = MDC.get("requestId");
        if (requestId != null && !requestId.isBlank()) {
            return requestId;
        }
        
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                String headerRequestId = request.getHeader("X-Request-ID");
                if (headerRequestId != null && !headerRequestId.isBlank()) {
                    return headerRequestId;
                }
            }
        } catch (Exception ignored) {
        }
        
        return UUID.randomUUID().toString();
    }
    
    /**
     * Build request context string for logging.
     */
    private String getRequestContext() {
        StringBuilder context = new StringBuilder();
        
        String requestId = MDC.get("requestId");
        String userId = MDC.get("userId");
        String clientIp = MDC.get("clientIp");
        String requestMethod = MDC.get("requestMethod");
        String requestUri = MDC.get("requestUri");
        
        context.append("requestId=").append(requestId != null ? requestId : "N/A");
        context.append(", userId=").append(userId != null ? userId : "N/A");
        context.append(", clientIp=").append(clientIp != null ? clientIp : "N/A");
        context.append(", method=").append(requestMethod != null ? requestMethod : "N/A");
        context.append(", uri=").append(requestUri != null ? requestUri : "N/A");
        
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                String userAgent = request.getHeader("User-Agent");
                if (userAgent != null && !userAgent.isBlank()) {
                    if (userAgent.length() > 100) {
                        userAgent = userAgent.substring(0, 100) + "...";
                    }
                    context.append(", userAgent=").append(userAgent);
                }
            }
        } catch (Exception ignored) {
        }
        
        return context.toString();
    }

    private String mapBusinessMessage(String code) {
        return switch (mapBusinessCodeToApiErrorCode(code)) {
            case ErrorCodes.DATA_ALREADY_EXISTS -> MESSAGE_CONFLICT;
            case ErrorCodes.DATA_NOT_FOUND -> MESSAGE_RESOURCE_NOT_FOUND;
            case ErrorCodes.INVALID_CREDENTIALS, ErrorCodes.INVALID_TOKEN -> MESSAGE_AUTHENTICATION_FAILED;
            case ErrorCodes.INSUFFICIENT_PERMISSION -> MESSAGE_AUTHORIZATION_FAILED;
            case ErrorCodes.INTERNAL_ERROR -> MESSAGE_INTERNAL_ERROR;
            default -> MESSAGE_INVALID_REQUEST;
        };
    }
    
    /**
     * Map business error code to API documentation error code (number format).
     * API doc uses: 1000-1999 for general, 2000-2999 for auth, etc.
     */
    private int mapBusinessCodeToApiErrorCode(String code) {
        if (code == null || code.isBlank()) {
            return ErrorCodes.BAD_REQUEST;
        }

        return switch (code) {
            case "400", "BUSINESS_ERROR" -> ErrorCodes.BAD_REQUEST;
            case "401", "AUTHENTICATION_FAILED", "INVALID_TOKEN" -> ErrorCodes.INVALID_TOKEN;
            case "INVALID_CREDENTIALS", "2001" -> ErrorCodes.INVALID_CREDENTIALS;
            case "USER_DISABLED", "2002" -> ErrorCodes.USER_DISABLED;
            case "USER_LOCKED", "2003" -> ErrorCodes.USER_LOCKED;
            case "403", "AUTHORIZATION_FAILED", "INSUFFICIENT_PERMISSION" -> ErrorCodes.INSUFFICIENT_PERMISSION;
            case "404", "RESOURCE_NOT_FOUND", "DATA_NOT_FOUND" -> ErrorCodes.DATA_NOT_FOUND;
            case "409", "DATA_ALREADY_EXISTS" -> ErrorCodes.DATA_ALREADY_EXISTS;
            case "422", "INVALID_STATUS" -> ErrorCodes.INVALID_STATUS;
            case "429", "RATE_LIMIT_EXCEEDED" -> ErrorCodes.RATE_LIMIT_EXCEEDED;
            case "500", "TECHNICAL_ERROR", "INTERNAL_ERROR" -> ErrorCodes.INTERNAL_ERROR;
            default -> {
                try {
                    yield mapBusinessCodeToApiErrorCode(Integer.parseInt(code));
                } catch (NumberFormatException ignored) {
                    yield ErrorCodes.BAD_REQUEST;
                }
            }
        };
    }

    private int mapBusinessCodeToApiErrorCode(int code) {
        return switch (code) {
            case 400 -> ErrorCodes.BAD_REQUEST;
            case 401 -> ErrorCodes.INVALID_TOKEN;
            case ErrorCodes.INVALID_CREDENTIALS -> ErrorCodes.INVALID_CREDENTIALS;
            case ErrorCodes.USER_DISABLED -> ErrorCodes.USER_DISABLED;
            case ErrorCodes.USER_LOCKED -> ErrorCodes.USER_LOCKED;
            case 403 -> ErrorCodes.INSUFFICIENT_PERMISSION;
            case 404 -> ErrorCodes.DATA_NOT_FOUND;
            case 409 -> ErrorCodes.DATA_ALREADY_EXISTS;
            case 422 -> ErrorCodes.INVALID_STATUS;
            case 429 -> ErrorCodes.RATE_LIMIT_EXCEEDED;
            case 500 -> ErrorCodes.INTERNAL_ERROR;
            default -> ErrorCodes.INTERNAL_ERROR;
        };
    }

    /**
     * Map error code to HTTP status.
     */
    private HttpStatus getHttpStatus(String code) {
        if (code == null || code.isBlank()) {
            return HttpStatus.BAD_REQUEST;
        }

        return switch (code) {
            case "400", "BUSINESS_ERROR" -> HttpStatus.BAD_REQUEST;
            case "401", "AUTHENTICATION_FAILED", "INVALID_CREDENTIALS", "USER_DISABLED", "USER_LOCKED",
                    "2001", "2002", "2003" -> HttpStatus.UNAUTHORIZED;
            case "403", "AUTHORIZATION_FAILED" -> HttpStatus.FORBIDDEN;
            case "404", "RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "409" -> HttpStatus.CONFLICT;
            case "422", "INVALID_STATUS" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "429", "RATE_LIMIT_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "500", "TECHNICAL_ERROR", "INTERNAL_ERROR" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> {
                try {
                    yield getHttpStatus(Integer.parseInt(code));
                } catch (NumberFormatException ignored) {
                    yield HttpStatus.BAD_REQUEST;
                }
            }
        };
    }

    private HttpStatus getHttpStatus(int code) {
        return switch (code) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case ErrorCodes.INVALID_CREDENTIALS, ErrorCodes.USER_DISABLED, ErrorCodes.USER_LOCKED -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
