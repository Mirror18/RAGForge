package com.ragforge.server.space.application;

import com.ragforge.server.shared.application.AuditOutboxService;
import com.ragforge.server.shared.domain.ApiException;
import com.ragforge.server.shared.api.CorrelationIdFilter;
import com.ragforge.server.shared.domain.UuidV7;
import com.ragforge.server.shared.domain.BootstrapAdminProperties;
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
import com.ragforge.server.space.infrastructure.PlatformAdminStartupTokenRepository;
import com.ragforge.server.space.domain.UserAccount;
import com.ragforge.server.space.infrastructure.UserRepository;

@Service
public class PlatformAdminBootstrapService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapAdminProperties properties;
    private final PlatformAdminStartupTokenRepository startupTokens;
    private final AuditOutboxService audit;

    public PlatformAdminBootstrapService(UserRepository users, PasswordEncoder passwordEncoder,
                                         BootstrapAdminProperties properties,
                                         PlatformAdminStartupTokenRepository startupTokens,
                                         AuditOutboxService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.startupTokens = startupTokens;
        this.audit = audit;
    }

    public BootstrapStatus status() {
        boolean required = !users.hasPlatformAdmin();
        return new BootstrapStatus(required, required && isBootstrapAvailable());
    }

    @Transactional
    public BootstrapResult bootstrap(String submittedToken, String email, String password, String displayName,
                                     HttpServletRequest request) {
        String credentialSource = resolveCredentialSource(submittedToken);
        if (credentialSource == null) {
            if (!isBootstrapAvailable()) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "bootstrap_not_configured",
                        "Bootstrap is not configured", "Platform administrator bootstrap is disabled");
            }
            throw new ApiException(HttpStatus.FORBIDDEN, "bootstrap_token_invalid", "Bootstrap denied",
                    "The bootstrap credential is invalid");
        }
        return completeBootstrap(email, password, displayName, credentialSource, correlationId(request));
    }

    @Transactional
    public BootstrapResult bootstrapFromCli(String email, String password, String displayName) {
        return completeBootstrap(email, password, displayName, "CLI", null);
    }

    @Transactional
    public BootstrapResult bootstrapFromDevSeed(String email, String password, String displayName) {
        return completeBootstrap(email, password, displayName, "DEV_SEED", null);
    }

    private BootstrapResult completeBootstrap(String email, String password, String displayName,
                                              String credentialSource, UUID correlationId) {
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

        startupTokens.clear();
        audit.record("platform.admin.bootstrapped.v1", null, null, administrator.id(),
                correlationId == null ? UuidV7.random() : correlationId,
                Map.of("userId", administrator.id(), "mode", mode, "credentialSource", credentialSource));
        return new BootstrapResult(administrator, mode);
    }

    private boolean isBootstrapAvailable() {
        return properties.isConfigured() || startupTokens.hasActiveToken();
    }

    private String resolveCredentialSource(String submittedToken) {
        if (properties.matches(submittedToken)) {
            return "CONFIGURED_TOKEN";
        }
        return startupTokens.findTokenHash()
                .filter(hash -> BootstrapTokenSupport.matches(submittedToken, hash))
                .map(ignored -> "STARTUP_TOKEN")
                .orElse(null);
    }

    private UUID correlationId(HttpServletRequest request) {
        return UUID.fromString(CorrelationIdFilter.current(request));
    }

    public record BootstrapStatus(boolean required, boolean available) {
    }

    public record BootstrapResult(UserAccount user, String mode) {
    }
}
