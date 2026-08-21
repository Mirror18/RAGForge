package com.ragforge.server.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.audit.AuditOutboxService;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.index.CandidateIndexStore;
import com.ragforge.server.index.IndexRepository;
import com.ragforge.server.provider.SpaceAuthorization;
import com.ragforge.server.retrieval.Bm25CandidateStore;
import com.ragforge.server.retrieval.ChunkCatalog;
import com.ragforge.server.retrieval.EvidenceBundle;
import com.ragforge.server.retrieval.ExpansionMode;
import com.ragforge.server.retrieval.Reranker;
import com.ragforge.server.retrieval.RetrievalCandidate;
import com.ragforge.server.retrieval.RetrievalProfileRepository;
import com.ragforge.server.retrieval.RetrievalService;
import com.ragforge.server.retrieval.RrfMerger;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
    @Mock CandidateIndexStore dense;
    @Mock Bm25CandidateStore bm25;
    @Mock ChunkCatalog catalog;
    @Mock Reranker reranker;
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
        CandidateIndexStore.CandidateHit hit = new CandidateIndexStore.CandidateHit(CHILD, 0.91, SPACE, INDEX,
                REVISION, PARENT, "chunk://child/1", HASH);
        RetrievalCandidate lexical = new RetrievalCandidate(SPACE, INDEX, CHILD, REVISION, PARENT,
                "chunk://child/1", HASH, 1.4, RetrievalCandidate.Source.BM25, "internal text");
        RrfMerger.MergedCandidate merged = new RrfMerger.MergedCandidate(SPACE, INDEX, CHILD, REVISION, PARENT,
                "chunk://child/1", HASH, 0.91, 1.4, 1, 1, 0.1, "internal text");
        when(dense.search(anyString(), eq(SPACE), eq(INDEX), eq(List.of(0.1, 0.2)), eq(5)))
                .thenReturn(List.of(hit));
        when(bm25.search(SPACE, INDEX, "hello world", 5)).thenReturn(List.of(lexical));
        when(reranker.rerank(anyString(), anyList(), eq(5)))
                .thenReturn(List.of(new Reranker.Result(merged, 0.8, "test")));
        EvidenceBundle bundle = new EvidenceBundle(SPACE, INDEX, PROFILE_A, 1, " hello  world ", "hello world",
                List.of(new EvidenceBundle.Evidence(UUID.randomUUID(), SPACE, INDEX, REVISION, PARENT, CHILD,
                        "chunk://child/1", HASH, new EvidenceBundle.Anchor(List.of("Guide"), 0, 10, 0, 20,
                                2, null, null, 10, 20, null), 0.91, 1.4, 0.1, 0.8, "direct-hit")), false, null);
        when(retrieval.retrieve(any())).thenReturn(bundle);

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
                .contains("evidenceId").doesNotContain("internal text");
        verify(indexes, never()).activate(any(), any(), any());
        verify(profiles, never()).activateProfile(any(), any(), anyInt(), any());
        ArgumentCaptor<Map<String, ?>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("retrieval.playground.experiment"), eq(principal.userId()), eq(SPACE), any(), any(),
                payload.capture());
        assertThat(payload.getValue()).doesNotContainKey("query").doesNotContainKey("queryVector")
                .doesNotContainKey("fullText");
    }

    private RetrievalPlaygroundService service() {
        return new RetrievalPlaygroundService(authorization, profiles, indexes, dense, bm25, catalog, reranker,
                retrieval, audit);
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
