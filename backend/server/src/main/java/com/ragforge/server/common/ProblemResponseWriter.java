package com.ragforge.server.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatusCode;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

public final class ProblemResponseWriter {
    private ProblemResponseWriter() {
    }

    public static void write(ObjectMapper objectMapper, HttpServletRequest request,
                             HttpServletResponse response, int status, String code,
                             String title, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://ragforge.local/problems/" + code));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.toUpperCase(java.util.Locale.ROOT));
        problem.setProperty("correlationId", CorrelationIdFilter.current(request));
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
