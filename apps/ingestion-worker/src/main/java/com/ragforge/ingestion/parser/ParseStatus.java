package com.ragforge.ingestion.parser;

public enum ParseStatus {
    SUCCEEDED,
    FAILED,
    OCR_REQUIRED,
    OCR_UNAVAILABLE,
    BLOCKED
}
