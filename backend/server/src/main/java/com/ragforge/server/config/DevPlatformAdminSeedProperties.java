package com.ragforge.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ragforge.bootstrap.admin.dev-seed")
public class DevPlatformAdminSeedProperties {
    private boolean enabled = false;
    private String email = "dev-admin@ragforge.local";
    private String password = "dev-admin-change-me";
    private String displayName = "Dev Platform Admin";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email == null ? "" : email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password == null ? "" : password;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName == null ? "" : displayName;
    }

    public boolean isConfigured() {
        return enabled
                && !email.isBlank()
                && password.length() >= 12
                && !displayName.isBlank();
    }
}
