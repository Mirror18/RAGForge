package com.ragforge.server.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.audit.AuditOutboxService;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.index.IndexRepository;
import com.ragforge.server.provider.SpaceAuthorization;
import com.ragforge.server.retrieval.EvidenceBundle;
import com.ragforge.server.retrieval.ExpansionMode;
import com.ragforge.server.retrieval.RetrievalProfileRepository;
import com.ragforge.server.retrieval.RetrievalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalPlaygroundServiceTest {
    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID INDEX = UUID.randomUUID();
    private static final UUID PROFILE_A = UUID.randomUUID();
    private static final UUID PROFILE_B = UUID.randomUUID();
    private static final UUID REVISION = UUID.randomUUID();
    private static final UUID PARENT = UUID.randomUUID();
    private static final UUID CHILD = UUID.randomUUID();
    private static final String HASH = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock SpaceAuthorization authorization;
    @Mock RetrievalProfileRepository profiles;
    @Mock IndexRepository indexes;
    @Mock RetrievalService retrieval;
    @Mock AuditOutboxService audit;

    private final SessionPrincipal principal = new SessionPrincipal(UUID.randomUUID(), UUID.randomUUID(), "u@test",
            "User", "csrf", "USER", Instant.MAX);

    @Test
    void missingOrInvalidQueryVectorFailsClosedBeforeAnyRetrievalAccess() {
        RetrievalPlaygroundService service = service();
        RetrievalPlaygroundService.ExperimentRequest request = request(null);

        assertThatThrownBy(() -> service.run(SPACE, request, principal, UUID.randomUUID()))
                .isInstanceOf(ApiException.class).extracting("status")
                .isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        verify(profiles, never()).findVersion(any(), any(), anyInt());
        verify(indexes, never()).findVersion(any(), any());
    }

    @Test
    void abExperimentReturnsRealStageReferencesAndDoesNotExposeSensitiveValuesOrChangePointers() throws Exception {
        RetrievalProfileRepository.RetrievalProfileVersion profileA = profile(PROFILE_A, 1);
        RetrievalProfileRepository.RetrievalProfileVersion profileB = profile(PROFILE_B, 3);
        when(indexes.findVersion(SPACE, INDEX)).thenReturn(java.util.Optional.of(index()));
        when(profiles.findVersion(SPACE, PROFILE_A, 1)).thenReturn(java.util.Optional.of(profileA));
        when(profiles.findVersion(SPACE, PROFILE_B, 3)).thenReturn(java.util.Optional.of(profileB));
        EvidenceBundle bundle = new EvidenceBundle(SPACE, INDEX, PROFILE_A, 1, " hello  world ", "hello world",
                List.of(new EvidenceBundle.Evidence(UUID.randomUUID(), SPACE, INDEX, REVISION, PARENT, CHILD,
                        "chunk://child/1", HASH, new EvidenceBundle.Anchor(List.of("Guide"), 0, 10, 0, 20,
                                2, null, null, 10, 20, null), 0.91, 1.4, 0.1, 0.8, "direct-hit")), false, null);
        RetrievalService.TraceCandidate dense = new RetrievalService.TraceCandidate(CHILD, REVISION,
                "chunk://child/1", HASH, 1, 0.91, "dense");
        RetrievalService.TraceCandidate bm25 = new RetrievalService.TraceCandidate(CHILD, REVISION,
                "chunk://child/1", HASH, 1, 1.4, "bm25");
        RetrievalService.TraceCandidate rrf = new RetrievalService.TraceCandidate(CHILD, REVISION,
                "chunk://child/1", HASH, 1, 0.1, "rrf");
        RetrievalService.TraceCandidate rerank = new RetrievalService.TraceCandidate(CHILD, REVISION,
                "chunk://child/1", HASH, 1, 0.8, "test");
        RetrievalService.Trace serviceTrace = new RetrievalService.Trace(
                new RetrievalService.StageTrace(List.of(dense), 0.1),
                new RetrievalService.StageTrace(List.of(bm25), 0.2),
                new RetrievalService.StageTrace(List.of(rrf), 0.3),
                new RetrievalService.StageTrace(List.of(rerank), 0.4),
                new RetrievalService.ContextTrace(List.of(CHILD), 10, 1000, false), bundle);
        when(retrieval.trace(any())).thenReturn(serviceTrace);

        RetrievalPlaygroundService.Experiment result = service().run(SPACE,
                new RetrievalPlaygroundService.ExperimentRequest(" hello  world ", INDEX,
                        new RetrievalPlaygroundService.ProfileRef(PROFILE_A, 1, true),
                        new RetrievalPlaygroundService.ProfileRef(PROFILE_B, 3, true), List.of(0.1, 0.2)),
                principal, UUID.randomUUID());

        assertThat(result.normalizedQuery()).isEqualTo("hello world");
        assertThat(result.profileA().profile().candidateOnly()).isTrue();
        assertThat(result.profileA().profile().profileId()).isEqualTo(PROFILE_A);
        assertThat(result.profileB().profile().profileId()).isEqualTo(PROFILE_B);
        assertThat(result.profileA().trace().dense().items()).extracting("childChunkId").containsExactly(CHILD);
        assertThat(result.profileA().trace().evidence().items()).hasSize(1)
                .allMatch(value -> value.citationAllowed());
        assertThat(result.activeProfileUnchanged()).isTrue();
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(json).doesNotContain("queryVector", "fullText", "rawText", "vector", "credential", "secret")
                .doesNotContain("searchableText", "internal text").contains("evidenceId");
        verify(indexes, never()).activate(any(), any(), any());
        verify(profiles, never()).activateProfile(any(), any(), anyInt(), any());
        ArgumentCaptor<Map<String, ?>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("retrieval.playground.experiment"), eq(principal.userId()), eq(SPACE), any(), any(),
                payload.capture());
        assertThat(payload.getValue()).doesNotContainKey("query").doesNotContainKey("queryVector")
                .doesNotContainKey("fullText");
        verify(retrieval, never()).retrieve(any());
        ArgumentCaptor<RetrievalService.Request> retrievalRequests = ArgumentCaptor.forClass(RetrievalService.Request.class);
        verify(retrieval, org.mockito.Mockito.times(2)).trace(retrievalRequests.capture());
        assertThat(retrievalRequests.getAllValues()).extracting(request -> request.profile().profileId())
                .containsExactly(PROFILE_A, PROFILE_B);
    }

    private RetrievalPlaygroundService service() {
        return new RetrievalPlaygroundService(authorization, profiles, indexes, retrieval, audit);
    }

    private static RetrievalPlaygroundService.ExperimentRequest request(List<Double> queryVector) {
        return new RetrievalPlaygroundService.ExperimentRequest("hello", INDEX,
                new RetrievalPlaygroundService.ProfileRef(PROFILE_A, 1, true), null, queryVector);
    }

    private static RetrievalProfileRepository.RetrievalProfileVersion profile(UUID profileId, int version) {
        return new RetrievalProfileRepository.RetrievalProfileVersion(UUID.randomUUID(), SPACE, profileId, version,
                5, 5, 60, 0.5, 0.5, 5, 5, ExpansionMode.NONE, 0, 0, 1000, NOW);
    }

    private static IndexRepository.IndexVersion index() {
        return new IndexRepository.IndexVersion(INDEX, SPACE, 1, com.ragforge.server.index.IndexState.READY,
                "collection", "embedding-v1", "chunk-v1", 1, 1, null, null, null, null, NOW);
    }
}
