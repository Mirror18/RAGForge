package com.ragforge.ingestion.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ragforge.ingestion.messaging.IngestionEventEnvelope;
import com.ragforge.ingestion.messaging.IngestionJobRequestedPayload;
import com.ragforge.ingestion.objectstore.ContentAddressedObjectStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class BusinessIngestionSideEffectHandlerTest {
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    @Test
    void rejectsArtifactIdentityMismatchBeforeDatabaseOrObjectAccess() {
        UUID spaceId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID foreignSourceId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        String hash = "a".repeat(64);
        IngestionJobRequestedPayload payload = new IngestionJobRequestedPayload(
                UUID.randomUUID(), sourceId, revisionId, UUID.randomUUID(), UUID.randomUUID(), "DOCUMENT_UPSERT",
                new IngestionJobRequestedPayload.ArtifactReference(artifactId, "text/markdown", 8, hash,
                        "spaces/" + spaceId + "/sources/" + foreignSourceId + "/revisions/" + revisionId
                                + "/artifacts/" + artifactId + "/sha256/" + hash));
        IngestionEventEnvelope envelope = new IngestionEventEnvelope(
                UUID.randomUUID(), "ingestion.job.requested.v1", Instant.now(), "test",
                UUID.randomUUID(), UUID.randomUUID(), spaceId, UUID.randomUUID(), mapper.valueToTree(payload));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ContentAddressedObjectStore store = mock(ContentAddressedObjectStore.class);
        BusinessIngestionSideEffectHandler handler = new BusinessIngestionSideEffectHandler(
                jdbc, mapper, store, mock(SpaceCandidateIndexBuilder.class));

        assertThatThrownBy(() -> handler.handle(envelope, payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("artifact reference identity validation failed");
        verifyNoInteractions(jdbc, store);
    }

    @Test
    void splitsLongMarkdownAtSafeBoundariesWithoutGapsOrOverlaps() {
        String text = "# Linux\n" + "a".repeat(2_300) + "\n\n" + "df -h\n" + "b".repeat(2_300);

        List<BusinessIngestionSideEffectHandler.ChunkRange> ranges =
                BusinessIngestionSideEffectHandler.chunkRanges(text);

        assertThat(ranges).isNotEmpty();
        assertThat(ranges.get(0).start()).isZero();
        assertThat(ranges.get(ranges.size() - 1).end()).isEqualTo(text.length());
        assertThat(ranges).allSatisfy(range -> {
            assertThat(range.end()).isGreaterThan(range.start());
            assertThat(range.end() - range.start()).isLessThanOrEqualTo(2_000);
        });
        for (int index = 1; index < ranges.size(); index++) {
            assertThat(ranges.get(index).start()).isEqualTo(ranges.get(index - 1).end());
        }
    }

    @Test
    void keepsShortMarkdownAsOneChunk() {
        assertThat(BusinessIngestionSideEffectHandler.chunkRanges("Linux\ndf -h\nfree -h"))
                .containsExactly(new BusinessIngestionSideEffectHandler.ChunkRange(0, 19));
    }
}
