package com.ragforge.ingestion.parser;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ParseReport(
        UUID parseReportId,
        UUID spaceId,
        UUID documentRevisionId,
        UUID artifactId,
        int version,
        ParseStatus status,
        String mediaType,
        int pageCount,
        long characterCount,
        long tokenCount,
        int nativePageCount,
        int ocrPageCount,
        String parserName,
        String parserVersion,
        long durationMs,
        List<String> warnings,
        List<String> errors,
        UUID extractedTextArtifactId,
        OcrReport ocr,
        Instant createdAt) {

    public ParseReport {
        if (parseReportId == null || spaceId == null || documentRevisionId == null || artifactId == null
                || version < 1 || status == null || mediaType == null || pageCount < 0
                || characterCount < 0 || tokenCount < 0 || nativePageCount < 0 || ocrPageCount < 0
                || parserName == null || parserVersion == null || durationMs < 0
                || warnings == null || errors == null || ocr == null || createdAt == null) {
            throw new IllegalArgumentException("parse report is incomplete");
        }
        warnings = bounded(warnings);
        errors = bounded(errors);
        if (status == ParseStatus.SUCCEEDED && extractedTextArtifactId == null) {
            throw new IllegalArgumentException("successful parse requires an extracted text artifact reference");
        }
        if ((status == ParseStatus.OCR_UNAVAILABLE || status == ParseStatus.BLOCKED)
                && extractedTextArtifactId != null) {
            throw new IllegalArgumentException("unavailable OCR cannot publish a text artifact reference");
        }
    }

    private static List<String> bounded(List<String> values) {
        if (values.size() > 100) {
            throw new IllegalArgumentException("parse report has too many diagnostics");
        }
        return values.stream().map(value -> {
            if (value == null || value.isBlank() || value.length() > 500) {
                throw new IllegalArgumentException("parse report diagnostic is invalid");
            }
            return value;
        }).toList();
    }

    public record OcrReport(
            boolean requested,
            OcrStatus status,
            String engine,
            String engineVersion,
            OcrTriggerReason triggerReason,
            List<Integer> pages,
            UUID sourceArtifactId,
            OcrAuditState auditState) {

        public OcrReport {
            if (status == null || triggerReason == null || pages == null || sourceArtifactId == null || auditState == null) {
                throw new IllegalArgumentException("OCR report is incomplete");
            }
            pages = List.copyOf(pages);
            if (pages.stream().anyMatch(page -> page == null || page < 1)) {
                throw new IllegalArgumentException("OCR page is invalid");
            }
        }

        public static OcrReport notRequested(UUID sourceArtifactId) {
            return new OcrReport(false, OcrStatus.NOT_REQUESTED, null, null,
                    OcrTriggerReason.NONE, List.of(), sourceArtifactId, OcrAuditState.NOT_APPLICABLE);
        }
    }
}
