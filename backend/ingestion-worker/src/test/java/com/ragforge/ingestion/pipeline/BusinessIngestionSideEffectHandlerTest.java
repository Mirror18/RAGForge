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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

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
    void newSpaceRetrievalSeedUsesM1ZhDefaultV1Numbers() {
        assertThat(BusinessIngestionSideEffectHandler.defaultRetrievalProfileVersion())
                .isEqualTo("m1-zh-default-v1");
        assertThat(BusinessIngestionSideEffectHandler.DEFAULT_DENSE_TOP_K).isEqualTo(8);
        assertThat(BusinessIngestionSideEffectHandler.DEFAULT_BM25_TOP_K).isEqualTo(8);
        assertThat(BusinessIngestionSideEffectHandler.DEFAULT_RRF_K).isEqualTo(60);
        assertThat(BusinessIngestionSideEffectHandler.DEFAULT_RRF_DENSE_WEIGHT).isEqualTo(0.8);
        assertThat(BusinessIngestionSideEffectHandler.DEFAULT_RRF_BM25_WEIGHT).isEqualTo(0.4);
        assertThat(BusinessIngestionSideEffectHandler.DEFAULT_RERANK_TOP_K).isEqualTo(6);
        assertThat(BusinessIngestionSideEffectHandler.DEFAULT_MAX_CONTEXT_CHILDREN).isEqualTo(6);
        assertThat(BusinessIngestionSideEffectHandler.DEFAULT_MAX_CONTEXT_TOKENS).isEqualTo(768);
    }

    @Test
    void ensureRetrievalProfileSkipsWhenActivePointerAlreadyExists() {
        UUID spaceId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(spaceId))).thenReturn(1);
        BusinessIngestionSideEffectHandler handler = new BusinessIngestionSideEffectHandler(
                jdbc, mapper, mock(ContentAddressedObjectStore.class), mock(SpaceCandidateIndexBuilder.class));

        handler.ensureRetrievalProfile(spaceId, Instant.now());

        verify(jdbc).queryForObject(anyString(), eq(Integer.class), eq(spaceId));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void chunksMarkdownWithP4DefaultStrategyVersion() {
        UUID spaceId = UUID.fromString("0190f5c2-7c1e-7abc-8def-1234567890ab");
        UUID revisionId = UUID.fromString("0190f5c2-7c1e-7abc-8def-1234567890b6");
        String text = """
                # 合成运维手册

                这是一段用于对照分块引擎的合成中文说明。重复若干句以超过旧的两千字符切分。
                """.repeat(8);

        var result = BusinessIngestionSideEffectHandler.chunkDocument(spaceId, revisionId, text);

        assertThat(BusinessIngestionSideEffectHandler.strategyVersion()).isEqualTo("p4-default-v1");
        assertThat(result.children()).isNotEmpty();
        assertThat(result.children()).allSatisfy(child -> {
            assertThat(child.contentRef()).contains("p4-default-v1");
            assertThat(child.charEnd()).isGreaterThan(child.charStart());
        });
    }

    @Test
    void derivesManifestLineageFromControlledArtifactReferences() {
        UUID spaceId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID parentArtifactId = UUID.randomUUID();
        UUID objectArtifactId = UUID.randomUUID();
        String hash = "a".repeat(64);
        String objectRef = "spaces/" + spaceId + "/sources/" + UUID.randomUUID() + "/revisions/" + revisionId
                + "/artifacts/" + objectArtifactId + "/sha256/" + hash;

        BusinessIngestionSideEffectHandler.ManifestWrite manifest = BusinessIngestionSideEffectHandler.manifestWrite(
                UUID.randomUUID(), spaceId, revisionId, pipelineId, parentArtifactId, objectArtifactId, hash,
                objectRef, "native-fixture-parser", "1.0.0", Instant.parse("2026-09-06T00:00:00Z"));

        assertThat(manifest.spaceId()).isEqualTo(spaceId);
        assertThat(manifest.parentArtifactId()).isEqualTo(parentArtifactId);
        assertThat(manifest.objectArtifactId()).isEqualTo(objectArtifactId);
        assertThat(manifest.contentHash()).isEqualTo(hash);
        assertThat(manifest.objectRef()).isEqualTo(objectRef);
        assertThat(manifest.locationMappingVersion()).isEqualTo("object-key.v1");
    }
}
