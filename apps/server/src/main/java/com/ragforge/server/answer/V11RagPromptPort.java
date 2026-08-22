package com.ragforge.server.answer;

import com.ragforge.server.prompt.PromptRepository;

import java.util.Objects;
import java.util.UUID;

/** Resolves a V11 opaque prompt artifact at execution time; the repository remains hash/ref-only. */
public final class V11RagPromptPort implements RagPromptPort {
    @FunctionalInterface
    public interface TemplateResolver {
        String resolve(UUID spaceId, String promptOpaqueRef, String promptHash);
    }

    private final PromptRepository prompts;
    private final TemplateResolver templates;

    public V11RagPromptPort(PromptRepository prompts, TemplateResolver templates) {
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.templates = Objects.requireNonNull(templates, "templates");
    }

    @Override
    public VersionedRagPrompt load(UUID spaceId, UUID promptVersionId, UUID correlationId) {
        PromptRepository.RagPromptVersion version = prompts.findRagVersion(spaceId, promptVersionId)
                .orElseThrow(() -> new IllegalArgumentException("RAG prompt version is not available in this space"));
        if (!spaceId.equals(version.spaceId())) {
            throw new IllegalArgumentException("RAG prompt version crosses the requested space");
        }
        String template = templates.resolve(spaceId, version.promptOpaqueRef(), version.promptHash());
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("RAG prompt template is not available for the requested version");
        }
        return new VersionedRagPrompt(version.id(), version.spaceId(), version.promptKey(), version.versionNo(),
                template, version.promptOpaqueRef(), version.promptHash());
    }
}
