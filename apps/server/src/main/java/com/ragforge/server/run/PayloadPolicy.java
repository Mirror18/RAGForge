package com.ragforge.server.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Set;

/** Validates the data boundary for events before an event can be retained or delivered. */
public final class PayloadPolicy {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "secret", "apikey", "accesstoken", "documentcontent", "fulltext", "rawdocument", "password");

    private PayloadPolicy() {
    }

    public static String validate(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("payloadJson must not be blank");
        }
        try {
            JsonNode payload = JSON.readTree(payloadJson);
            if (payload == null) {
                throw new IllegalArgumentException("payloadJson must be valid JSON");
            }
            rejectSensitiveFields(payload);
            return payloadJson;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("payloadJson must be valid JSON", exception);
        }
    }

    public static JsonNode parse(String payloadJson) {
        validate(payloadJson);
        try {
            return JSON.readTree(payloadJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("payloadJson must be valid JSON", exception);
        }
    }

    private static void rejectSensitiveFields(JsonNode node) {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (FORBIDDEN_FIELDS.contains(normalize(field.getKey()))) {
                    throw new SensitivePayloadException(field.getKey());
                }
                rejectSensitiveFields(field.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(PayloadPolicy::rejectSensitiveFields);
        }
    }

    private static String normalize(String fieldName) {
        return fieldName.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }
}
