package com.ragforge.server.answer.api;

import com.ragforge.server.knowledge.application.IndexRepository;
import com.ragforge.server.knowledge.domain.IndexState;
import com.ragforge.server.knowledge.application.IngestionRepository;
import com.ragforge.server.answer.infrastructure.PromptRepository;
import com.ragforge.server.space.application.ProviderRepository;
import com.ragforge.server.space.application.SpaceBindingRepository;
import com.ragforge.server.knowledge.application.RetrievalProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.ragforge.server.answer.infrastructure.ActiveRetrievalExecutionResolver;
import com.ragforge.server.space.infrastructure.EgressPolicy;
import com.ragforge.server.answer.infrastructure.JdbcRetrievalProfileVersionLoader;

/** Space-scoped, credential-free checklist for creating a cited answer. */
@Service
public class AnswerReadinessService {
    private static final List<GapDefinition> ORDER = List.of(
            new GapDefinition("object_storage_disabled", "?view=control&section=health"),
            new GapDefinition("no_documents", "?view=flow"),
            new GapDefinition("no_active_index", "?view=flow"),
            new GapDefinition("no_active_profile", "?view=playground"),
            new GapDefinition("no_published_prompt", "?view=control&section=prompts"),
            new GapDefinition("no_authorized_chat_route", "?view=control&section=providers"),
            new GapDefinition("cloud_egress_required", "?view=control&section=providers")
    );

    private final boolean objectStorageEnabled;
    private final IngestionRepository ingestion;
    private final IndexRepository indexes;
    private final RetrievalProfileRepository profiles;
    private final com.ragforge.server.answer.infrastructure.ActiveRetrievalExecutionResolver.ProfileVersionLoader profileVersions;
    private final PromptRepository prompts;
    private final SpaceBindingRepository bindings;
    private final ProviderRepository providers;

    @Autowired
    public AnswerReadinessService(
            @Value("${ragforge.object-storage.enabled:false}") boolean objectStorageEnabled,
            IngestionRepository ingestion, IndexRepository indexes, RetrievalProfileRepository profiles,
            JdbcTemplate jdbc, PromptRepository prompts, SpaceBindingRepository bindings,
            ProviderRepository providers) {
        this(objectStorageEnabled, ingestion, indexes, profiles,
                new com.ragforge.server.answer.infrastructure.JdbcRetrievalProfileVersionLoader(jdbc),
                prompts, bindings, providers);
    }

    AnswerReadinessService(boolean objectStorageEnabled, IngestionRepository ingestion, IndexRepository indexes,
                           RetrievalProfileRepository profiles,
                           com.ragforge.server.answer.infrastructure.ActiveRetrievalExecutionResolver.ProfileVersionLoader profileVersions,
                           PromptRepository prompts, SpaceBindingRepository bindings, ProviderRepository providers) {
        this.objectStorageEnabled = objectStorageEnabled;
        this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
        this.indexes = Objects.requireNonNull(indexes, "indexes");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.profileVersions = Objects.requireNonNull(profileVersions, "profileVersions");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    public AnswerReadiness inspect(UUID spaceId) {
        Objects.requireNonNull(spaceId, "spaceId");
        boolean[] open = {
                !objectStorageEnabled,
                ingestion.listSourceDocuments(spaceId).isEmpty(),
                !hasActiveIndex(spaceId),
                !hasActiveProfile(spaceId),
                !hasPublishedPrompt(spaceId),
                !hasAuthorizedChatRoute(spaceId),
                !hasCloudEgress(spaceId)
        };
        List<Gap> gaps = new ArrayList<>();
        for (int i = 0; i < ORDER.size(); i++) {
            if (open[i]) {
                GapDefinition definition = ORDER.get(i);
                gaps.add(new Gap(definition.code(), definition.path()));
            }
        }
        return new AnswerReadiness(spaceId, gaps.isEmpty(), List.copyOf(gaps));
    }

    private boolean hasActiveIndex(UUID spaceId) {
        return indexes.findActivePointer(spaceId)
                .filter(item -> spaceId.equals(item.spaceId()))
                .flatMap(pointer -> indexes.findVersion(spaceId, pointer.activeIndexVersionId()))
                .filter(item -> item.state() == IndexState.ACTIVE && spaceId.equals(item.spaceId()))
                .isPresent();
    }

    private boolean hasActiveProfile(UUID spaceId) {
        return profiles.findActivePointer(spaceId)
                .filter(item -> spaceId.equals(item.spaceId()))
                .flatMap(pointer -> profileVersions.find(spaceId, pointer.activeProfileVersionId(), spaceId))
                .filter(item -> spaceId.equals(item.spaceId()))
                .isPresent();
    }

    private boolean hasPublishedPrompt(UUID spaceId) {
        SpaceBindingRepository.SpaceBindingRecord binding = bindings.findCurrent(spaceId).orElse(null);
        if (binding != null && binding.promptVersionId() != null) {
            PromptRepository.PromptVersion bound = prompts.findVersion(spaceId, binding.promptVersionId())
                    .filter(item -> item.status() == PromptRepository.PromptStatus.PUBLISHED)
                    .orElse(null);
            if (bound != null) {
                return true;
            }
        }
        return prompts.findLatestPublished(spaceId).isPresent();
    }

    private boolean hasAuthorizedChatRoute(UUID spaceId) {
        SpaceBindingRepository.SpaceBindingRecord binding = bindings.findCurrent(spaceId).orElse(null);
        if (binding == null || binding.chatRouteId() == null) {
            return false;
        }
        ProviderRepository.ModelRouteVersion route = providers.findRouteVersion(spaceId, binding.chatRouteId())
                .orElse(null);
        if (route == null || route.status() != ProviderRepository.ModelRouteStatus.PUBLISHED
                || route.purpose() != ProviderRepository.RoutePurpose.CHAT) {
            return false;
        }
        return providers.listRouteCandidates(spaceId, route.id()).stream()
                .map(item -> providers.findProfileVersion(spaceId, item.profileVersionId()).orElse(null))
                .anyMatch(item -> item != null && item.status() == ProviderRepository.ModelProfileStatus.PUBLISHED);
    }

    private boolean hasCloudEgress(UUID spaceId) {
        SpaceBindingRepository.SpaceBindingRecord binding = bindings.findCurrent(spaceId).orElse(null);
        if (binding == null || binding.chatRouteId() == null) {
            return false;
        }
        ProviderRepository.ModelRouteVersion route = providers.findRouteVersion(spaceId, binding.chatRouteId())
                .orElse(null);
        if (route == null) {
            return false;
        }
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

    public record AnswerReadiness(UUID spaceId, boolean ready, List<Gap> gaps) {
    }

    public record Gap(String code, String path) {
    }

    private record GapDefinition(String code, String path) {
    }
}
