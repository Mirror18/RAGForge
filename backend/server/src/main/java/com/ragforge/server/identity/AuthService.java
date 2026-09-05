package com.ragforge.server.identity;

import com.ragforge.server.audit.AuditOutboxService;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.config.SessionProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final AuditOutboxService auditOutboxService;
    private final PasswordEncoder passwordEncoder;
    private final SessionProperties sessionProperties;

    public AuthService(UserRepository userRepository, SessionRepository sessionRepository,
                       AuditOutboxService auditOutboxService, PasswordEncoder passwordEncoder,
                       SessionProperties sessionProperties) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.auditOutboxService = auditOutboxService;
        this.passwordEncoder = passwordEncoder;
        this.sessionProperties = sessionProperties;
    }

    @Transactional
    public UserAccount register(String email, String password, String displayName, HttpServletRequest request) {
        String normalizedEmail = email.trim().toLowerCase(java.util.Locale.ROOT);
        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "email_already_registered", "Email already registered",
                    "An account with this email already exists");
        }
        UUID id = UuidV7.random();
        try {
            UserAccount user = userRepository.create(id, normalizedEmail, passwordEncoder.encode(password),
                    displayName.trim());
            auditOutboxService.record("user.registered.v1", user.id(), null, user.id(),
                    correlationId(request), Map.of("userId", user.id(), "email", user.email()));
            return user;
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "email_already_registered", "Email already registered",
                    "An account with this email already exists");
        }
    }

    @Transactional
    public SessionData login(String email, String password, HttpServletRequest request) {
        UserAccount user = userRepository.findByEmail(email.trim().toLowerCase(java.util.Locale.ROOT))
                .filter(candidate -> passwordEncoder.matches(password, candidate.passwordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials",
                        "Authentication failed", "Email or password is invalid"));
        Instant created = Instant.now();
        UUID sessionId = UuidV7.random();
        String rawToken = SessionAuthenticationFilter.randomToken();
        String csrfToken = SessionAuthenticationFilter.randomToken();
        sessionRepository.create(sessionId, SessionAuthenticationFilter.hash(rawToken), user.id(),
                csrfToken, created, created.plus(sessionProperties.getTtl()));
        auditOutboxService.record("session.created.v1", user.id(), null, sessionId,
                correlationId(request), Map.of("userId", user.id(), "sessionId", sessionId));
        return new SessionData(sessionId, rawToken, csrfToken, user, created.plus(sessionProperties.getTtl()));
    }

    @Transactional
    public void logout(SessionPrincipal principal, HttpServletRequest request) {
        sessionRepository.revoke(principal.sessionId(), Instant.now());
        auditOutboxService.record("session.revoked.v1", principal.userId(), null, principal.sessionId(),
                correlationId(request), Map.of("userId", principal.userId(), "sessionId", principal.sessionId()));
    }

    private UUID correlationId(HttpServletRequest request) {
        return UUID.fromString(CorrelationIdFilter.current(request));
    }

    public record SessionData(UUID sessionId, String rawToken, String csrfToken, UserAccount user,
                              Instant expiresAt) {
    }
}
