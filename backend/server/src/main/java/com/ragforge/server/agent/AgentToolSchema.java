package com.ragforge.server.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Strict JSON boundary for tool requests; unknown properties are rejected. */
public final class AgentToolSchema {
    private AgentToolSchema() {
    }

    public static KnowledgeSearchTool.KnowledgeSearchRequest parseKnowledgeSearch(ObjectMapper mapper, String json) {
        return read(mapper, json, KnowledgeSearchTool.KnowledgeSearchRequest.class);
    }

    public static DocumentReadTool.DocumentReadRequest parseDocumentRead(ObjectMapper mapper, String json) {
        return read(mapper, json, DocumentReadTool.DocumentReadRequest.class);
    }

    public static WebFetchTool.WebFetchRequest parseWebFetch(ObjectMapper mapper, String json) {
        return read(mapper, json, WebFetchTool.WebFetchRequest.class);
    }

    private static <T> T read(ObjectMapper mapper, String json, Class<T> type) {
        if (mapper == null || json == null || json.length() > 16 * 1024) {
            throw new AgentToolSecurityException("TOOL_SCHEMA_INVALID");
        }
        try {
            return mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(json);
        } catch (Exception exception) {
            throw new AgentToolSecurityException("TOOL_SCHEMA_INVALID");
        }
    }
}
