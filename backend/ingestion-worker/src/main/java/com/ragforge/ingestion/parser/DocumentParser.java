package com.ragforge.ingestion.parser;

public interface DocumentParser {
    ParsedDocument parse(ParseRequest request, OcrEngine ocrEngine);
}
