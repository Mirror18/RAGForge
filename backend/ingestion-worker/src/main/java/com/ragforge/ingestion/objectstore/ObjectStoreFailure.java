package com.ragforge.ingestion.objectstore;

public enum ObjectStoreFailure {
    INVALID_KEY,
    SPACE_MISMATCH,
    CONTENT_TOO_LARGE,
    MIME_NOT_ALLOWED,
    CHECKSUM_MISMATCH,
    OBJECT_NOT_FOUND,
    OBJECT_STORE_UNAVAILABLE
}
