package com.ragforge.server.studio;

import com.ragforge.server.audit.AuditOutboxService;
import com.ragforge.server.chunk.ChunkRepository;
import com.ragforge.server.chunk.OverrideState;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChunkStudioServiceTest {
    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID CHILD = UUID.randomUUID();
    private static final UUID PARENT = UUID.randomUUID();
    private static final UUID REVISION = UUID.randomUUID();
    private static final UUID NEW_REVISION = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final String HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock SpaceAuthorization authorization;
    @Mock StudioRepository studio;
    @Mock ChunkRepository chunks;
    @Mock AuditOutboxService audit;

    private final SessionPrincipal principal = new SessionPrincipal(USER, UUID.randomUUID(), "u@test", "User",
            "csrf", "USER", Instant.MAX);

    @Test
    void childProjectionContainsOnlyProvenanceAndIndexMetadata() {
        when(studio.findChild(SPACE, CHILD)).thenReturn(Optional.of(child(NEW_REVISION)));
        when(studio.findVectorStatus(eq(SPACE), eq(CHILD), any())).thenReturn(
                new StudioRepository.VectorStatus("INDEXED", UUID.randomUUID(), 768, NOW));
        when(chunks.findLatestOverride(SPACE, CHILD)).thenReturn(Optional.empty());

        ChunkStudioService.ChunkStudioChildProjection result = service().getChild(SPACE, CHILD, principal);

        assertThat(result.contentRef()).isEqualTo("chunk://child/1");
        assertThat(result.provenance().sourcePath()).isEqualTo("docs/guide.md");
        assertThat(result.anchor().headingPath()).containsExactly("Guide");
        assertThat(result.vectorStatus().state()).isEqualTo("INDEXED");
        assertThat(result.override().state()).isEqualTo(OverrideState.NONE);
    }

    @Test
    void newerRevisionStartsNeedsReviewAndAuditHasNoBodyFields() {
        when(studio.findChild(SPACE, CHILD)).thenReturn(Optional.of(child(REVISION)));
        when(studio.documentRevisionExists(SPACE, NEW_REVISION)).thenReturn(true);
        ChunkRepository.ChunkOverride created = new ChunkRepository.ChunkOverride(UUID.randomUUID(), SPACE, CHILD,
                NEW_REVISION, 1, OverrideState.NEEDS_REVIEW, "fix", HASH, USER, NOW, NOW,
                "opaque://replacement/1");
        when(chunks.createOverride(any())).thenReturn(created);

        ChunkStudioService.OverrideResponse response = service().createOverride(SPACE, CHILD,
                new ChunkStudioService.CreateOverrideRequest(NEW_REVISION, "opaque://replacement/1", HASH, "fix"),
                principal, UUID.randomUUID());

        assertThat(response.override().state()).isEqualTo(OverrideState.NEEDS_REVIEW);
        assertThat(response.contentRef()).isEqualTo("opaque://replacement/1");
        ArgumentCaptor<Map<String, ?>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("chunk.override.created"), eq(USER), eq(SPACE), eq(created.id()), any(), payload.capture());
        assertThat(payload.getValue()).doesNotContainKey("fullText").doesNotContainKey("rawText")
                .doesNotContainKey("vector").doesNotContainKey("queryVector");
    }

    @Test
    void staleVersionAndForbiddenTransitionFailClosed() {
        when(studio.findChild(SPACE, CHILD)).thenReturn(Optional.of(child(REVISION)));
        UUID overrideId = UUID.randomUUID();
        ChunkRepository.ChunkOverride current = new ChunkRepository.ChunkOverride(overrideId, SPACE, CHILD, REVISION,
                2, OverrideState.ACTIVE, "fix", HASH, USER, NOW, NOW, "opaque://replacement/1");
        when(chunks.findById(SPACE, overrideId)).thenReturn(Optional.of(current));
        when(chunks.findLatestOverride(SPACE, CHILD)).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service().transition(SPACE, CHILD, overrideId,
                new ChunkStudioService.TransitionRequest(OverrideState.NEEDS_REVIEW, 1, "stale"), principal,
                UUID.randomUUID())).isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.CONFLICT);

        assertThatThrownBy(() -> service().transition(SPACE, CHILD, overrideId,
                new ChunkStudioService.TransitionRequest(OverrideState.DISCARDED, 2, "forbidden"), principal,
                UUID.randomUUID())).isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void transitionUsesPersistedReferenceBeforeHistoricalAuditFallback() {
        when(studio.findChild(SPACE, CHILD)).thenReturn(Optional.of(child(REVISION)));
        UUID overrideId = UUID.randomUUID();
        ChunkRepository.ChunkOverride current = new ChunkRepository.ChunkOverride(overrideId, SPACE, CHILD, REVISION,
                1, OverrideState.ACTIVE, "fix", HASH, USER, NOW, NOW, "opaque://replacement/1");
        ChunkRepository.ChunkOverride updated = new ChunkRepository.ChunkOverride(UUID.randomUUID(), SPACE, CHILD,
                REVISION, 2, OverrideState.NEEDS_REVIEW, "fix", HASH, USER, NOW, NOW.plusSeconds(60),
                "opaque://replacement/1");
        when(chunks.findById(SPACE, overrideId)).thenReturn(Optional.of(current));
        when(chunks.findLatestOverride(SPACE, CHILD)).thenReturn(Optional.of(current));
        when(chunks.updateOverrideState(eq(SPACE), eq(overrideId), eq(OverrideState.NEEDS_REVIEW), any()))
                .thenReturn(updated);

        ChunkStudioService.OverrideResponse response = service().transition(SPACE, CHILD, overrideId,
                new ChunkStudioService.TransitionRequest(OverrideState.NEEDS_REVIEW, 1, "review"), principal,
                UUID.randomUUID());

        assertThat(response.contentRef()).isEqualTo("opaque://replacement/1");
        verify(studio, never()).findOverrideContentRef(any(), any());
    }

    @Test
    void sensitiveOpaqueReferenceAndViewerWriteAreRejected() {
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "space_editor_required", "Forbidden", "write"))
                .when(authorization).requireWrite(SPACE, principal);

        assertThatThrownBy(() -> service().createOverride(SPACE, CHILD,
                new ChunkStudioService.CreateOverrideRequest(REVISION, "fullText://secret", HASH, "fix"),
                principal, UUID.randomUUID())).isInstanceOf(ApiException.class);
    }

    @Test
    void blankAndSensitiveOpaqueReferencesAreRejected() {
        when(studio.findChild(SPACE, CHILD)).thenReturn(Optional.of(child(REVISION)));

        assertThatThrownBy(() -> service().createOverride(SPACE, CHILD,
                new ChunkStudioService.CreateOverrideRequest(REVISION, " ", HASH, "fix"),
                principal, UUID.randomUUID())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service().createOverride(SPACE, CHILD,
                new ChunkStudioService.CreateOverrideRequest(REVISION, "opaque://fullText/1", HASH, "fix"),
                principal, UUID.randomUUID())).isInstanceOf(ApiException.class);
    }

    private ChunkStudioService service() {
        return new ChunkStudioService(authorization, studio, chunks, audit);
    }

    private static StudioRepository.ChildStudioRow child(UUID revision) {
        return new StudioRepository.ChildStudioRow(SPACE, CHILD, PARENT, revision, 1,
                "chunk://child/1", HASH, UUID.randomUUID(), UUID.randomUUID(), "docs/guide.md", 2,
                "chunk://parent/1", List.of("Guide"), 3, null, null, 10, 20, null, NOW);
    }
}
