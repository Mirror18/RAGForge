package com.ragforge.ingestion.objectstore;

public interface ContentAddressedObjectStore {
    StoredObject put(ObjectKey key, String mediaType, byte[] content);

    StoredObject get(ObjectKey key);

    boolean exists(ObjectKey key);
}
