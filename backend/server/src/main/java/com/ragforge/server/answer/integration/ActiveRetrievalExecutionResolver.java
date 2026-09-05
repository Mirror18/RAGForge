package com.ragforge.server.answer.integration;

import com.ragforge.server.index.IndexRepository;
import com.ragforge.server.index.IndexState;
import com.ragforge.server.retrieval.RetrievalProfileRepository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Reads the active PostgreSQL pointers and refuses missing/non-active versions. */
public final class ActiveRetrievalExecutionResolver implements RetrievalExecutionResolver {
    @FunctionalInterface
    public interface VersionIdentityProvider {
        VersionIdentity resolve(UUID spaceId, UUID indexVersionId,
                                RetrievalProfileRepository.RetrievalProfileVersion profile,
                                UUID correlationId);
    }

    @FunctionalInterface
    public interface ProfileVersionLoader {
        Optional<RetrievalProfileRepository.RetrievalProfileVersion> find(UUID spaceId,
                                                                           UUID profileVersionId,
                                                                           UUID correlationId);
    }

    public record VersionIdentity(UUID evidenceBundleId, int evidenceBundleVersion,
                                  String evidenceBundleRef, String datasetHash, String configHash) {
    }

    private final IndexRepository indexes;
    private final RetrievalProfileRepository profiles;
    private final ProfileVersionLoader profileVersions;
    private final VersionIdentityProvider identities;
    private final Phase5IntegrationObserver observer;

    public ActiveRetrievalExecutionResolver(IndexRepository indexes, RetrievalProfileRepository profiles,
                                            ProfileVersionLoader profileVersions,
                                            VersionIdentityProvider identities) {
        this(indexes, profiles, profileVersions, identities, Phase5IntegrationObserver.noop());
    }

    public ActiveRetrievalExecutionResolver(IndexRepository indexes, RetrievalProfileRepository profiles,
                                            ProfileVersionLoader profileVersions, VersionIdentityProvider identities,
                                            Phase5IntegrationObserver observer) {
        this.indexes = Objects.requireNonNull(indexes, "indexes");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.profileVersions = Objects.requireNonNull(profileVersions, "profileVersions");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.observer = observer == null ? Phase5IntegrationObserver.noop() : observer;
    }

    @Override
    public Execution resolve(UUID spaceId, UUID runId, UUID correlationId) {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(correlationId, "correlationId");
        IndexRepository.ActiveIndexPointer pointer = indexes.findActivePointer(spaceId)
                .filter(item -> spaceId.equals(item.spaceId()))
                .orElseThrow(() -> unavailable(spaceId, runId, correlationId, "NO_ACTIVE_INDEX"));
        IndexRepository.IndexVersion index = indexes.findVersion(spaceId, pointer.activeIndexVersionId())
                .filter(item -> item.state() == IndexState.ACTIVE)
                .orElseThrow(() -> unavailable(spaceId, runId, correlationId, "ACTIVE_INDEX_NOT_READY"));
        RetrievalProfileRepository.ActiveProfilePointer profilePointer = profiles.findActivePointer(spaceId)
                .filter(item -> spaceId.equals(item.spaceId()))
                .orElseThrow(() -> unavailable(spaceId, runId, correlationId, "NO_ACTIVE_RETRIEVAL_PROFILE"));
        RetrievalProfileRepository.RetrievalProfileVersion profile = profileVersions
                .find(spaceId, profilePointer.activeProfileVersionId(), correlationId)
                .filter(item -> item.id().equals(profilePointer.activeProfileVersionId()))
                .orElseThrow(() -> unavailable(spaceId, runId, correlationId, "ACTIVE_PROFILE_NOT_READY"));
        VersionIdentity identity = Objects.requireNonNull(identities.resolve(spaceId, index.id(), profile, correlationId),
                "retrieval identity");
        Execution result = new Execution(spaceId, index.id(), profile, identity.evidenceBundleId(),
                identity.evidenceBundleVersion(), identity.evidenceBundleRef(), identity.datasetHash(), identity.configHash());
        observer.record(new Phase5IntegrationObserver.Decision(spaceId, runId, correlationId,
                "retrieval-route", "AUTHORIZED", "ACTIVE_INDEX_AND_PROFILE", null));
        return result;
    }

    private ProviderRouteUnavailableException unavailable(UUID spaceId, UUID runId,
                                                          UUID correlationId, String reason) {
        observer.record(new Phase5IntegrationObserver.Decision(spaceId, runId, correlationId,
                "retrieval-route", "REJECTED", reason, null));
        return new ProviderRouteUnavailableException("Retrieval execution is not available");
    }

    public static final class ProviderRouteUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ProviderRouteUnavailableException(String message) {
            super(message);
        }
    }
}
