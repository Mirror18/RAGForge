package com.ragforge.server.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapAdminPropertiesTest {
    @Test
    void blankTokenKeepsBootstrapDisabled() {
        BootstrapAdminProperties properties = new BootstrapAdminProperties();

        assertThat(properties.isConfigured()).isFalse();
        assertThat(properties.matches(null)).isFalse();
        assertThat(properties.matches("")).isFalse();
    }

    @Test
    void configuredTokenUsesExactBoundedComparison() {
        BootstrapAdminProperties properties = new BootstrapAdminProperties();
        String token = "bootstrap-secret-that-is-at-least-32-characters";
        properties.setToken(token);

        assertThat(properties.isConfigured()).isTrue();
        assertThat(properties.matches(token)).isTrue();
        assertThat(properties.matches(token + "x")).isFalse();
        assertThat(properties.matches("x".repeat(513))).isFalse();
    }

    @Test
    void shortConfiguredTokenFailsClosed() {
        BootstrapAdminProperties properties = new BootstrapAdminProperties();

        assertThatThrownBy(() -> properties.setToken("too-short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32");
    }
}
