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
        var legacy = prompts.findRagVersion(spaceId, promptVersionId);
        if (legacy.isPresent()) {
            PromptRepository.RagPromptVersion version = legacy.get();
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

        // The current Web business flow publishes prompt_versions. Keep the
        // answer port compatible with the older redacted rag_prompt_versions
        // projection while still requiring an immutable, published,
        // space-scoped template and its database-derived content hash.
        PromptRepository.PromptVersion current = prompts.findVersion(spaceId, promptVersionId)
                .filter(version -> version.status() == PromptRepository.PromptStatus.PUBLISHED)
                .orElseThrow(() -> new IllegalArgumentException("RAG prompt version is not available in this space"));
        if (!spaceId.equals(current.spaceId()) || current.template() == null || current.template().isBlank()) {
            throw new IllegalArgumentException("RAG prompt template is not available for the requested version");
        }
        PromptRepository.RagPromptVersion projected = prompts.ensureRagVersion(current, correlationId);
        return new VersionedRagPrompt(projected.id(), projected.spaceId(), projected.promptKey(), projected.versionNo(),
                current.template(), projected.promptOpaqueRef(), projected.promptHash());
    }
}
