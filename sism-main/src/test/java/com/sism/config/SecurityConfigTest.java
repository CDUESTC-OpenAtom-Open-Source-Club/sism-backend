package com.sism.config;

import com.sism.iam.application.JwtTokenService;
import com.sism.shared.application.dto.CurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Security Config Tests")
class SecurityConfigTest {

    @Mock
    private JwtTokenService jwtTokenService;

    @Mock
    private FilterChain filterChain;

    @Test
    @DisplayName("Should build current user from jwt claims when token is valid")
    void shouldBuildCurrentUserFromJwtClaimsWhenTokenIsValid() throws Exception {
        SecurityContextHolder.clearContext();
        SecurityConfig config = new SecurityConfig();
        OncePerRequestFilter filter = config.jwtAuthenticationFilter(jwtTokenService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer valid-token");

        when(jwtTokenService.validateToken("valid-token")).thenReturn(true);
        when(jwtTokenService.extractUsername("valid-token")).thenReturn("admin");
        when(jwtTokenService.getUserIdFromToken("valid-token")).thenReturn(1L);
        when(jwtTokenService.getOrgIdFromToken("valid-token")).thenReturn(35L);
        when(jwtTokenService.getEmailFromToken("valid-token")).thenReturn("admin@example.com");
        when(jwtTokenService.getRolesFromToken("valid-token")).thenReturn(List.of("ROLE_ADMIN"));

        filter.doFilter(request, response, filterChain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("admin", SecurityContextHolder.getContext().getAuthentication().getName());
        CurrentUser principal = (CurrentUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertEquals(1L, principal.getId());
        assertEquals(35L, principal.getOrgId());
        assertEquals("admin@example.com", principal.getEmail());
        assertEquals("admin", principal.getRealName());
        assertEquals(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")), principal.getAuthorities().stream().toList());
        verify(filterChain).doFilter(request, response);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Should continue filter chain when jwt validation throws")
    void shouldContinueFilterChainWhenJwtValidationThrows() throws Exception {
        SecurityContextHolder.clearContext();
        SecurityConfig config = new SecurityConfig();
        OncePerRequestFilter filter = config.jwtAuthenticationFilter(jwtTokenService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer bad-token");

        when(jwtTokenService.validateToken("bad-token")).thenThrow(new IllegalArgumentException("invalid token"));

        filter.doFilter(request, response, filterChain);

        verify(jwtTokenService, never()).extractUsername("bad-token");
        verify(filterChain).doFilter(request, response);
        SecurityContextHolder.clearContext();
    }
}
