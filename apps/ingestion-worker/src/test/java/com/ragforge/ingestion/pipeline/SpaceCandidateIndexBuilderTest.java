package com.ragforge.ingestion.pipeline;

import com.ragforge.ingestion.objectstore.ContentAddressedObjectStore;
import com.ragforge.ingestion.objectstore.ObjectKey;
import com.ragforge.ingestion.objectstore.StoredObject;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpaceCandidateIndexBuilderTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void buildsOneCandidateFromAllActiveChunksInTheSpace() throws Exception {
        UUID spaceId = UUID.randomUUID();
        UUID documentOne = UUID.randomUUID();
        UUID documentTwo = UUID.randomUUID();
        UUID revisionOne = UUID.randomUUID();
        UUID revisionTwo = UUID.randomUUID();
        UUID parentOne = UUID.randomUUID();
        UUID parentTwo = UUID.randomUUID();
        UUID childOne = UUID.randomUUID();
        UUID childTwo = UUID.randomUUID();
        String textOne = "alpha from first note";
        String textTwo = "beta from second note";
        String hashOne = sha256(textOne);
        String hashTwo = sha256(textTwo);
        ObjectKey keyOne = key(spaceId, revisionOne, hashOne);
        ObjectKey keyTwo = key(spaceId, revisionTwo, hashTwo);

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ContentAddressedObjectStore store = mock(ContentAddressedObjectStore.class);
        OllamaEmbeddingClient embedding = mock(OllamaEmbeddingClient.class);
        QdrantIndexWriter qdrant = mock(QdrantIndexWriter.class);
        ResultSet rowOne = row(documentOne, childOne, revisionOne, parentOne, hashOne, textOne, keyOne);
        ResultSet rowTwo = row(documentTwo, childTwo, revisionTwo, parentTwo, hashTwo, textTwo, keyTwo);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(spaceId))).thenAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(rowOne, 0), mapper.mapRow(rowTwo, 1));
        });
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(spaceId))).thenReturn(3);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(store.get(keyOne)).thenReturn(new StoredObject(keyOne, "text/plain", textOne.length(), hashOne,
                Instant.now(), textOne.getBytes(StandardCharsets.UTF_8)));
        when(store.get(keyTwo)).thenReturn(new StoredObject(keyTwo, "text/plain", textTwo.length(), hashTwo,
                Instant.now(), textTwo.getBytes(StandardCharsets.UTF_8)));
        when(embedding.embed(textOne)).thenReturn(List.of(0.1, 0.2));
        when(embedding.embed(textTwo)).thenReturn(List.of(0.3, 0.4));
        when(qdrant.validateCandidate(anyString(), eq(spaceId), any(UUID.class), any(List.class)))
                .thenReturn(new QdrantIndexWriter.Validation(true, true));

        SpaceCandidateIndexBuilder builder = new SpaceCandidateIndexBuilder(jdbc, store, embedding, qdrant,
                "nomic-embed-text:latest");
        SpaceCandidateIndexBuilder.IndexResult result = builder.build(spaceId, Instant.now());

        assertThat(result.documentCount()).isEqualTo(2);
        assertThat(result.childChunkCount()).isEqualTo(2);
        assertThat(result.dimension()).isEqualTo(2);
        verify(qdrant).createAndUpsert(anyString(), eq(2), eq(spaceId), eq(result.indexId()), any(List.class));
        verify(qdrant).validateCandidate(anyString(), eq(spaceId), eq(result.indexId()), any(List.class));
        verify(jdbc).query(contains("d.active_revision_id"), any(RowMapper.class), eq(spaceId));
    }

    private static ResultSet row(UUID documentId, UUID childId, UUID revisionId, UUID parentId,
                                 String textHash, String text, ObjectKey key) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("source_document_id", UUID.class)).thenReturn(documentId);
        when(row.getObject("child_id", UUID.class)).thenReturn(childId);
        when(row.getObject("document_revision_id", UUID.class)).thenReturn(revisionId);
        when(row.getObject("parent_chunk_id", UUID.class)).thenReturn(parentId);
        when(row.getString("content_ref")).thenReturn("opaque/" + childId);
        when(row.getString("text_hash")).thenReturn(textHash);
        when(row.getInt("char_start")).thenReturn(0);
        when(row.getInt("char_end")).thenReturn(text.length());
        when(row.getString("storage_uri")).thenReturn(key.value());
        when(row.getString("sha256")).thenReturn(key.contentHash());
        when(row.getLong("byte_length")).thenReturn((long) text.length());
        when(row.getString("media_type")).thenReturn("text/plain");
        return row;
    }

    private static ObjectKey key(UUID spaceId, UUID revisionId, String hash) {
        return new ObjectKey(spaceId, UUID.randomUUID(), revisionId, UUID.randomUUID(), hash);
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
