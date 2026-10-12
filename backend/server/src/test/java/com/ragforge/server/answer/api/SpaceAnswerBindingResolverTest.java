package com.ragforge.server.answer.api;

import com.ragforge.server.answer.integration.ActiveRetrievalExecutionResolver;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.index.IndexRepository;
import com.ragforge.server.index.IndexState;
import com.ragforge.server.prompt.PromptRepository;
import com.ragforge.server.provider.ProviderRepository;
import com.ragforge.server.provider.SpaceBindingRepository;
import com.ragforge.server.retrieval.ExpansionMode;
import com.ragforge.server.retrieval.RetrievalProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpaceAnswerBindingResolverTest {
    private final UUID spaceId = UuidV7.random();
    private final UUID otherSpace = UuidV7.random();
    private final UUID runId = UuidV7.random();
    private final UUID indexId = UuidV7.random();
    private final UUID retrievalProfileId = UuidV7.random();
    private final UUID promptId = UuidV7.random();
    private final UUID routeId = UuidV7.random();
    private final UUID modelProfileId = UuidV7.random();
    private final String datasetHash = "a".repeat(64);
    private final String configHash = "b".repeat(64);

    private IndexRepository indexes;
    private RetrievalProfileRepository profiles;
    private ActiveRetrievalExecutionResolver.ProfileVersionLoader profileVersions;
    private ActiveRetrievalExecutionResolver.VersionIdentityProvider identities;
    private PromptRepository prompts;
    private SpaceBindingRepository bindings;
    private ProviderRepository providers;
    private SpaceAnswerBindingResolver resolver;

    @BeforeEach
    void setUp() {
        indexes = mock(IndexRepository.class);
        profiles = mock(RetrievalProfileRepository.class);
        profileVersions = mock(ActiveRetrievalExecutionResolver.ProfileVersionLoader.class);
        identities = mock(ActiveRetrievalExecutionResolver.VersionIdentityProvider.class);
        prompts = mock(PromptRepository.class);
        bindings = mock(SpaceBindingRepository.class);
        providers = mock(ProviderRepository.class);
        resolver = new SpaceAnswerBindingResolver(indexes, profiles, profileVersions, identities, prompts,
                bindings, providers);
        stubReadySpace();
    }

    @Test
    void omittedFieldsResolveToSpaceBindings() {
        SpaceAnswerBindingResolver.ResolvedAnswerBindings resolved = resolver.resolve(spaceId, omitted());
        assertThat(resolved.promptVersionId()).isEqualTo(promptId);
        assertThat(resolved.modelRouteVersionId()).isEqualTo(routeId);
        assertThat(resolved.modelProfileVersionId()).isEqualTo(modelProfileId);
        assertThat(resolved.model()).isEqualTo("cloud-chat");
        assertThat(resolved.datasetHash()).isEqualTo(datasetHash);
        assertThat(resolved.configHash()).isEqualTo(configHash);
        assertThat(resolved.allowCloudEgress()).isTrue();
        assertThat(resolved.maxContextTokens()).isEqualTo(800);
    }

    @Test
    void explicitIdsOverrideDefaultsWhenTheyBelongToTheSpace() {
        UUID otherPrompt = UuidV7.random();
        UUID otherProfile = UuidV7.random();
        when(prompts.findVersion(spaceId, otherPrompt)).thenReturn(Optional.of(prompt(otherPrompt)));
        when(providers.findProfileVersion(spaceId, otherProfile)).thenReturn(Optional.of(modelProfile(otherProfile)));
        when(providers.listRouteCandidates(spaceId, routeId)).thenReturn(List.of(
                candidate(modelProfileId), candidate(otherProfile)));
        AnswerApiController.CreateAnswerRequest request = new AnswerApiController.CreateAnswerRequest(runId,
                "What is verified?", otherPrompt, routeId, otherProfile, "cloud-chat", 1200, 30, "{}",
                datasetHash, configHash, true);

        SpaceAnswerBindingResolver.ResolvedAnswerBindings resolved = resolver.resolve(spaceId, request);
        assertThat(resolved.promptVersionId()).isEqualTo(otherPrompt);
        assertThat(resolved.modelProfileVersionId()).isEqualTo(otherProfile);
        assertThat(resolved.maxContextTokens()).isEqualTo(1200);
    }

    @Test
    void crossSpaceIdsAreRejected() {
        UUID foreign = UuidV7.random();
        when(prompts.findVersion(spaceId, foreign)).thenReturn(Optional.empty());
        AnswerApiController.CreateAnswerRequest request = new AnswerApiController.CreateAnswerRequest(runId,
                "What is verified?", foreign, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> resolver.resolve(spaceId, request))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException exception = (ApiException) error;
                    assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.code()).isEqualTo("cross_space_resource");
                });
        assertThat(otherSpace).isNotEqualTo(spaceId);
    }

    @Test
    void missingActiveIndexReturnsStableCode() {
        when(indexes.findActivePointer(spaceId)).thenReturn(Optional.empty());
        assertCode(omitted(), "no_active_index", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void missingActiveProfileReturnsStableCode() {
        when(profiles.findActivePointer(spaceId)).thenReturn(Optional.empty());
        assertCode(omitted(), "no_active_profile", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void missingPublishedPromptReturnsStableCode() {
        when(bindings.findCurrent(spaceId)).thenReturn(Optional.of(binding(false)));
        when(prompts.findVersion(eq(spaceId), any())).thenReturn(Optional.empty());
        when(prompts.findLatestPublished(spaceId)).thenReturn(Optional.empty());
        assertCode(omitted(), "no_published_prompt", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void missingChatRouteReturnsStableCode() {
        when(bindings.findCurrent(spaceId)).thenReturn(Optional.empty());
        when(prompts.findLatestPublished(spaceId)).thenReturn(Optional.of(prompt(promptId)));
        assertCode(omitted(), "no_authorized_chat_route", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void unauthorizedCloudEgressReturnsStableCode() {
        when(bindings.findCurrent(spaceId)).thenReturn(Optional.of(binding(false)));
        assertCode(omitted(), "cloud_egress_required", HttpStatus.FORBIDDEN);
    }

    private void assertCode(AnswerApiController.CreateAnswerRequest request, String code, HttpStatus status) {
        assertThatThrownBy(() -> resolver.resolve(spaceId, request))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException exception = (ApiException) error;
                    assertThat(exception.code()).isEqualTo(code);
                    assertThat(exception.status()).isEqualTo(status);
                });
    }

    private void stubReadySpace() {
        when(indexes.findActivePointer(spaceId)).thenReturn(Optional.of(new IndexRepository.ActiveIndexPointer(
                UuidV7.random(), spaceId, indexId, null, 1, Instant.parse("2026-10-12T00:00:00Z"))));
        when(indexes.findVersion(spaceId, indexId)).thenReturn(Optional.of(new IndexRepository.IndexVersion(
                indexId, spaceId, 1, IndexState.ACTIVE, "col", "emb-v1", "chunk-v1", 1, 1, null,
                Instant.parse("2026-10-12T00:00:00Z"), null, null, Instant.parse("2026-10-12T00:00:00Z"))));
        when(profiles.findActivePointer(spaceId)).thenReturn(Optional.of(
                new RetrievalProfileRepository.ActiveProfilePointer(UuidV7.random(), spaceId, retrievalProfileId, 1,
                        Instant.parse("2026-10-12T00:00:00Z"))));
        RetrievalProfileRepository.RetrievalProfileVersion retrieval = new RetrievalProfileRepository.RetrievalProfileVersion(
                retrievalProfileId, spaceId, UuidV7.random(), 1, 8, 8, 60, 0.5, 0.5, 8, 5,
                ExpansionMode.PARENT, 1, 1, 800, Instant.parse("2026-10-12T00:00:00Z"));
        when(profileVersions.find(spaceId, retrievalProfileId, spaceId)).thenReturn(Optional.of(retrieval));
        when(identities.resolve(eq(spaceId), eq(indexId), eq(retrieval), eq(spaceId)))
                .thenReturn(new ActiveRetrievalExecutionResolver.VersionIdentity(UuidV7.random(), 1,
                        "evidence-bundle:" + indexId, datasetHash, configHash));
        when(bindings.findCurrent(spaceId)).thenReturn(Optional.of(binding(true)));
        when(prompts.findVersion(spaceId, promptId)).thenReturn(Optional.of(prompt(promptId)));
        when(prompts.findLatestPublished(spaceId)).thenReturn(Optional.of(prompt(promptId)));
        when(providers.findRouteVersion(spaceId, routeId)).thenReturn(Optional.of(route()));
        when(providers.listRouteCandidates(spaceId, routeId)).thenReturn(List.of(candidate(modelProfileId)));
        when(providers.findProfileVersion(spaceId, modelProfileId)).thenReturn(Optional.of(modelProfile(modelProfileId)));
    }

    private AnswerApiController.CreateAnswerRequest omitted() {
        return new AnswerApiController.CreateAnswerRequest(runId, "What is verified?", null, null, null, null,
                null, null, null, null, null, null);
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
        Instant now = Instant.parse("2026-10-12T00:00:00Z");
        return new PromptRepository.PromptVersion(id, spaceId, "rag", 1, "template", "c".repeat(64), "{}", "{}",
                "note", UuidV7.random(), PromptRepository.PromptStatus.PUBLISHED, now, now, UuidV7.random());
    }

    private ProviderRepository.ModelRouteVersion route() {
        Instant now = Instant.parse("2026-10-12T00:00:00Z");
        return new ProviderRepository.ModelRouteVersion(routeId, spaceId, "chat", 1,
                ProviderRepository.RoutePurpose.CHAT, ProviderRepository.EgressPolicy.CLOUD_ALLOWED, true,
                ProviderRepository.SelectionPolicy.SINGLE, "{}", ProviderRepository.ModelRouteStatus.PUBLISHED,
                now, now, UuidV7.random());
    }

    private ProviderRepository.ModelProfileVersion modelProfile(UUID id) {
        Instant now = Instant.parse("2026-10-12T00:00:00Z");
        return new ProviderRepository.ModelProfileVersion(id, spaceId, UuidV7.random(), "chat-profile", 1,
                "cloud-chat", "{}", "{}", "{}", 8192, 1024, null, "tok", "{}", null, "{}",
                ProviderRepository.ModelProfileStatus.PUBLISHED, now, now, UuidV7.random());
    }

    private ProviderRepository.RouteCandidate candidate(UUID profileVersionId) {
        Instant now = Instant.parse("2026-10-12T00:00:00Z");
        return new ProviderRepository.RouteCandidate(UuidV7.random(), spaceId, routeId, 1, profileVersionId,
                now, now, UuidV7.random());
    }
}
