package com.ragforge.server.retrieval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable identity of one retrieval execution. This value object deliberately
 * contains only redacted parameters and provenance references; query text,
 * vectors, credentials and searchable material are not part of a snapshot.
 */
public record RetrievalExecutionSnapshot(
        String schemaVersion,
        UUID snapshotId,
        UUID spaceId,
        String executionKey,
        UUID indexVersionId,
        UUID profileId,
        int profileVersion,
        EffectiveParameters effectiveParameters,
        String routeVersion,
        String executorVersion,
        String degradationPolicy,
        UUID evidenceBundleId,
        int evidenceBundleVersion,
        String evidenceBundleRef,
        String datasetHash,
        String configHash,
        Instant createdAt) {

    public static final String SCHEMA_VERSION = "retrieval-execution-snapshot.v1";
    private static final Pattern SAFE_REF = Pattern.compile("^[A-Za-z0-9._:/-]{1,512}$");
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");

    public RetrievalExecutionSnapshot {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unknown retrieval execution snapshot version");
        }
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(spaceId, "spaceId");
        if (executionKey == null || !SAFE_REF.matcher(executionKey).matches()) {
            throw new IllegalArgumentException("executionKey is invalid");
        }
        Objects.requireNonNull(indexVersionId, "indexVersionId");
        Objects.requireNonNull(profileId, "profileId");
        if (profileVersion <= 0) {
            throw new IllegalArgumentException("profileVersion must be positive");
        }
        Objects.requireNonNull(effectiveParameters, "effectiveParameters");
        requireRef(routeVersion, "routeVersion", 160);
        requireRef(executorVersion, "executorVersion", 120);
        requireRef(degradationPolicy, "degradationPolicy", 120);
        Objects.requireNonNull(evidenceBundleId, "evidenceBundleId");
        if (evidenceBundleVersion <= 0) {
            throw new IllegalArgumentException("evidenceBundleVersion must be positive");
        }
        requireRef(evidenceBundleRef, "evidenceBundleRef", 512);
        requireHash(datasetHash, "datasetHash");
        requireHash(configHash, "configHash");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public String effectiveParametersHash() {
        return sha256(effectiveParameters.canonicalJson());
    }

    /** Retry equality excludes generated identity and creation time. */
    public boolean sameExecutionIdentity(RetrievalExecutionSnapshot other) {
        return other != null
                && schemaVersion.equals(other.schemaVersion)
                && spaceId.equals(other.spaceId)
                && executionKey.equals(other.executionKey)
                && indexVersionId.equals(other.indexVersionId)
                && profileId.equals(other.profileId)
                && profileVersion == other.profileVersion
                && effectiveParameters.equals(other.effectiveParameters)
                && routeVersion.equals(other.routeVersion)
                && executorVersion.equals(other.executorVersion)
                && degradationPolicy.equals(other.degradationPolicy)
                && evidenceBundleId.equals(other.evidenceBundleId)
                && evidenceBundleVersion == other.evidenceBundleVersion
                && evidenceBundleRef.equals(other.evidenceBundleRef)
                && datasetHash.equals(other.datasetHash)
                && configHash.equals(other.configHash);
    }

    public static EffectiveParameters from(RetrievalProfileRepository.RetrievalProfileVersion profile) {
        Objects.requireNonNull(profile, "profile");
        return new EffectiveParameters(profile.denseTopK(), profile.bm25TopK(), profile.rrfK(),
                profile.rrfDenseWeight(), profile.rrfBm25Weight(), profile.rerankTopK(),
                profile.maxContextChildren(), profile.expansionMode().name(), profile.maxParentsPerChild(),
                profile.maxNeighborsPerParent(), profile.maxContextTokens());
    }

    private static void requireRef(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max || !SAFE_REF.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static void requireHash(String value, String name) {
        if (value == null || !HASH.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : bytes) {
                result.append(String.format(Locale.ROOT, "%02x", item));
            }
            return result.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is required by the runtime", failure);
        }
    }

    public record EffectiveParameters(
            int denseTopK,
            int bm25TopK,
            int rrfK,
            double rrfDenseWeight,
            double rrfBm25Weight,
            int rerankTopK,
            int maxContextChildren,
            String expansionMode,
            int maxParentsPerChild,
            int maxNeighborsPerParent,
            int maxContextTokens) {

        public EffectiveParameters {
            if (denseTopK <= 0 || bm25TopK <= 0 || rrfK <= 0 || rerankTopK <= 0 || maxContextChildren <= 0
                    || maxParentsPerChild < 0 || maxNeighborsPerParent < 0 || maxContextTokens <= 0
                    || !Double.isFinite(rrfDenseWeight) || rrfDenseWeight < 0
                    || !Double.isFinite(rrfBm25Weight) || rrfBm25Weight < 0
                    || expansionMode == null || expansionMode.isBlank()) {
                throw new IllegalArgumentException("effective retrieval parameters are invalid");
            }
            expansionMode = expansionMode.toUpperCase(Locale.ROOT);
            if (!SetOfExpansionModes.contains(expansionMode)) {
                throw new IllegalArgumentException("unknown expansion mode");
            }
        }

        public String canonicalJson() {
            return "{\"denseTopK\":" + denseTopK
                    + ",\"bm25TopK\":" + bm25TopK
                    + ",\"rrfK\":" + rrfK
                    + ",\"rrfDenseWeight\":" + Double.toString(rrfDenseWeight)
                    + ",\"rrfBm25Weight\":" + Double.toString(rrfBm25Weight)
                    + ",\"rerankTopK\":" + rerankTopK
                    + ",\"maxContextChildren\":" + maxContextChildren
                    + ",\"expansionMode\":\"" + expansionMode + "\""
                    + ",\"maxParentsPerChild\":" + maxParentsPerChild
                    + ",\"maxNeighborsPerParent\":" + maxNeighborsPerParent
                    + ",\"maxContextTokens\":" + maxContextTokens + "}";
        }
    }

    private static final class SetOfExpansionModes {
        private static boolean contains(String value) {
            return "NONE".equals(value) || "PARENT".equals(value) || "NEIGHBOR".equals(value)
                    || "PARENT_AND_NEIGHBOR".equals(value);
        }
    }
}
