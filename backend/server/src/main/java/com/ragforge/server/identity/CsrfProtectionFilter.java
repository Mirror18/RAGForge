package com.ragforge.server.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.common.ProblemResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Set;

public class CsrfProtectionFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private final ObjectMapper objectMapper;

    public CsrfProtectionFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (requiresCsrf(request)) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof SessionPrincipal principal) {
                String submitted = request.getHeader("X-CSRF-Token");
                if (submitted == null || !MessageDigest.isEqual(submitted.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        principal.csrfToken().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                    ProblemResponseWriter.write(objectMapper, request, response, 403, "csrf_failed",
                            "CSRF validation failed", "A valid CSRF token is required");
                    return;
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean requiresCsrf(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/")) {
            return false;
        }
        return !("POST".equals(request.getMethod()) &&
                (path.equals("/api/v1/auth/register") || path.equals("/api/v1/auth/login")
                        || path.equals("/api/v1/sessions")
                        || path.equals("/api/v1/bootstrap/platform-admin")));
    }
}
