package com.sism.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Filter to add request context to MDC for comprehensive logging.
 * 
 * This filter captures:
 * - Request ID (generated or from header)
 * - User ID (from JWT authentication)
 * - Client IP address
 * - Request method and URI
 * - Response status and time
 * 
 * Requirements:
 * - 15.2: Include timestamp, log level, request ID, user ID
 * - 15.5: Request context in error logs
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final Pattern SENSITIVE_QUERY_PARAM_PATTERN =
            Pattern.compile("(?i)(^|&)(token|access_token|refresh_token|password|secret)=([^&]*)");
    
    // Header names
    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    private static final String REAL_IP_HEADER = "X-Real-IP";
    
    // MDC keys
    private static final String REQUEST_ID_KEY = "requestId";
    private static final String USER_ID_KEY = "userId";
    private static final String CLIENT_IP_KEY = "clientIp";
    private static final String REQUEST_METHOD_KEY = "requestMethod";
    private static final String REQUEST_URI_KEY = "requestUri";
    private static final String RESPONSE_STATUS_KEY = "responseStatus";
    private static final String RESPONSE_TIME_KEY = "responseTime";

    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                    HttpServletResponse response, 
                                    FilterChain filterChain) throws ServletException, IOException {
        long startTime = System.currentTimeMillis();

        try {
            // Set up MDC context
            setupMDC(request, response);

            // Process request
            filterChain.doFilter(request, response);

        } finally {
            // Calculate response time
            long responseTime = System.currentTimeMillis() - startTime;
            MDC.put(RESPONSE_TIME_KEY, String.valueOf(responseTime));
            MDC.put(RESPONSE_STATUS_KEY, String.valueOf(response.getStatus()));

            // Log access entry
            logAccessEntry(request, response, responseTime);

            // Clean up MDC
            clearMDC();
        }
    }
    
    /**
     * Set up MDC with request context information.
     */
    private void setupMDC(HttpServletRequest request, HttpServletResponse response) {
        // Request ID - use existing or generate new
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = generateRequestId();
        }
        MDC.put(REQUEST_ID_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        
        // Client IP - check proxy headers first
        String clientIp = getClientIp(request);
        MDC.put(CLIENT_IP_KEY, clientIp);
        
        // Request method and URI
        MDC.put(REQUEST_METHOD_KEY, request.getMethod());
        MDC.put(REQUEST_URI_KEY, getRequestUri(request));
        
        // User ID - will be "anonymous" until JWT filter sets it
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            MDC.put(USER_ID_KEY, "pending");
        } else {
            MDC.put(USER_ID_KEY, "anonymous");
        }
    }
    
    /**
     * Generate a short unique request ID.
     */
    private String generateRequestId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
    
    /**
     * Extract client IP address, considering proxy headers.
     */
    private String getClientIp(HttpServletRequest request) {
        // Check X-Forwarded-For header (may contain multiple IPs)
        String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            // Take the first IP (original client)
            return forwardedFor.split(",")[0].trim();
        }
        
        // Check X-Real-IP header
        String realIp = request.getHeader(REAL_IP_HEADER);
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        
        // Fall back to remote address
        return request.getRemoteAddr();
    }
    
    /**
     * Get the full request URI including query string.
     */
    private String getRequestUri(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String queryString = request.getQueryString();
        if (queryString != null && !queryString.isBlank()) {
            return uri + "?" + maskSensitiveQueryParams(queryString);
        }
        return uri;
    }

    private String maskSensitiveQueryParams(String queryString) {
        return SENSITIVE_QUERY_PARAM_PATTERN.matcher(queryString).replaceAll("$1$2=***");
    }
    
    /**
     * Log access entry for request/response.
     */
    private void logAccessEntry(HttpServletRequest request, 
                                HttpServletResponse response,
                                long responseTime) {
        int status = response.getStatus();
        String method = request.getMethod();
        String uri = getRequestUri(request);
        
        // Log at appropriate level based on status
        if (status >= 500) {
            log.error("Request completed: {} {} - {} ({}ms)", method, uri, status, responseTime);
        } else if (status >= 400) {
            log.warn("Request completed: {} {} - {} ({}ms)", method, uri, status, responseTime);
        } else {
            log.info("Request completed: {} {} - {} ({}ms)", method, uri, status, responseTime);
        }
    }
    
    /**
     * Clear all MDC entries to prevent memory leaks.
     */
    private void clearMDC() {
        MDC.remove(REQUEST_ID_KEY);
        MDC.remove(USER_ID_KEY);
        MDC.remove(CLIENT_IP_KEY);
        MDC.remove(REQUEST_METHOD_KEY);
        MDC.remove(REQUEST_URI_KEY);
        MDC.remove(RESPONSE_STATUS_KEY);
        MDC.remove(RESPONSE_TIME_KEY);
    }
    
    /**
     * Static method to update user ID in MDC after authentication.
     * Called by JwtAuthenticationFilter after successful token validation.
     */
    public static void setUserId(String userId) {
        if (userId != null && !userId.isBlank()) {
            MDC.put(USER_ID_KEY, userId);
        }
    }
}
