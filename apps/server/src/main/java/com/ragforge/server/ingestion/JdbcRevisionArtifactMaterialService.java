package com.ragforge.server.ingestion;

import com.ragforge.server.answer.integration.RevisionArtifactMaterialService;
import com.ragforge.server.provider.adapter.CancellationToken;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-owned revision/artifact material service. It resolves chunk metadata
 * and the parsed-text artifact inside one requested space, then verifies the
 * selected slice against the immutable child-chunk hash.
 */
public final class JdbcRevisionArtifactMaterialService implements RevisionArtifactMaterialService {
    private final JdbcTemplate jdbc;
    private final ArtifactContentReader reader;

    public JdbcRevisionArtifactMaterialService(JdbcTemplate jdbc, ArtifactContentReader reader) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override
    public Material resolve(UUID spaceId, UUID documentRevisionId, String contentRef,
                            String expectedTextHash, CancellationToken cancellationToken) {
        if (spaceId == null || documentRevisionId == null || contentRef == null || contentRef.isBlank()
                || expectedTextHash == null || !expectedTextHash.matches("[0-9a-fA-F]{64}")
                || cancellationToken == null || cancellationToken.isCancellationRequested()) {
            return null;
        }
        Optional<MaterialPointer> pointer = findPointer(spaceId, documentRevisionId, contentRef, expectedTextHash);
        if (pointer.isEmpty() || cancellationToken.isCancellationRequested()) {
            return null;
        }
        MaterialPointer value = pointer.get();
        if (value.mediaType() == null || !value.mediaType().toLowerCase(Locale.ROOT).startsWith("text/")) {
            return null;
        }
        byte[] bytes = reader.read(spaceId, value.storageUri(), value.artifactSha256(),
                value.byteLength(), cancellationToken);
        if (cancellationToken.isCancellationRequested()) {
            return null;
        }
        String text = decodeUtf8(bytes);
        if (value.charStart() < 0 || value.charEnd() < value.charStart()
                || value.charEnd() > text.length()) {
            return null;
        }
        String slice = text.substring(value.charStart(), value.charEnd());
        String actualHash = sha256(slice);
        if (!actualHash.equalsIgnoreCase(value.textHash())
                || !actualHash.equalsIgnoreCase(expectedTextHash)) {
            return null;
        }
        try {
            return new Material(spaceId, documentRevisionId, contentRef, actualHash, slice);
        } catch (IllegalArgumentException invalidMaterial) {
            return null;
        }
    }

    private Optional<MaterialPointer> findPointer(UUID spaceId, UUID revisionId,
                                                   String contentRef, String textHash) {
        return jdbc.query("""
                SELECT c.char_start, c.char_end, c.text_hash,
                       a.storage_uri, a.sha256, a.byte_length, a.media_type
                FROM child_chunks c
                JOIN document_revisions r
                  ON r.id = c.document_revision_id AND r.space_id = c.space_id
                JOIN parse_reports p
                  ON p.document_revision_id = r.id AND p.space_id = r.space_id
                 AND p.status = 'SUCCEEDED'
                 AND p.extracted_text_artifact_id IS NOT NULL
                JOIN artifacts a
                  ON a.id = p.extracted_text_artifact_id AND a.space_id = p.space_id
                 AND a.document_revision_id = r.id
                 AND a.artifact_kind = 'PARSED_TEXT' AND a.immutable = TRUE
                WHERE c.space_id = ? AND c.document_revision_id = ?
                  AND c.content_ref = ? AND lower(c.text_hash) = lower(?)
                  AND r.revision_state = 'PARSED' AND r.immutable = TRUE
                ORDER BY c.chunk_index
                LIMIT 1
                """, (rs, row) -> new MaterialPointer(
                rs.getInt("char_start"), rs.getInt("char_end"), rs.getString("text_hash"),
                rs.getString("storage_uri"), rs.getString("sha256"), rs.getLong("byte_length"),
                rs.getString("media_type")), spaceId, revisionId, contentRef, textHash).stream().findFirst();
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return chars.toString();
        } catch (CharacterCodingException exception) {
            return "";
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the runtime", impossible);
        }
    }

    private record MaterialPointer(int charStart, int charEnd, String textHash,
                                   String storageUri, String artifactSha256, long byteLength,
                                   String mediaType) {
    }
}
