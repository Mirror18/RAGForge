package com.ragforge.server.answer.api;

import com.ragforge.server.answer.infrastructure.ActiveRetrievalExecutionResolver;
import com.ragforge.server.answer.infrastructure.DeterministicRetrievalVersionIdentityProvider;
import com.ragforge.server.answer.infrastructure.JdbcRetrievalProfileVersionLoader;
import com.ragforge.server.shared.domain.ApiException;
import com.ragforge.server.knowledge.application.IndexRepository;
import com.ragforge.server.knowledge.domain.IndexState;
import com.ragforge.server.answer.infrastructure.PromptRepository;
import com.ragforge.server.space.application.ProviderRepository;
import com.ragforge.server.space.application.SpaceBindingRepository;
import com.ragforge.server.space.infrastructure.EgressDecision;
import com.ragforge.server.knowledge.application.RetrievalProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import com.ragforge.server.answer.domain.Answer;
import com.ragforge.server.space.infrastructure.EgressPolicy;

/**
 * Binds omitted answer technical IDs to the current space's active index,
 * retrieval profile, published prompt and authorized Chat route.
 */
@Component
public class SpaceAnswerBindingResolver {
    private final IndexRepository indexes;
    private final RetrievalProfileRepository profiles;
    private final ActiveRetrievalExecutionResolver.ProfileVersionLoader profileVersions;
    private final ActiveRetrievalExecutionResolver.VersionIdentityProvider identities;
    private final PromptRepository prompts;
    private final SpaceBindingRepository bindings;
    private final ProviderRepository providers;

    @Autowired
    public SpaceAnswerBindingResolver(IndexRepository indexes, RetrievalProfileRepository profiles,
                                      JdbcTemplate jdbc, PromptRepository prompts,
                                      SpaceBindingRepository bindings, ProviderRepository providers) {
        this(indexes, profiles, new JdbcRetrievalProfileVersionLoader(jdbc),
                new DeterministicRetrievalVersionIdentityProvider(indexes), prompts, bindings, providers);
    }

