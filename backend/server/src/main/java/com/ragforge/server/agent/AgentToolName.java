package com.ragforge.server.agent;

/** The only tools that the read-only agent boundary may dispatch. */
public enum AgentToolName {
    KNOWLEDGE_SEARCH("knowledge.search"),
    DOCUMENT_READ("document.read"),
    WEB_FETCH("web.fetch");

    private final String value;

    AgentToolName(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
