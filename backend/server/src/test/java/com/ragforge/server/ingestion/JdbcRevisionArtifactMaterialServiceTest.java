package com.ragforge.server.ingestion;

import com.ragforge.server.provider.adapter.CancellationToken;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcRevisionArtifactMaterialServiceTest {
    private static final UUID SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");
    private static final UUID REVISION = UUID.fromString("018f0f70-8e10-7b14-8f1a-222222222222");
    private static final String REF = "spaces/" + SPACE + "/revisions/chunk-1";
    private static final String TEXT = "trusted chunk text";
    private static final String HASH = sha256(TEXT);

    @Test
    void resolvesOnlyTheSpaceScopedChunkSliceAndRechecksHash() throws Exception {
        JdbcTemplate jdbc = pointerQuery(0, TEXT.length(), HASH);
        ArtifactContentReader reader = (space, uri, artifactHash, byteLength, token) ->
                TEXT.getBytes(StandardCharsets.UTF_8);

        var material = new JdbcRevisionArtifactMaterialService(jdbc, reader)
                .resolve(SPACE, REVISION, REF, HASH, new CancellationToken());

        assertThat(material).isNotNull();
        assertThat(material.spaceId()).isEqualTo(SPACE);
        assertThat(material.documentRevisionId()).isEqualTo(REVISION);
        assertThat(material.contentRef()).isEqualTo(REF);
        assertThat(material.textHash()).isEqualTo(HASH);
        assertThat(material.text()).isEqualTo(TEXT);
    }

    @Test
    void rejectsSliceWhenStoredChunkHashDoesNotMatchRequestedHash() throws Exception {
        JdbcTemplate jdbc = pointerQuery(0, TEXT.length(), "b".repeat(64));
        ArtifactContentReader reader = (space, uri, artifactHash, byteLength, token) ->
                TEXT.getBytes(StandardCharsets.UTF_8);

        var material = new JdbcRevisionArtifactMaterialService(jdbc, reader)
                .resolve(SPACE, REVISION, REF, HASH, new CancellationToken());

        assertThat(material).isNull();
    }

    @Test
    void doesNotReadStorageAfterCancellation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CancellationToken cancellation = new CancellationToken();
        cancellation.cancel();
        boolean[] read = {false};
        ArtifactContentReader reader = (space, uri, artifactHash, byteLength, token) -> {
            read[0] = true;
            return new byte[0];
        };

        var material = new JdbcRevisionArtifactMaterialService(jdbc, reader)
                .resolve(SPACE, REVISION, REF, HASH, cancellation);

        assertThat(material).isNull();
        assertThat(read[0]).isFalse();
    }

    private static JdbcTemplate pointerQuery(int start, int end, String hash) throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getInt("char_start")).thenReturn(start);
        when(resultSet.getInt("char_end")).thenReturn(end);
        when(resultSet.getString("text_hash")).thenReturn(hash);
        when(resultSet.getString("storage_uri")).thenReturn("spaces/" + SPACE + "/artifact/text");
        when(resultSet.getString("sha256")).thenReturn("c".repeat(64));
        when(resultSet.getLong("byte_length")).thenReturn((long) TEXT.getBytes(StandardCharsets.UTF_8).length);
        when(resultSet.getString("media_type")).thenReturn("text/plain");
        when(jdbc.query(anyString(), any(RowMapper.class), eq(SPACE), eq(REVISION), eq(REF), eq(HASH)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        return jdbc;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
