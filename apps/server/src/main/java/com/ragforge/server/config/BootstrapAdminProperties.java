package com.ragforge.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@ConfigurationProperties(prefix = "ragforge.bootstrap.admin")
public class BootstrapAdminProperties {
    private String token = "";

    public void setToken(String token) {
        String value = token == null ? "" : token;
        if (!value.isBlank() && value.length() < 32) {
            throw new IllegalArgumentException("Bootstrap admin token must contain at least 32 characters");
        }
        this.token = value;
    }

    public boolean isConfigured() {
        return !token.isBlank();
    }

    public boolean matches(String submitted) {
        if (!isConfigured() || submitted == null || submitted.length() > 512) {
            return false;
        }
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                submitted.getBytes(StandardCharsets.UTF_8));
    }
}
