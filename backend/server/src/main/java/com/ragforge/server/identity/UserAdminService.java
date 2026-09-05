package com.ragforge.server.identity;

import com.ragforge.server.audit.AuditOutboxService;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.common.UuidV7;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class UserAdminService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditOutboxService audit;

    public UserAdminService(UserRepository users, PasswordEncoder passwordEncoder, AuditOutboxService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    public List<UserAccount> list(SessionPrincipal actor) {
        requirePlatformAdmin(actor);
        return users.findAll();
    }

    @Transactional
    public UserAccount create(SessionPrincipal actor, String email, String displayName, String password,
                              HttpServletRequest request) {
        requirePlatformAdmin(actor);
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        try {
            UserAccount user = users.create(UuidV7.random(), normalizedEmail, passwordEncoder.encode(password),
                    displayName.trim());
            audit.record("user.admin.created.v1", actor.userId(), null, user.id(), correlationId(request),
                    Map.of("userId", user.id(), "email", user.email()));
            return user;
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "email_already_registered", "Email already registered",
                    "An account with this email already exists");
        }
    }

    @Transactional
    public UserAccount update(SessionPrincipal actor, UUID userId, String displayName, String platformRole,
                              String status, String password, HttpServletRequest request) {
        requirePlatformAdmin(actor);
        validateRole(platformRole);
        validateStatus(status);
        if (actor.userId().equals(userId) && (!"PLATFORM_ADMIN".equals(platformRole) || "DISABLED".equals(status))) {
            throw new ApiException(HttpStatus.CONFLICT, "cannot_lock_out_self", "Self lockout is not allowed",
                    "A platform administrator cannot remove their own administrator access or disable their account");
        }
        UserAccount current = users.findByIdIncludingDisabled(userId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "User not found", "User not found"));
        UserAccount updated = users.update(userId, displayName.trim(), platformRole, status,
                password == null || password.isBlank() ? null : passwordEncoder.encode(password), Instant.now());
        audit.record("user.admin.updated.v1", actor.userId(), null, userId, correlationId(request),
                Map.of("userId", userId, "status", updated.status(), "platformRole", updated.platformRole(),
                        "changedFromStatus", current.status()));
        return updated;
    }

    @Transactional
    public UserAccount disable(SessionPrincipal actor, UUID userId, HttpServletRequest request) {
        requirePlatformAdmin(actor);
        UserAccount current = users.findByIdIncludingDisabled(userId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "User not found", "User not found"));
        if (actor.userId().equals(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "cannot_lock_out_self", "Self lockout is not allowed",
                    "A platform administrator cannot disable their own account");
        }
        UserAccount updated = users.update(userId, current.displayName(), current.platformRole(), "DISABLED", null,
                Instant.now());
        audit.record("user.admin.disabled.v1", actor.userId(), null, userId, correlationId(request),
                Map.of("userId", userId));
        return updated;
    }

    public static void requirePlatformAdmin(SessionPrincipal principal) {
        if (!"PLATFORM_ADMIN".equals(principal.platformRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "platform_admin_required", "Forbidden",
                    "Platform administrator permission is required");
        }
    }

    private void validateRole(String value) {
        if (!"PLATFORM_ADMIN".equals(value) && !"USER".equals(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_platform_role", "Invalid platform role",
                    "Role must be PLATFORM_ADMIN or USER");
        }
    }

    private void validateStatus(String value) {
        if (!"ACTIVE".equals(value) && !"DISABLED".equals(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_user_status", "Invalid user status",
                    "Status must be ACTIVE or DISABLED");
        }
    }

    private UUID correlationId(HttpServletRequest request) {
        return UUID.fromString(CorrelationIdFilter.current(request));
    }
}
