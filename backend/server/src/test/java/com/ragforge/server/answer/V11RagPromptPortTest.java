package com.ragforge.server.answer;

import com.ragforge.server.prompt.PromptRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V11RagPromptPortTest {
    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();
    private static final UUID CORRELATION = UUID.randomUUID();
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void resolvesTemplateWithSpaceAndImmutableHash() {
        PromptRepository prompts = mock(PromptRepository.class);
        when(prompts.findRagVersion(SPACE, VERSION)).thenReturn(Optional.of(new PromptRepository.RagPromptVersion(
                VERSION, SPACE, "rag-answer", 3, "answer", "prompt://answer/v3", HASH,
                "{}", "{}", UUID.randomUUID(), Instant.now(), CORRELATION)));
        AtomicReference<String> received = new AtomicReference<>();

        V11RagPromptPort port = new V11RagPromptPort(prompts, (space, ref, hash) -> {
            received.set(space + "|" + ref + "|" + hash);
            return "system: answer only from evidence";
        });

        RagPromptPort.VersionedRagPrompt result = port.load(SPACE, VERSION, CORRELATION);

        assertThat(result.template()).isEqualTo("system: answer only from evidence");
        assertThat(received).hasValue(SPACE + "|prompt://answer/v3|" + HASH);
    }

    @Test
    void refusesMissingResolvedTemplateInsteadOfGeneratingWithUnboundContent() {
        PromptRepository prompts = mock(PromptRepository.class);
        when(prompts.findRagVersion(SPACE, VERSION)).thenReturn(Optional.of(new PromptRepository.RagPromptVersion(
                VERSION, SPACE, "rag-answer", 3, "answer", "prompt://answer/v3", HASH,
                "{}", "{}", UUID.randomUUID(), Instant.now(), CORRELATION)));

        V11RagPromptPort port = new V11RagPromptPort(prompts, (space, ref, hash) -> null);

        assertThatThrownBy(() -> port.load(SPACE, VERSION, CORRELATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("template is not available");
    }

    @Test
    void refusesRepositoryResultThatCrossesRequestedSpace() {
        PromptRepository prompts = mock(PromptRepository.class);
        when(prompts.findRagVersion(SPACE, VERSION)).thenReturn(Optional.of(new PromptRepository.RagPromptVersion(
                VERSION, UUID.randomUUID(), "rag-answer", 3, "answer", "prompt://answer/v3", HASH,
                "{}", "{}", UUID.randomUUID(), Instant.now(), CORRELATION)));

        V11RagPromptPort port = new V11RagPromptPort(prompts, (space, ref, hash) -> "must not be reached");

        assertThatThrownBy(() -> port.load(SPACE, VERSION, CORRELATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("crosses the requested space");
    }

    @Test
    void loadsPublishedModernPromptVersionUsedByWebBusinessFlow() {
        PromptRepository prompts = mock(PromptRepository.class);
        when(prompts.findRagVersion(SPACE, VERSION)).thenReturn(Optional.empty());
        when(prompts.findVersion(SPACE, VERSION)).thenReturn(Optional.of(new PromptRepository.PromptVersion(
                VERSION, SPACE, "Local RAG Answer", 2, "Return JSON", "a".repeat(64), "{}", "{}",
                "test", UUID.randomUUID(), PromptRepository.PromptStatus.PUBLISHED,
                Instant.now(), Instant.now(), CORRELATION)));
        when(prompts.ensureRagVersion(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(CORRELATION)))
                .thenReturn(new PromptRepository.RagPromptVersion(VERSION, SPACE, "Local RAG Answer", 2,
                        "RAG_ANSWER", "prompt-version:" + VERSION, "a".repeat(64), "{}", "{}",
                        UUID.randomUUID(), Instant.now(), CORRELATION));

        V11RagPromptPort port = new V11RagPromptPort(prompts, (space, ref, hash) -> "must not be reached");

        assertThat(port.load(SPACE, VERSION, CORRELATION).template()).isEqualTo("Return JSON");
    }
}
