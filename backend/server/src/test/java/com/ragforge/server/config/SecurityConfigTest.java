package com.ragforge.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigTest {
    @Test
    void passwordAuthenticationIsExplicitlyDisabled() {
        UserDetailsService service = new SecurityConfig().disabledPasswordAuthentication();

        assertThatThrownBy(() -> service.loadUserByUsername("any-user"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("Password authentication is not configured");
    }
}