    SpaceAnswerBindingResolver(IndexRepository indexes, RetrievalProfileRepository profiles,
                               ActiveRetrievalExecutionResolver.ProfileVersionLoader profileVersions,
                               ActiveRetrievalExecutionResolver.VersionIdentityProvider identities,
                               PromptRepository prompts, SpaceBindingRepository bindings,
                               ProviderRepository providers) {
        this.indexes = Objects.requireNonNull(indexes, "indexes");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.profileVersions = Objects.requireNonNull(profileVersions, "profileVersions");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    public ResolvedAnswerBindings resolve(UUID spaceId, AnswerApiController.CreateAnswerRequest request) {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(request, "request");
        IndexRepository.ActiveIndexPointer indexPointer = indexes.findActivePointer(spaceId)
                .filter(item -> spaceId.equals(item.spaceId()))
                .orElseThrow(() -> missing("no_active_index", "This space has no active index"));
        IndexRepository.IndexVersion index = indexes.findVersion(spaceId, indexPointer.activeIndexVersionId())
                .filter(item -> item.state() == IndexState.ACTIVE && spaceId.equals(item.spaceId()))
                .orElseThrow(() -> missing("no_active_index", "This space has no active index"));
        RetrievalProfileRepository.ActiveProfilePointer profilePointer = profiles.findActivePointer(spaceId)
                .filter(item -> spaceId.equals(item.spaceId()))
                .orElseThrow(() -> missing("no_active_profile", "This space has no active retrieval profile"));
        RetrievalProfileRepository.RetrievalProfileVersion retrievalProfile = profileVersions
                .find(spaceId, profilePointer.activeProfileVersionId(), spaceId)
                .filter(item -> spaceId.equals(item.spaceId()))
                .orElseThrow(() -> missing("no_active_profile", "This space has no active retrieval profile"));
        ActiveRetrievalExecutionResolver.VersionIdentity identity = identities.resolve(
                spaceId, index.id(), retrievalProfile, spaceId);
        if (identity == null || identity.datasetHash() == null || identity.configHash() == null) {
            throw missing("no_active_index", "This space has no active index");
        }
        String datasetHash = optionalHash(request.datasetHash(), identity.datasetHash(), "datasetHash");
        String configHash = optionalHash(request.configHash(), identity.configHash(), "configHash");

        UUID promptVersionId = resolvePrompt(spaceId, request.promptVersionId());
        ChatRoute chat = resolveChat(spaceId, request);
        int maxContextTokens = request.maxContextTokens() == null
                ? Math.max(1, retrievalProfile.maxContextTokens()) : request.maxContextTokens();
        return new ResolvedAnswerBindings(promptVersionId, chat.routeId(), chat.profileId(), chat.model(),
                maxContextTokens, datasetHash, configHash, true);
    }

    private UUID resolvePrompt(UUID spaceId, UUID requested) {
        if (requested != null) {
            PromptRepository.PromptVersion version = prompts.findVersion(spaceId, requested)
                    .orElseThrow(() -> crossSpace("promptVersionId"));
            if (version.status() != PromptRepository.PromptStatus.PUBLISHED) {
                throw missing("no_published_prompt", "The requested prompt is not published in this space");
            }
            return version.id();
        }
        SpaceBindingRepository.SpaceBindingRecord binding = bindings.findCurrent(spaceId).orElse(null);
        if (binding != null && binding.promptVersionId() != null) {
            PromptRepository.PromptVersion bound = prompts.findVersion(spaceId, binding.promptVersionId())
                    .filter(item -> item.status() == PromptRepository.PromptStatus.PUBLISHED)
                    .orElse(null);
            if (bound != null) {
                return bound.id();
            }
        }
        return prompts.findLatestPublished(spaceId)
                .map(PromptRepository.PromptVersion::id)
                .orElseThrow(() -> missing("no_published_prompt", "This space has no published prompt"));
    }

    private ChatRoute resolveChat(UUID spaceId, AnswerApiController.CreateAnswerRequest request) {
        SpaceBindingRepository.SpaceBindingRecord binding = bindings.findCurrent(spaceId).orElse(null);
        UUID routeId = request.modelRouteVersionId();
        if (routeId == null) {
            if (binding == null || binding.chatRouteId() == null) {
                throw missing("no_authorized_chat_route", "This space has no authorized Chat route");
            }
            routeId = binding.chatRouteId();
        }
        ProviderRepository.ModelRouteVersion route = providers.findRouteVersion(spaceId, routeId)
                .orElseThrow(() -> request.modelRouteVersionId() == null
                        ? missing("no_authorized_chat_route", "This space has no authorized Chat route")
                        : crossSpace("modelRouteVersionId"));
        if (route.status() != ProviderRepository.ModelRouteStatus.PUBLISHED
                || route.purpose() != ProviderRepository.RoutePurpose.CHAT) {
            throw missing("no_authorized_chat_route", "This space has no authorized Chat route");
        }
        UUID profileId = request.modelProfileVersionId();
        ProviderRepository.ModelProfileVersion profile;
        if (profileId != null) {
            profile = providers.findProfileVersion(spaceId, profileId)
                    .orElseThrow(() -> crossSpace("modelProfileVersionId"));
            boolean candidate = providers.listRouteCandidates(spaceId, route.id()).stream()
                    .anyMatch(item -> profile.id().equals(item.profileVersionId()));
            if (!candidate) {
                throw missing("no_authorized_chat_route", "The requested model profile is not on this Chat route");
            }
        } else {
            profile = providers.listRouteCandidates(spaceId, route.id()).stream()
                    .map(item -> providers.findProfileVersion(spaceId, item.profileVersionId()).orElse(null))
                    .filter(item -> item != null && item.status() == ProviderRepository.ModelProfileStatus.PUBLISHED)
                    .findFirst()
                    .orElseThrow(() -> missing("no_authorized_chat_route",
                            "This space has no authorized Chat route"));
        }
        if (profile.status() != ProviderRepository.ModelProfileStatus.PUBLISHED) {
            throw missing("no_authorized_chat_route", "This space has no authorized Chat route");
        }
        String model = request.model() == null || request.model().isBlank() ? profile.modelName() : request.model();
        if (!profile.modelName().equals(model)) {
            throw missing("no_authorized_chat_route", "The requested model is not on this Chat route");
        }
        if (binding == null) {
            throw missing("no_authorized_chat_route", "This space has no authorized Chat route");
        }
        if (!authorizedCloud(route, binding)) {
            throw missing("cloud_egress_required", "Cloud egress is required and is not authorized for this space");
        }
        if (Boolean.FALSE.equals(request.allowCloudEgress())) {
            throw missing("cloud_egress_required", "Cloud egress is required and is not authorized for this space");
        }
        return new ChatRoute(route.id(), profile.id(), profile.modelName());
    }

    private static boolean authorizedCloud(ProviderRepository.ModelRouteVersion route,
                                           SpaceBindingRepository.SpaceBindingRecord binding) {
        if (route.egressPolicy() != ProviderRepository.EgressPolicy.CLOUD_ALLOWED || !route.allowCloudEgress()
                || !binding.cloudEgressEnabled()) {
            return false;
        }
        SpaceBindingRepository.CloudAuthorization authorization = binding.authorization();
        if (authorization == null || authorization.approvedAt() == null || authorization.expiresAt() == null
                || !authorization.approvedAt().isBefore(authorization.expiresAt())
                || !authorization.expiresAt().isAfter(Instant.now())) {
            return false;
        }
        String scope = authorization.scope() == null ? "" : authorization.scope().trim().toUpperCase();
        return "CHAT".equals(scope) || "ALL".equals(scope);
    }

    private static String optionalHash(String requested, String computed, String field) {
        if (requested == null || requested.isBlank()) {
            return computed;
        }
        if (!computed.equals(requested)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "cross_space_resource", "Not found",
                    field + " does not belong to this space");
        }
        return computed;
    }

    private static ApiException missing(String code, String detail) {
        HttpStatus status = "cloud_egress_required".equals(code) ? HttpStatus.FORBIDDEN : HttpStatus.UNPROCESSABLE_ENTITY;
        return new ApiException(status, code, "Answer binding missing", detail);
    }

    private static ApiException crossSpace(String field) {
        return new ApiException(HttpStatus.NOT_FOUND, "cross_space_resource", "Not found",
                field + " does not belong to this space");
    }

    public record ResolvedAnswerBindings(UUID promptVersionId, UUID modelRouteVersionId,
                                         UUID modelProfileVersionId, String model, int maxContextTokens,
                                         String datasetHash, String configHash, boolean allowCloudEgress) {
    }

    private record ChatRoute(UUID routeId, UUID profileId, String model) {
    }
}
