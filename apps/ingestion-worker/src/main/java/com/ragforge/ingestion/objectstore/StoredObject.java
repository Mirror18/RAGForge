package com.ragforge.ingestion.objectstore;

import java.time.Instant;

public record StoredObject(ObjectKey key, String mediaType, long byteLength, String sha256,
                           Instant createdAt, byte[] content) {
    public StoredObject {
        if (key == null || mediaType == null || byteLength < 0 || sha256 == null || createdAt == null || content == null) {
            throw new ObjectStoreException(ObjectStoreFailure.OBJECT_STORE_UNAVAILABLE, "stored object is incomplete");
        }
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
