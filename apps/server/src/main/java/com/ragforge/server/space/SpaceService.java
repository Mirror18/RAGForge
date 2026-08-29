package com.ragforge.server.space;

import com.ragforge.server.audit.AuditOutboxService;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.identity.UserAccount;
import com.ragforge.server.identity.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SpaceService {
    private final SpaceRepository spaceRepository;
    private final UserRepository userRepository;
    private final AuditOutboxService auditOutboxService;

    public SpaceService(SpaceRepository spaceRepository, UserRepository userRepository,
                        AuditOutboxService auditOutboxService) {
        this.spaceRepository = spaceRepository;
        this.userRepository = userRepository;
        this.auditOutboxService = auditOutboxService;
    }

    public List<KnowledgeSpace> list(SessionPrincipal principal) {
        return spaceRepository.findAllForUser(principal.userId());
    }

    public List<SpaceMemberView> listMembers(SessionPrincipal principal, UUID spaceId) {
        requireAdmin(principal, spaceId);
        ensureActive(spaceId);
        return spaceRepository.findMembers(spaceId);
    }

    public long currentVersion(UUID spaceId) {
        return ensureActive(spaceId).version();
    }

    @Transactional
    public KnowledgeSpace create(SessionPrincipal principal, String name, String description,
                                 HttpServletRequest request) {
        String normalizedName = name.trim();
        UUID spaceId = UuidV7.random();
        Instant now = Instant.now();
        KnowledgeSpace space = spaceRepository.create(spaceId, normalizedName,
                description == null ? null : description.trim(), now);
        spaceRepository.addMembership(spaceId, principal.userId(), SpaceRole.SPACE_ADMIN, now);
        auditOutboxService.record("space.created.v1", principal.userId(), spaceId, spaceId,
                correlationId(request), Map.of("spaceId", spaceId, "name", normalizedName));
        return new KnowledgeSpace(space.id(), space.name(), space.description(), space.status(), SpaceRole.SPACE_ADMIN,
                space.createdAt(), space.updatedAt(), space.version());
    }

    @Transactional
    public SpaceMember updateMember(SessionPrincipal principal, UUID spaceId, UUID userId, SpaceRole role,
                                    HttpServletRequest request) {
        requireAdmin(principal, spaceId);
        ensureActive(spaceId);
        if (!spaceRepository.userExists(userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "User not found", "User not found");
        }
        if (principal.userId().equals(userId) && role != SpaceRole.SPACE_ADMIN && spaceRepository.countAdmins(spaceId) <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "last_space_admin", "Last admin cannot be removed",
                    "A space must retain at least one space administrator");
        }
        Instant now = Instant.now();
        long version = spaceRepository.upsertMembership(spaceId, userId, role, now);
        auditOutboxService.record("space.member.updated.v1", principal.userId(), spaceId, spaceId,
                correlationId(request), Map.of("spaceId", spaceId, "userId", userId, "role", role.name()));
        return new SpaceMember(spaceId, userId, role, version);
    }

    @Transactional
    public SpaceMember addMember(SessionPrincipal principal, UUID spaceId, String email, SpaceRole role,
                                 HttpServletRequest request) {
        requireAdmin(principal, spaceId);
        ensureActive(spaceId);
        UserAccount target = userRepository.findByEmail(email.trim()).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "active_user_not_found", "Active user not found",
                        "No active user matches that email address"));
        if (spaceRepository.findRole(spaceId, target.id()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "space_member_already_exists", "Member already exists",
                    "The user is already a member of this space");
        }
        Instant now = Instant.now();
        spaceRepository.addMembership(spaceId, target.id(), role, now);
        auditOutboxService.record("space.member.added.v1", principal.userId(), spaceId, spaceId,
                correlationId(request), Map.of("spaceId", spaceId, "userId", target.id(), "role", role.name()));
        return new SpaceMember(spaceId, target.id(), role, 0);
    }

    @Transactional
    public KnowledgeSpace update(SessionPrincipal principal, UUID spaceId, String name, String description,
                                 long expectedVersion, HttpServletRequest request) {
        requireAdmin(principal, spaceId);
        KnowledgeSpace current = ensureActive(spaceId);
        try {
            if (!spaceRepository.updateSpace(spaceId, name.trim(), description == null ? null : description.trim(),
                    expectedVersion, Instant.now())) {
                throw new ApiException(HttpStatus.PRECONDITION_FAILED, "space_version_conflict", "Version conflict",
                        "The space changed since it was loaded");
            }
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "space_name_already_exists", "Space name already exists",
                    "Choose a different space name");
        }
        auditOutboxService.record("space.updated.v1", principal.userId(), spaceId, spaceId,
                correlationId(request), Map.of("spaceId", spaceId, "previousVersion", current.version()));
        return spaceRepository.findById(spaceId).orElseThrow();
    }

    @Transactional
    public void archive(SessionPrincipal principal, UUID spaceId, long expectedVersion, HttpServletRequest request) {
        requireAdmin(principal, spaceId);
        KnowledgeSpace current = ensureActive(spaceId);
        if (!spaceRepository.archive(spaceId, expectedVersion, Instant.now())) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "space_version_conflict", "Version conflict",
                    "The space changed since it was loaded");
        }
        auditOutboxService.record("space.archived.v1", principal.userId(), spaceId, spaceId,
                correlationId(request), Map.of("spaceId", spaceId, "previousVersion", current.version()));
    }

    @Transactional
    public void removeMember(SessionPrincipal principal, UUID spaceId, UUID userId, HttpServletRequest request) {
        requireAdmin(principal, spaceId);
        ensureActive(spaceId);
        SpaceRole targetRole = spaceRepository.findRole(spaceId, userId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "member_not_found", "Member not found", "Member not found"));
        if (targetRole == SpaceRole.SPACE_ADMIN && spaceRepository.countAdmins(spaceId) <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "last_space_admin", "Last admin cannot be removed",
                    "A space must retain at least one space administrator");
        }
        if (!spaceRepository.deleteMembership(spaceId, userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "member_not_found", "Member not found", "Member not found");
        }
        auditOutboxService.record("space.member.removed.v1", principal.userId(), spaceId, spaceId,
                correlationId(request), Map.of("spaceId", spaceId, "userId", userId));
    }

    private void requireAdmin(SessionPrincipal principal, UUID spaceId) {
        SpaceRole actorRole = spaceRepository.findRole(spaceId, principal.userId()).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "space_not_found", "Space not found", "Space not found"));
        if (actorRole != SpaceRole.SPACE_ADMIN && !"PLATFORM_ADMIN".equals(principal.platformRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "space_admin_required", "Forbidden",
                    "Space admin permission is required");
        }
    }

    private KnowledgeSpace ensureActive(UUID spaceId) {
        KnowledgeSpace space = spaceRepository.findById(spaceId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "space_not_found", "Space not found", "Space not found"));
        if (!"ACTIVE".equals(space.status())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "space_not_found", "Space not found", "Space not found");
        }
        return space;
    }

    private UUID correlationId(HttpServletRequest request) {
        return UUID.fromString(CorrelationIdFilter.current(request));
    }
}
