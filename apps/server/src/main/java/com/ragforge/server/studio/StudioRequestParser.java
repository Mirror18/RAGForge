package com.ragforge.server.studio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Set;

/** Explicit request allow-list because the application mapper intentionally ignores unknown JSON fields globally. */
final class StudioRequestParser {
    private StudioRequestParser() {
    }

    static <T> T parse(ObjectMapper mapper, JsonNode body, Class<T> type, Set<String> allowedFields) {
        if (body == null || !body.isObject()) {
            throw invalid();
        }
        var names = body.fieldNames();
        while (names.hasNext()) {
            if (!allowedFields.contains(names.next())) {
                throw invalid();
            }
        }
        try {
            return mapper.treeToValue(body, type);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "request_fields_invalid", "Invalid request fields",
                "The request contains an unsupported or malformed field");
    }
}
