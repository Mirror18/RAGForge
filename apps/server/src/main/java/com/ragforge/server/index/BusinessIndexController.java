package com.ragforge.server.index;

import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceAuthorization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/indexes")
public class BusinessIndexController {
    private final JdbcTemplate jdbc;
    private final IndexRepository indexes;
    private final CandidateIndexService candidateIndexes;
    private final SpaceAuthorization authorization;

    public BusinessIndexController(JdbcTemplate jdbc, IndexRepository indexes, CandidateIndexService candidateIndexes,
                                   SpaceAuthorization authorization) {
        this.jdbc = jdbc;
        this.indexes = indexes;
        this.candidateIndexes = candidateIndexes;
        this.authorization = authorization;
    }

    @GetMapping
    public List<IndexView> list(@PathVariable UUID spaceId, @AuthenticationPrincipal SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        return jdbc.query("""
                SELECT id, space_id, version_no, index_state, candidate_collection,
                       embedding_profile_version, chunking_strategy_version, document_revision_count,
                       child_chunk_count, validation_vector_dimension, validation_sample_retrieval_passed,
                       validation_space_filter_passed, activated_at, created_at
                FROM index_versions WHERE space_id = ? ORDER BY version_no DESC
                """, (rs, row) -> map(rs), spaceId);
    }

    @GetMapping("/active")
    public ResponseEntity<ActiveIndexView> active(@PathVariable UUID spaceId, @AuthenticationPrincipal SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        return indexes.findActivePointer(spaceId).map(pointer -> {
            IndexRepository.IndexVersion index = indexes.findVersion(spaceId, pointer.activeIndexVersionId()).orElse(null);
            return ResponseEntity.ok(new ActiveIndexView(pointer, index, index == null ? null : datasetHash(spaceId, index)));
        }).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{indexVersionId}/publish")
    public IndexRepository.ActiveIndexPointer publish(@PathVariable UUID spaceId, @PathVariable UUID indexVersionId,
                                                      @AuthenticationPrincipal SessionPrincipal principal) {
        authorization.requireWrite(spaceId, principal);
        return candidateIndexes.publish(spaceId, indexVersionId, Instant.now());
    }

    private static IndexView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new IndexView(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getInt("version_no"), rs.getString("index_state"), rs.getString("candidate_collection"),
                rs.getString("embedding_profile_version"), rs.getString("chunking_strategy_version"),
                rs.getInt("document_revision_count"), rs.getInt("child_chunk_count"),
                (Integer) rs.getObject("validation_vector_dimension"),
                (Boolean) rs.getObject("validation_sample_retrieval_passed"),
                (Boolean) rs.getObject("validation_space_filter_passed"), instant(rs, "activated_at"), instant(rs, "created_at"));
    }
    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException { Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant(); }

    public record IndexView(UUID indexVersionId, UUID spaceId, int versionNo, String state, String candidateCollection,
                            String embeddingProfileVersion, String chunkingStrategyVersion, int documentRevisionCount,
                            int childChunkCount, Integer validationVectorDimension, Boolean sampleRetrievalPassed,
                            Boolean spaceFilterPassed, Instant activatedAt, Instant createdAt) {}
    public record ActiveIndexView(IndexRepository.ActiveIndexPointer pointer, IndexRepository.IndexVersion index,
                                  String datasetHash) {
        public ActiveIndexView(IndexRepository.ActiveIndexPointer pointer, IndexRepository.IndexVersion index) {
            this(pointer, index, null);
        }
    }

    private static String datasetHash(UUID spaceId, IndexRepository.IndexVersion index) {
        String value = "retrieval-dataset-v1|" + spaceId + "|" + index.id() + "|"
                + index.versionNo() + "|" + index.embeddingProfileVersion() + "|"
                + index.chunkingStrategyVersion() + "|" + index.documentRevisionCount() + "|"
                + index.childChunkCount();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime", exception);
        }
    }
}
