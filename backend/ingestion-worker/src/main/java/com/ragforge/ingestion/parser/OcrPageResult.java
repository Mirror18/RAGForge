package com.ragforge.ingestion.parser;

public record OcrPageResult(int page, String text) {
    public OcrPageResult {
        if (page < 1 || text == null) {
            throw new IllegalArgumentException("OCR page result is invalid");
        }
    }
}
