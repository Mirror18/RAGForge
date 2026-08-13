package com.ragforge.ingestion.objectstore;

import java.util.UUID;

public record ObjectKey(
        UUID spaceId,
        UUID sourceId,
        UUID documentRevisionId,
        UUID artifactId,
        String contentHash) {

    public ObjectKey {
        if (spaceId == null || sourceId == null || documentRevisionId == null || artifactId == null
                || contentHash == null || !contentHash.matches("^[0-9a-fA-F]{64}$")) {
            throw new ObjectStoreException(ObjectStoreFailure.INVALID_KEY, "object key is incomplete");
        }
    }

    public String value() {
        return "spaces/" + spaceId + "/sources/" + sourceId + "/revisions/"
                + documentRevisionId + "/artifacts/" + artifactId + "/sha256/" + contentHash.toLowerCase();
    }

    public static ObjectKey parse(UUID expectedSpaceId, String value) {
        if (value == null || expectedSpaceId == null || !value.startsWith("spaces/" + expectedSpaceId + "/")) {
            throw new ObjectStoreException(ObjectStoreFailure.SPACE_MISMATCH, "object key does not belong to the requested space");
        }
        String[] parts = value.split("/");
        if (parts.length != 10 || !parts[0].equals("spaces") || !parts[2].equals("sources")
                || !parts[4].equals("revisions") || !parts[6].equals("artifacts") || !parts[8].equals("sha256")) {
            throw new ObjectStoreException(ObjectStoreFailure.INVALID_KEY, "object key format is invalid");
        }
        try {
            return new ObjectKey(expectedSpaceId, UUID.fromString(parts[3]), UUID.fromString(parts[5]),
                    UUID.fromString(parts[7]), parts[9]);
        } catch (IllegalArgumentException exception) {
            throw new ObjectStoreException(ObjectStoreFailure.INVALID_KEY, "object key identifier is invalid", exception);
        }
    }
}
