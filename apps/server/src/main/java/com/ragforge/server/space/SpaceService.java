package com.ragforge.server.space;

import com.ragforge.server.audit.AuditOutboxService;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.identity.SessionPrincipal;
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
    private final AuditOutboxService auditOutboxService;

    public SpaceService(SpaceRepository spaceRepository, AuditOutboxService auditOutboxService) {
        this.spaceRepository = spaceRepository;
        this.auditOutboxService = auditOutboxService;
    }

    public List<KnowledgeSpace> list(SessionPrincipal principal) {
        return spaceRepository.findAllForUser(principal.userId());
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
        SpaceRole actorRole = spaceRepository.findRole(spaceId, principal.userId()).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "space_not_found", "Space not found", "Space not found"));
        if (actorRole != SpaceRole.SPACE_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "space_admin_required", "Forbidden",
                    "Space admin permission is required");
        }
        if (spaceRepository.findById(spaceId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "space_not_found", "Space not found", "Space not found");
        }
        if (!spaceRepository.userExists(userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "User not found", "User not found");
        }
        Instant now = Instant.now();
        long version = spaceRepository.upsertMembership(spaceId, userId, role, now);
        auditOutboxService.record("space.member.updated.v1", principal.userId(), spaceId, spaceId,
                correlationId(request), Map.of("spaceId", spaceId, "userId", userId, "role", role.name()));
        return new SpaceMember(spaceId, userId, role, version);
    }

    private UUID correlationId(HttpServletRequest request) {
        return UUID.fromString(CorrelationIdFilter.current(request));
    }
}
