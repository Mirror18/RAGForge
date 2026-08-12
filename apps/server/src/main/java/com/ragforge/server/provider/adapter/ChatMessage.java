package com.ragforge.server.provider.adapter;

import java.util.Objects;

public record ChatMessage(String role, String content) {

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        if (!role.matches("^(system|user|assistant|tool)$")) {
            throw new IllegalArgumentException("Unsupported chat message role");
        }
    }

    @Override
    public String toString() {
        return "ChatMessage[role=%s, content=<redacted>]".formatted(role);
    }
}
