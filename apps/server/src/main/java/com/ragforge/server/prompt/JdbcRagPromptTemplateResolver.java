package com.ragforge.server.prompt;

import com.ragforge.server.answer.V11RagPromptPort;

import java.util.Objects;
import java.util.UUID;

/** Resolves an opaque RAG prompt reference only after space/hash validation. */
public final class JdbcRagPromptTemplateResolver implements V11RagPromptPort.TemplateResolver {
    private final PromptRepository prompts;

    public JdbcRagPromptTemplateResolver(PromptRepository prompts) {
        this.prompts = Objects.requireNonNull(prompts, "prompts");
    }

    @Override
    public String resolve(UUID spaceId, String promptOpaqueRef, String promptHash) {
        if (spaceId == null || promptOpaqueRef == null || promptOpaqueRef.isBlank()
                || promptHash == null || !promptHash.matches("[0-9a-fA-F]{64}")
                || promptOpaqueRef.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            return null;
        }
        return prompts.findPublishedTemplateByHash(spaceId, promptHash).orElse(null);
    }
}
