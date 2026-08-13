package com.ragforge.ingestion.parser;

import java.util.List;

public record OcrResponse(
        OcrStatus status,
        String engine,
        String engineVersion,
        List<OcrPageResult> pages,
        String error) {

    public OcrResponse {
        if (status == null || pages == null) {
            throw new IllegalArgumentException("OCR response is incomplete");
        }
        pages = List.copyOf(pages);
    }

    public static OcrResponse unavailable(String message) {
        return new OcrResponse(OcrStatus.UNAVAILABLE, null, null, List.of(), message);
    }
}
