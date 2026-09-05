package com.ragforge.ingestion.connector;

import java.util.List;
import java.util.Set;

public record DiscoveryRules(
        List<String> include,
        List<String> exclude,
        Set<String> supportedExtensions,
        long maxObjectBytes,
        int maxChanges) {

    public DiscoveryRules {
        include = include == null ? List.of() : List.copyOf(include);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
        supportedExtensions = supportedExtensions == null ? Set.of() : supportedExtensions.stream()
                .map(String::toLowerCase)
                .map(value -> value.startsWith(".") ? value : "." + value)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (maxObjectBytes <= 0 || maxObjectBytes > 10L * 1024 * 1024 * 1024) {
            throw new ConnectorException(ConnectorFailure.RULES_INVALID, "maxObjectBytes is outside the contract limit");
        }
        if (maxChanges <= 0 || maxChanges > 100_000) {
            throw new ConnectorException(ConnectorFailure.RULES_INVALID, "maxChanges is outside the contract limit");
        }
    }

    public static DiscoveryRules defaults() {
        return new DiscoveryRules(List.of(), List.of(), Set.of(), 10L * 1024 * 1024 * 1024, 100_000);
    }
}
