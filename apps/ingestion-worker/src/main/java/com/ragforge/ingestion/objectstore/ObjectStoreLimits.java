package com.ragforge.ingestion.objectstore;

import java.util.Set;

public record ObjectStoreLimits(long maxBytes, Set<String> allowedMediaTypes) {
    public ObjectStoreLimits {
        if (maxBytes <= 0 || maxBytes > 10L * 1024 * 1024 * 1024 || allowedMediaTypes == null || allowedMediaTypes.isEmpty()) {
            throw new ObjectStoreException(ObjectStoreFailure.OBJECT_STORE_UNAVAILABLE, "object store limits are invalid");
        }
        allowedMediaTypes = allowedMediaTypes.stream().map(String::toLowerCase).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static ObjectStoreLimits defaults() {
        return new ObjectStoreLimits(10L * 1024 * 1024 * 1024, Set.of(
                "text/plain", "text/markdown", "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }
}
