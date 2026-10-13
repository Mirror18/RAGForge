package com.ragforge.server.identity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PlatformAdminCreateCli {
    static final String MODE_PROPERTY = "ragforge.cli.mode";
    static final String EMAIL_PROPERTY = "ragforge.cli.admin-email";
    static final String DISPLAY_NAME_PROPERTY = "ragforge.cli.admin-display-name";
    static final String PASSWORD_ENV = "RAGFORGE_ADMIN_CLI_PASSWORD";

    private static final String FLAG = "--ragforge-create-platform-admin";
    private static final String EMAIL_PREFIX = "--ragforge-admin-email=";
    private static final String DISPLAY_PREFIX = "--ragforge-admin-display-name=";

    private PlatformAdminCreateCli() {
    }

    public static boolean matches(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (FLAG.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    public static Map<String, Object> propertiesFromArgs(String[] args) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(MODE_PROPERTY, "create-platform-admin");
        String email = null;
        String displayName = null;
        for (String arg : args) {
            if (arg.startsWith(EMAIL_PREFIX)) {
                email = arg.substring(EMAIL_PREFIX.length());
            } else if (arg.startsWith(DISPLAY_PREFIX)) {
                displayName = arg.substring(DISPLAY_PREFIX.length());
            }
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Missing --ragforge-admin-email=<address>");
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = "Platform Administrator";
        }
        properties.put(EMAIL_PROPERTY, email.trim().toLowerCase(Locale.ROOT));
        properties.put(DISPLAY_NAME_PROPERTY, displayName.trim());
        return properties;
    }

    public static String[] serverArgs(String[] args) {
        List<String> remaining = new ArrayList<>();
        for (String arg : args) {
            if (FLAG.equals(arg) || arg.startsWith(EMAIL_PREFIX) || arg.startsWith(DISPLAY_PREFIX)) {
                continue;
            }
            remaining.add(arg);
        }
        return remaining.toArray(String[]::new);
    }

    public static String requiredPassword() {
        String password = System.getenv(PASSWORD_ENV);
        if (password == null || password.length() < 12) {
            throw new IllegalArgumentException(PASSWORD_ENV + " must be set to at least 12 characters");
        }
        return password;
    }
}
