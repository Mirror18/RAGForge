package com.ragforge.ingestion.messaging;

public class EnvelopeValidationException extends RuntimeException {
    public EnvelopeValidationException(String message) {
        super(message);
    }
}
