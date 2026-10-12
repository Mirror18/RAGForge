package com.ragforge.server.answer.api;

import com.ragforge.server.answer.integration.ActiveRetrievalExecutionResolver;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.index.IndexRepository;
import com.ragforge.server.index.IndexState;
import com.ragforge.server.ingestion.IngestionRepository;
import com.ragforge.server.prompt.PromptRepository;
import com.ragforge.server.provider.ProviderRepository;
import com.ragforge.server.provider.SpaceBindingRepository;
import com.ragforge.server.retrieval.ExpansionMode;
import com.ragforge.server.retrieval.RetrievalProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnswerReadinessServiceTest {
    private final UUID spaceId = UuidV7.random();
    private final UUID otherSpace = UuidV7.random();
    private final UUID indexId = UuidV7.random();
    private final UUID retrievalProfileId = UuidV7.random();
    private final UUID promptId = UuidV7.random();
    private final UUID routeId = UuidV7.random();
    private final UUID modelProfileId = UuidV7.random();

    private IngestionRepository ingestion;
    private IndexRepository indexes;
    private RetrievalProfileRepository profiles;
    private ActiveRetrievalExecutionResolver.ProfileVersionLoader profileVersions;
    private PromptRepository prompts;
    private SpaceBindingRepository bindings;
    private ProviderRepository providers;
    private AnswerReadinessService service;

    @BeforeEach
    void setUp() {
        ingestion = mock(IngestionRepository.class);
        indexes = mock(IndexRepository.class);
        profiles = mock(RetrievalProfileRepository.class);
        profileVersions = mock(ActiveRetrievalExecutionResolver.ProfileVersionLoader.class);
        prompts = mock(PromptRepository.class);
        bindings = mock(SpaceBindingRepository.class);
        providers = mock(ProviderRepository.class);
        service = new AnswerReadinessService(true, ingestion, indexes, profiles, profileVersions, prompts,
                bindings, providers);
        stubReadySpace();
    }

    @Test
    void readyWhenEveryGapIsClosed() {
        AnswerReadinessService.AnswerReadiness readiness = service.inspect(spaceId);
        assertThat(readiness.ready()).isTrue();
        assertThat(readiness.gaps()).isEmpty();
        assertThat(readiness.spaceId()).isEqualTo(spaceId);
    }

    @Test
    void singleOpenGapReturnsOnlyThatGap() {
        when(bindings.findCurrent(spaceId)).thenReturn(Optional.of(binding(false)));
        AnswerReadinessService.AnswerReadiness readiness = service.inspect(spaceId);
        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.gaps()).extracting(AnswerReadinessService.Gap::code)
                .containsExactly("cloud_egress_required");
        assertThat(readiness.gaps()).extracting(AnswerReadinessService.Gap::path)
                .containsExactly("?view=control&section=providers");
    }

    @Test
    void firstOpenGapOmitsEarlierClosedGapsAndListsLaterOpenGaps() {
        when(indexes.findActivePointer(spaceId)).thenReturn(Optional.empty());
        when(profiles.findActivePointer(spaceId)).thenReturn(Optional.empty());
        AnswerReadinessService.AnswerReadiness readiness = service.inspect(spaceId);
        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.gaps()).extracting(AnswerReadinessService.Gap::code)
                .containsExactly("no_active_index", "no_active_profile");
        assertThat(readiness.gaps()).extracting(AnswerReadinessService.Gap::code)
                .doesNotContain("object_storage_disabled", "no_documents");
    }

    @Test
    void otherSpacePointersAreNotVisible() {
        AnswerReadinessService.AnswerReadiness foreign = service.inspect(otherSpace);
        assertThat(foreign.spaceId()).isEqualTo(otherSpace);
        assertThat(foreign.gaps()).extracting(AnswerReadinessService.Gap::code)
                .contains("no_documents", "no_active_index", "no_active_profile", "no_published_prompt",
                        "no_authorized_chat_route", "cloud_egress_required");
        assertThat(foreign.gaps()).extracting(AnswerReadinessService.Gap::code)
                .doesNotContain("object_storage_disabled");
    }

    private void stubReadySpace() {
        when(ingestion.listSourceDocuments(spaceId)).thenReturn(List.of(mock(IngestionRepository.SourceDocument.class)));
        when(ingestion.listSourceDocuments(otherSpace)).thenReturn(List.of());
        when(indexes.findActivePointer(spaceId)).thenReturn(Optional.of(new IndexRepository.ActiveIndexPointer(
                UuidV7.random(), spaceId, indexId, null, 1, Instant.now())));
        when(indexes.findVersion(spaceId, indexId)).thenReturn(Optional.of(new IndexRepository.IndexVersion(
                indexId, spaceId, 1, IndexState.ACTIVE, "col", "emb-v1", "chunk-v1", 1, 1, null,
                Instant.now(), null, null, Instant.now())));
        when(profiles.findActivePointer(spaceId)).thenReturn(Optional.of(
                new RetrievalProfileRepository.ActiveProfilePointer(UuidV7.random(), spaceId, retrievalProfileId, 1,
                        Instant.now())));
        RetrievalProfileRepository.RetrievalProfileVersion retrieval = new RetrievalProfileRepository.RetrievalProfileVersion(
                retrievalProfileId, spaceId, UuidV7.random(), 1, 8, 8, 60, 0.5, 0.5, 8, 5,
                ExpansionMode.PARENT, 1, 1, 800, Instant.now());
        when(profileVersions.find(spaceId, retrievalProfileId, spaceId)).thenReturn(Optional.of(retrieval));
        when(bindings.findCurrent(spaceId)).thenReturn(Optional.of(binding(true)));
        when(prompts.findVersion(spaceId, promptId)).thenReturn(Optional.of(prompt(promptId)));
        when(providers.findRouteVersion(spaceId, routeId)).thenReturn(Optional.of(route()));
        when(providers.listRouteCandidates(spaceId, routeId)).thenReturn(List.of(candidate()));
        when(providers.findProfileVersion(spaceId, modelProfileId)).thenReturn(Optional.of(modelProfile()));
    }

    private SpaceBindingRepository.SpaceBindingRecord binding(boolean cloud) {
        Instant now = Instant.now();
        SpaceBindingRepository.CloudAuthorization authorization = cloud
                ? new SpaceBindingRepository.CloudAuthorization(UuidV7.random(), UuidV7.random(),
                now.minusSeconds(60), now.plusSeconds(3600), "CHAT") : null;
        return new SpaceBindingRepository.SpaceBindingRecord(UuidV7.random(), spaceId, 1, routeId, UuidV7.random(),
                UuidV7.random(), promptId, cloud, authorization, now, now, UuidV7.random());
    }

    private PromptRepository.PromptVersion prompt(UUID id) {
        Instant now = Instant.now();
        return new PromptRepository.PromptVersion(id, spaceId, "rag", 1, "template", "c".repeat(64), "{}", "{}",
                "note", UuidV7.random(), PromptRepository.PromptStatus.PUBLISHED, now, now, UuidV7.random());
    }

    private ProviderRepository.ModelRouteVersion route() {
        Instant now = Instant.now();
        return new ProviderRepository.ModelRouteVersion(routeId, spaceId, "chat", 1,
                ProviderRepository.RoutePurpose.CHAT, ProviderRepository.EgressPolicy.CLOUD_ALLOWED, true,
                ProviderRepository.SelectionPolicy.SINGLE, "{}", ProviderRepository.ModelRouteStatus.PUBLISHED,
                now, now, UuidV7.random());
    }

    private ProviderRepository.ModelProfileVersion modelProfile() {
        Instant now = Instant.now();
        return new ProviderRepository.ModelProfileVersion(modelProfileId, spaceId, UuidV7.random(), "chat-profile", 1,
                "cloud-chat", "{}", "{}", "{}", 8192, 1024, null, "tok", "{}", null, "{}",
                ProviderRepository.ModelProfileStatus.PUBLISHED, now, now, UuidV7.random());
    }

    private ProviderRepository.RouteCandidate candidate() {
        Instant now = Instant.now();
        return new ProviderRepository.RouteCandidate(UuidV7.random(), spaceId, routeId, 1, modelProfileId,
                now, now, UuidV7.random());
    }
}
