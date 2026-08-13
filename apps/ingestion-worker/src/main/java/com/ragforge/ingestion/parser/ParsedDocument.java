package com.ragforge.ingestion.parser;

public record ParsedDocument(ParseReport report, String extractedText) {
    public ParsedDocument {
        if (report == null || extractedText == null) {
            throw new IllegalArgumentException("parsed document is incomplete");
        }
        if (report.status() == ParseStatus.SUCCEEDED && extractedText.isBlank()) {
            throw new IllegalArgumentException("successful parse cannot return empty text");
        }
    }
}
