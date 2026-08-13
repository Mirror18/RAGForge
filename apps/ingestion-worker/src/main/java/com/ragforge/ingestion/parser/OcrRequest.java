package com.ragforge.ingestion.parser;

import java.util.List;
import java.util.UUID;

public record OcrRequest(
        UUID spaceId,
        UUID sourceArtifactId,
        String mediaType,
        List<Integer> pages,
        OcrTriggerReason triggerReason,
        byte[] content) {

    public OcrRequest {
        if (spaceId == null || sourceArtifactId == null || mediaType == null || pages == null
                || triggerReason == null || content == null) {
            throw new IllegalArgumentException("OCR request is incomplete");
        }
        pages = List.copyOf(pages);
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
