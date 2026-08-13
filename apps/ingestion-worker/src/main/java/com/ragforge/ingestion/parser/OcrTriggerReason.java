package com.ragforge.ingestion.parser;

public enum OcrTriggerReason {
    NONE,
    IMAGE_ONLY_PDF,
    LOW_TEXT_QUALITY,
    SCANNED_PAGE,
    PARSER_FAILURE
}
