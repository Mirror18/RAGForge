package com.ragforge.ingestion.parser;

import java.util.UUID;

public record ParseRequest(
        UUID spaceId,
        UUID documentRevisionId,
        UUID artifactId,
        String mediaType,
        byte[] content,
        int version,
        String parserVersion) {

    public ParseRequest {
        if (spaceId == null || documentRevisionId == null || artifactId == null || mediaType == null
                || content == null || content.length == 0 || version < 1 || parserVersion == null
                || parserVersion.isBlank()) {
            throw new IllegalArgumentException("parse request is incomplete");
        }
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
