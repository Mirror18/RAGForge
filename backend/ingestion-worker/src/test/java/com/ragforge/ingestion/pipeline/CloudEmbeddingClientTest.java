package com.ragforge.ingestion.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudEmbeddingClientTest {
    @Test
    void blankEndpointFailsClosed() {
        CloudEmbeddingClient client = new CloudEmbeddingClient(null, "", "text-embedding-3-small", "test-key");
        assertThatThrownBy(() -> client.embed("question"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("space-authorized OpenAI-compatible cloud endpoint");
    }

    @Test
    void localOllamaEndpointFailsClosed() {
        CloudEmbeddingClient client = new CloudEmbeddingClient(null,
                "http://127.0.0.1:11434", "nomic-embed-text", "test-key");
        assertThatThrownBy(() -> client.embed("question"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("space-authorized OpenAI-compatible cloud endpoint");
    }

    @Test
    void ollamaHostnameFailsClosed() {
        CloudEmbeddingClient client = new CloudEmbeddingClient(null,
                "http://ollama:11434", "nomic-embed-text", "test-key");
        assertThatThrownBy(() -> client.embed("question"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("space-authorized OpenAI-compatible cloud endpoint");
    }
}
