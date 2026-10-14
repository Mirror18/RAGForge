package com.ragforge.server.space.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.ragforge.server.space.application.BootstrapTokenSupport;

class BootstrapTokenSupportTest {
    @Test
    void hashAndMatchRoundTrip() {
        String plaintext = BootstrapTokenSupport.generatePlaintext();
        assertThat(plaintext.length()).isGreaterThanOrEqualTo(32);

        String hash = BootstrapTokenSupport.hash(plaintext);
        assertThat(hash).hasSize(64);
        assertThat(BootstrapTokenSupport.matches(plaintext, hash)).isTrue();
        assertThat(BootstrapTokenSupport.matches(plaintext + "x", hash)).isFalse();
    }

    @Test
    void rejectsShortPlaintext() {
        String hash = BootstrapTokenSupport.hash("x".repeat(32));
        assertThat(BootstrapTokenSupport.matches("short", hash)).isFalse();
    }

    @Test
    void rejectsBlankHashInput() {
        assertThatThrownBy(() -> BootstrapTokenSupport.hash(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
