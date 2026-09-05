package com.ragforge.server.identity;

import com.ragforge.server.audit.AuditOutboxService;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.config.BootstrapAdminProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class PlatformAdminBootstrapService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapAdminProperties properties;
    private final AuditOutboxService audit;

    public PlatformAdminBootstrapService(UserRepository users, PasswordEncoder passwordEncoder,
                                         BootstrapAdminProperties properties, AuditOutboxService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.audit = audit;
    }

    public BootstrapStatus status() {
        boolean required = !users.hasPlatformAdmin();
        return new BootstrapStatus(required, required && properties.isConfigured());
    }

    @Transactional
    public BootstrapResult bootstrap(String submittedToken, String email, String password, String displayName,
                                     HttpServletRequest request) {
        if (!properties.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "bootstrap_not_configured",
                    "Bootstrap is not configured", "Platform administrator bootstrap is disabled");
        }
        if (!properties.matches(submittedToken)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "bootstrap_token_invalid", "Bootstrap denied",
                    "The bootstrap credential is invalid");
        }

        users.lockPlatformAdminBootstrap();
        if (users.hasPlatformAdmin()) {
            throw new ApiException(HttpStatus.CONFLICT, "bootstrap_already_completed",
                    "Bootstrap already completed", "A platform administrator already exists");
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        String passwordHash = passwordEncoder.encode(password);
        Instant now = Instant.now();
        UserAccount existing = users.findByEmailIncludingDisabled(normalizedEmail).orElse(null);
        if (existing != null && !"ACTIVE".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "bootstrap_user_disabled", "Bootstrap user is disabled",
                    "Choose an active account or a new email address");
        }

        UserAccount administrator;
        String mode;
        try {
            if (existing == null) {
                administrator = users.createPlatformAdmin(UuidV7.random(), normalizedEmail, passwordHash,
                        displayName.trim(), now);
                mode = "CREATED";
            } else {
                administrator = users.update(existing.id(), displayName.trim(), "PLATFORM_ADMIN", "ACTIVE",
                        passwordHash, now);
                mode = "PROMOTED";
            }
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "bootstrap_identity_conflict", "Bootstrap conflict",
                    "The bootstrap identity changed concurrently; retry with a new idempotency key");
        }

        audit.record("platform.admin.bootstrapped.v1", null, null, administrator.id(), correlationId(request),
                Map.of("userId", administrator.id(), "mode", mode));
        return new BootstrapResult(administrator, mode);
    }

    private UUID correlationId(HttpServletRequest request) {
        return UUID.fromString(CorrelationIdFilter.current(request));
    }

    public record BootstrapStatus(boolean required, boolean available) {
    }

    public record BootstrapResult(UserAccount user, String mode) {
    }
}
