package com.ragforge.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.common.IdempotencyKeyFilter;
import com.ragforge.server.common.ProblemResponseWriter;
import com.ragforge.server.identity.CsrfProtectionFilter;
import com.ragforge.server.identity.SessionAuthenticationFilter;
import com.ragforge.server.identity.SessionRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({SessionProperties.class, BootstrapAdminProperties.class})
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Password authentication is intentionally not an application entry path; sessions are authenticated by
     * SessionAuthenticationFilter. Declaring this bean prevents Boot from creating and logging a generated password.
     */
    @Bean
    UserDetailsService disabledPasswordAuthentication() {
        return username -> {
            throw new UsernameNotFoundException("Password authentication is not configured");
        };
    }

    @Bean
    CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    SessionAuthenticationFilter sessionAuthenticationFilter(SessionRepository sessionRepository,
                                                             SessionProperties properties) {
        return new SessionAuthenticationFilter(sessionRepository, properties);
    }

    @Bean
    CsrfProtectionFilter csrfProtectionFilter(ObjectMapper objectMapper) {
        return new CsrfProtectionFilter(objectMapper);
    }

    @Bean
    IdempotencyKeyFilter idempotencyKeyFilter(ObjectMapper objectMapper,
                                              com.ragforge.server.common.IdempotencyRepository repository) {
        return new IdempotencyKeyFilter(objectMapper, repository);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                             ObjectMapper objectMapper,
                                             CorrelationIdFilter correlationIdFilter,
                                             SessionAuthenticationFilter sessionAuthenticationFilter,
                                             CsrfProtectionFilter csrfProtectionFilter,
                                             IdempotencyKeyFilter idempotencyKeyFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ignored) ->
                                ProblemResponseWriter.write(objectMapper, request, response, 401,
                                        "authentication_required", "Authentication required",
                                        "A valid session is required"))
                        .accessDeniedHandler((request, response, ignored) ->
                                ProblemResponseWriter.write(objectMapper, request, response, 403,
                                        "forbidden", "Forbidden", "The authenticated principal is not allowed")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/api/v1/auth/register",
                                "/api/v1/auth/login", "/api/v1/sessions",
                                "/api/v1/bootstrap/platform-admin").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(sessionAuthenticationFilter, CorrelationIdFilter.class)
                .addFilterAfter(csrfProtectionFilter, SessionAuthenticationFilter.class)
                .addFilterAfter(idempotencyKeyFilter, CsrfProtectionFilter.class);
        return http.build();
    }
}
