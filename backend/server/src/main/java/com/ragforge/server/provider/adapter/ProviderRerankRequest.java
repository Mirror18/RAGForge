package com.ragforge.server.provider.adapter;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded, space-scoped request sent to a rerank provider. */
public record ProviderRerankRequest(
        UUID spaceId,
        RequestIdentity identity,
        String modelName,
        String query,
        List<Candidate> candidates,
        Duration timeout,
        int limit,
        Set<ModelCapability> requiredCapabilities) {

    public static final int MAX_CANDIDATES = 100;
    public static final int MAX_QUERY_CHARS = 2_000;
    public static final int MAX_CANDIDATE_TEXT_CHARS = 2_048;
    public static final int MAX_TOTAL_TEXT_CHARS = 100_000;

    public record Candidate(UUID spaceId, UUID candidateId, String text) {
        public Candidate {
            Objects.requireNonNull(spaceId, "spaceId");
            Objects.requireNonNull(candidateId, "candidateId");
            if (text == null || text.isBlank() || text.length() > MAX_CANDIDATE_TEXT_CHARS) {
                throw new IllegalArgumentException("rerank candidate text is outside its bound");
            }
        }
    }

    public ProviderRerankRequest {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(identity, "identity");
        if (modelName == null || modelName.isBlank() || modelName.length() > 200
                || query == null || query.isBlank() || query.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("rerank request text is outside its bound");
        }
        if (candidates == null || candidates.isEmpty() || candidates.size() > MAX_CANDIDATES
                || limit < 1 || limit > MAX_CANDIDATES || limit > candidates.size()) {
            throw new IllegalArgumentException("rerank candidate bounds are invalid");
        }
        int totalChars = 0;
        Set<UUID> identities = new java.util.HashSet<>();
        for (Candidate candidate : candidates) {
            if (!spaceId.equals(candidate.spaceId()) || !identities.add(candidate.candidateId())) {
                throw new IllegalArgumentException("rerank candidate crosses space or identity boundary");
            }
            totalChars += candidate.text().length();
        }
        if (totalChars > MAX_TOTAL_TEXT_CHARS) {
            throw new IllegalArgumentException("rerank request text exceeds its total bound");
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.toSeconds() > 30) {
            throw new IllegalArgumentException("rerank timeout is outside its bound");
        }
        requiredCapabilities = requiredCapabilities == null ? Set.of(ModelCapability.RERANK)
                : Set.copyOf(requiredCapabilities);
    }
}
