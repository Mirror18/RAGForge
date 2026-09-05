package com.ragforge.server.answer.integration;

import com.ragforge.server.answer.RagPromptPort;

import java.util.UUID;

/** Prompt resolution is never replaced with an inline/default prompt. */
public final class FailClosedRagPromptPort implements RagPromptPort {
    private final Phase5IntegrationObserver observer;

    public FailClosedRagPromptPort() {
        this(Phase5IntegrationObserver.noop());
    }

    public FailClosedRagPromptPort(Phase5IntegrationObserver observer) {
        this.observer = observer == null ? Phase5IntegrationObserver.noop() : observer;
    }

    @Override
    public VersionedRagPrompt load(UUID spaceId, UUID promptVersionId, UUID correlationId) {
        if (spaceId == null || promptVersionId == null || correlationId == null) {
            throw new IllegalArgumentException("Prompt lookup is incomplete");
        }
        observer.record(new Phase5IntegrationObserver.Decision(spaceId, correlationId, correlationId,
                "prompt", "REJECTED", "UNCONFIGURED", null));
        throw new IllegalStateException("Versioned RAG prompt route is not configured");
    }
}
