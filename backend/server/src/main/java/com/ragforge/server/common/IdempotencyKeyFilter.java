package com.ragforge.server.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

/** Validates and durably binds every API mutation to one request hash and principal scope. */
public class IdempotencyKeyFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final String ANONYMOUS_SCOPE = "anonymous";

    private final ObjectMapper objectMapper;
    private final IdempotencyRepository repository;

    public IdempotencyKeyFilter(ObjectMapper objectMapper, IdempotencyRepository repository) {
        this.objectMapper = objectMapper;
        this.repository = repository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isApiMutation(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = request.getHeader("Idempotency-Key");
        if (key == null || key.isBlank() || key.length() > 255 || !key.matches("[A-Za-z0-9._~-]+")) {
            ProblemResponseWriter.write(objectMapper, request, response, 400, "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency key required", "A valid Idempotency-Key header is required");
            return;
        }

        boolean multipart = request.getContentType() != null
                && request.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
        byte[] body = multipart ? new byte[0] : request.getInputStream().readAllBytes();
        String principalScope = principalScope();
        String requestHash = requestHash(request, body);
        IdempotencyRepository.Record previous = repository.find(principalScope, key).orElse(null);
        if (previous != null && !multipart) {
            boolean sameRequest = previous.requestHash().equals(requestHash);
            ProblemResponseWriter.write(objectMapper, request, response, 409,
                    sameRequest ? "IDEMPOTENCY_KEY_REUSED" : "IDEMPOTENCY_KEY_CONFLICT",
                    "Idempotency key conflict", sameRequest
                            ? "The idempotency key was already used for this request"
                            : "The idempotency key was already used for a different request");
            return;
        }

        if (!repository.tryCreate(principalScope, key, requestHash, request.getMethod(),
                request.getRequestURI(), Instant.now())) {
            ProblemResponseWriter.write(objectMapper, request, response, 409, "IDEMPOTENCY_KEY_CONFLICT",
                    "Idempotency key conflict", "The idempotency key is being used by another request");
            return;
        }

        try {
            filterChain.doFilter(multipart ? request : new CachedBodyRequest(request, body), response);
        } finally {
            repository.markCompleted(principalScope, key, response.getStatus(), Instant.now());
        }
    }

    private boolean isApiMutation(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/v1/") && !SAFE_METHODS.contains(request.getMethod());
    }

    private String principalScope() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof com.ragforge.server.identity.SessionPrincipal principal) {
            return principal.userId().toString();
        }
        return ANONYMOUS_SCOPE;
    }

    private String requestHash(HttpServletRequest request, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(request.getMethod().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream delegate = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return delegate.read();
                }

                @Override
                public boolean isFinished() {
                    return delegate.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // Synchronous request processing does not use non-blocking reads.
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),
                    StandardCharsets.UTF_8));
        }
    }
}
