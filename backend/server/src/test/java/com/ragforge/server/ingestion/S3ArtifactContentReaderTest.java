package com.ragforge.server.ingestion;

import com.ragforge.server.provider.adapter.CancellationToken;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class S3ArtifactContentReaderTest {
    private static final UUID SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");

    @Test
    void rejectsForeignSpaceBeforeTouchingObjectStorage() {
        var reader = new S3ArtifactContentReader(mock(MinioClient.class), "ragforge", "phase3", 1_000_000);

        assertThatThrownBy(() -> reader.read(SPACE,
                "spaces/018f0f70-8e10-7b14-8f1a-999999999999/artifact/text",
                "a".repeat(64), 1, new CancellationToken()))
                .isInstanceOf(ArtifactContentReadException.class);
    }

    @Test
    void rejectsUrlLikeReferencesAndTraversal() {
        var reader = new S3ArtifactContentReader(mock(MinioClient.class), "ragforge", "phase3", 1_000_000);

        assertThatThrownBy(() -> reader.read(SPACE, "https://example.com/file",
                "a".repeat(64), 1, new CancellationToken()))
                .isInstanceOf(ArtifactContentReadException.class);
        assertThatThrownBy(() -> reader.read(SPACE, "spaces/" + SPACE + "/../secret",
                "a".repeat(64), 1, new CancellationToken()))
                .isInstanceOf(ArtifactContentReadException.class);
    }
}
