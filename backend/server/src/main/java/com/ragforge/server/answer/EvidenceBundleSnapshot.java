package com.ragforge.server.answer;

import com.ragforge.server.common.UuidV7;
import com.ragforge.server.retrieval.EvidenceBundle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Evidence metadata plus transient, non-persisted material made available to generation. */
public record EvidenceBundleSnapshot(
        UUID evidenceBundleId,
        int evidenceBundleVersion,
        String evidenceBundleHash,
        String evidenceBundleRef,
        EvidenceBundle bundle,
        List<EvidenceMaterial> materials,
        String datasetHash,
        String configHash) {

    public record EvidenceMaterial(UUID evidenceId, String content) {
        public EvidenceMaterial {
            Objects.requireNonNull(evidenceId, "evidenceId");
            if (content == null || content.isBlank() || content.length() > 100_000) {
                throw new IllegalArgumentException("Evidence material is invalid");
            }
        }
    }

    public EvidenceBundleSnapshot {
        Objects.requireNonNull(evidenceBundleId, "evidenceBundleId");
        Objects.requireNonNull(bundle, "bundle");
        if (evidenceBundleVersion <= 0 || evidenceBundleHash == null
                || !evidenceBundleHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Evidence bundle identity is invalid");
        }
        if (evidenceBundleRef == null || !evidenceBundleRef.matches("^[A-Za-z0-9._:/-]{1,512}$")
                || evidenceBundleRef.matches(".*(?i)(raw_document|raw_output|fulltext|responsebody|promptbody).*")) {
            throw new IllegalArgumentException("Evidence bundle reference is invalid");
        }
        if (!bundle.spaceId().equals(bundle.evidence().isEmpty() ? bundle.spaceId() : bundle.evidence().get(0).spaceId())) {
            throw new IllegalArgumentException("Evidence bundle crosses space");
        }
        materials = materials == null ? List.of() : List.copyOf(materials);
        Set<UUID> ids = new HashSet<>();
        for (EvidenceMaterial material : materials) {
            if (!ids.add(material.evidenceId())
                    || bundle.evidence().stream().noneMatch(item -> item.evidenceId().equals(material.evidenceId()))) {
                throw new IllegalArgumentException("Evidence material is outside the bundle or duplicated");
            }
        }
        if (datasetHash == null || !datasetHash.matches("[0-9a-f]{64}")
                || configHash == null || !configHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Evidence dataset/config hash is invalid");
        }
    }

    public EvidenceBundleSnapshot(EvidenceBundle bundle) {
        this(UuidV7.random(), 1, hashBundle(bundle), "evidence:" + UuidV7.random(), bundle, List.of(),
                zeroHash(), zeroHash());
    }

    public EvidenceBundleSnapshot(UUID evidenceBundleId, int evidenceBundleVersion, String evidenceBundleHash,
                                  String evidenceBundleRef, EvidenceBundle bundle, List<EvidenceMaterial> materials) {
        this(evidenceBundleId, evidenceBundleVersion, evidenceBundleHash, evidenceBundleRef, bundle, materials,
                zeroHash(), zeroHash());
    }

    public EvidenceBundleSnapshot limitTo(int maxContextTokens) {
        if (maxContextTokens <= 0) {
            throw new IllegalArgumentException("Context token budget must be positive");
        }
        int used = 0;
        List<EvidenceBundle.Evidence> selected = new ArrayList<>();
        for (EvidenceBundle.Evidence item : bundle.evidence()) {
            int tokens = Math.max(1, item.anchor().tokenEnd() - item.anchor().tokenStart());
            if (used + tokens > maxContextTokens) {
                continue;
            }
            selected.add(item);
            used += tokens;
        }
        boolean abstained = bundle.abstained() || selected.isEmpty();
        EvidenceBundle bounded = new EvidenceBundle(bundle.spaceId(), bundle.indexVersionId(), bundle.profileId(),
                bundle.profileVersion(), bundle.originalQuery(), bundle.normalizedQuery(), selected, abstained,
                abstained ? (bundle.abstentionReason() == null ? "CONTEXT_BUDGET_EXCEEDED" : bundle.abstentionReason()) : null);
        Set<UUID> selectedIds = selected.stream().map(EvidenceBundle.Evidence::evidenceId).collect(java.util.stream.Collectors.toSet());
        return new EvidenceBundleSnapshot(evidenceBundleId, evidenceBundleVersion, evidenceBundleHash,
                evidenceBundleRef, bounded, materials.stream().filter(item -> selectedIds.contains(item.evidenceId())).toList(),
                datasetHash, configHash);
    }

    public Map<UUID, String> materialById() {
        Map<UUID, String> result = new HashMap<>();
        materials.forEach(item -> result.put(item.evidenceId(), item.content()));
        return Map.copyOf(result);
    }

    private static String hashBundle(EvidenceBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        String canonical = bundle.spaceId() + "|" + bundle.indexVersionId() + "|" + bundle.profileId() + "|"
                + bundle.profileVersion() + "|" + bundle.evidence().stream()
                .map(item -> item.evidenceId() + ":" + item.textHash() + ":" + item.anchor().tokenStart()
                        + ":" + item.anchor().tokenEnd())
                .sorted().reduce("", (left, right) -> left + right);
        return sha256(canonical);
    }

    private static String zeroHash() {
        return "0000000000000000000000000000000000000000000000000000000000000000";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime", exception);
        }
    }
}
