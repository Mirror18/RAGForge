package com.ragforge.server.answer.integration;

import com.ragforge.server.index.IndexRepository;
import com.ragforge.server.retrieval.RetrievalProfileRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;

/**
 * Derives a stable, redacted retrieval identity from immutable active index
 * and profile rows. No query text or evidence material enters these hashes.
 */
public final class DeterministicRetrievalVersionIdentityProvider
        implements ActiveRetrievalExecutionResolver.VersionIdentityProvider {
    private final IndexRepository indexes;

    public DeterministicRetrievalVersionIdentityProvider(IndexRepository indexes) {
        this.indexes = Objects.requireNonNull(indexes, "indexes");
    }

    @Override
    public ActiveRetrievalExecutionResolver.VersionIdentity resolve(
            UUID spaceId, UUID indexVersionId,
            RetrievalProfileRepository.RetrievalProfileVersion profile, UUID correlationId) {
        if (spaceId == null || indexVersionId == null || profile == null
                || !spaceId.equals(profile.spaceId())) {
            return null;
        }
        IndexRepository.IndexVersion index = indexes.findVersion(spaceId, indexVersionId)
                .filter(value -> value.state() == com.ragforge.server.index.IndexState.ACTIVE)
                .orElse(null);
        if (index == null) {
            return null;
        }
        String datasetHash = sha256("retrieval-dataset-v1|" + spaceId + "|" + index.id() + "|"
                + index.versionNo() + "|" + index.embeddingProfileVersion() + "|"
                + index.chunkingStrategyVersion() + "|" + index.documentRevisionCount() + "|"
                + index.childChunkCount());
        String configHash = sha256("retrieval-config-v1|" + spaceId + "|" + profile.id() + "|"
                + profile.profileId() + "|" + profile.versionNo() + "|" + profile.denseTopK() + "|"
                + profile.bm25TopK() + "|" + profile.rrfK() + "|" + profile.rrfDenseWeight() + "|"
                + profile.rrfBm25Weight() + "|" + profile.rerankTopK() + "|"
                + profile.maxContextChildren() + "|" + profile.expansionMode() + "|"
                + profile.maxParentsPerChild() + "|" + profile.maxNeighborsPerParent() + "|"
                + profile.maxContextTokens());
        UUID bundleId = UUID.nameUUIDFromBytes(("evidence-bundle-v1|" + datasetHash + "|" + configHash)
                .getBytes(StandardCharsets.UTF_8));
        String bundleRef = "evidence-bundle:" + index.id() + ":" + profile.id() + ":" + profile.versionNo();
        return new ActiveRetrievalExecutionResolver.VersionIdentity(bundleId, index.versionNo(), bundleRef,
                datasetHash, configHash);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the runtime", impossible);
        }
    }
}
