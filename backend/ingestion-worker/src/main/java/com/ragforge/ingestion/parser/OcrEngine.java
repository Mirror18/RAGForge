package com.ragforge.ingestion.parser;

public interface OcrEngine {
    OcrResponse recognize(OcrRequest request) throws OcrException;
}
