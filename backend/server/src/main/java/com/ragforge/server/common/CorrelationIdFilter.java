package com.ragforge.server.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTRIBUTE = CorrelationIdFilter.class.getName();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = validUuid(request.getHeader(HEADER))
                .orElseGet(() -> UuidV7.random().toString());
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", correlationId)) {
            filterChain.doFilter(request, response);
        }
    }

    public static String current(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value == null ? UuidV7.random().toString() : value.toString();
    }

    private static java.util.Optional<String> validUuid(String value) {
        return UuidV7.isUuidV7(value)
                ? java.util.Optional.of(value.toLowerCase(java.util.Locale.ROOT))
                : java.util.Optional.empty();
    }
}
