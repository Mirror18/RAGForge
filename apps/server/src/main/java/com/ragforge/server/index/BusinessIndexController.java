package com.ragforge.server.index;

import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.ingestion.CursorCodec;
import com.ragforge.server.ingestion.CursorPage;
import com.ragforge.server.ingestion.SourceTaskCenterService;
import com.ragforge.server.provider.SpaceAuthorization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
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
import java.util.ArrayList;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/indexes")
public class BusinessIndexController {
    private final JdbcTemplate jdbc;
    private final IndexRepository indexes;
    private final CandidateIndexService candidateIndexes;
    private final SpaceAuthorization authorization;
    private final SourceTaskCenterService taskCenter;

    public BusinessIndexController(JdbcTemplate jdbc, IndexRepository indexes, CandidateIndexService candidateIndexes,
                                   SpaceAuthorization authorization, SourceTaskCenterService taskCenter) {
        this.jdbc = jdbc;
        this.indexes = indexes;
        this.candidateIndexes = candidateIndexes;
        this.authorization = authorization;
        this.taskCenter = taskCenter;
    }

    @GetMapping
    public CursorPage<IndexView> list(@PathVariable UUID spaceId, @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit,
                                      @RequestParam(required = false) String state,
                                      @AuthenticationPrincipal SessionPrincipal principal) {
        authorization.requireMember(spaceId, principal);
        int pageSize = limit == null ? 20 : limit;
        if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        CursorCodec.Position position = cursor == null ? null : CursorCodec.decode(cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT id, space_id, version_no, index_state, candidate_collection,
                       embedding_profile_version, chunking_strategy_version, document_revision_count,
                       child_chunk_count, validation_vector_dimension, validation_sample_retrieval_passed,
                       validation_space_filter_passed, activated_at, created_at
                FROM index_versions WHERE space_id = ? AND lifecycle_state = 'ACTIVE'
                """);
        List<Object> args = new ArrayList<>(List.of(spaceId));
        if (state != null && !state.isBlank()) {
            sql.append(" AND index_state = ?");
            args.add(state.toUpperCase(java.util.Locale.ROOT));
        }
        if (position != null) {
            sql.append(" AND (created_at, id) < (?, ?)");
            args.add(Timestamp.from(position.sortTime()));
            args.add(position.id());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(pageSize + 1);
        List<IndexRow> rows = jdbc.query(sql.toString(), (rs, row) -> new IndexRow(map(rs), instant(rs, "created_at")), args.toArray());
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) rows = rows.subList(0, pageSize);
        String next = hasMore ? CursorCodec.encode(new CursorCodec.Position(
                rows.get(rows.size() - 1).sortTime(), rows.get(rows.size() - 1).index().indexVersionId())) : null;
        return new CursorPage<>(rows.stream().map(IndexRow::index).toList(), next);
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

    @PostMapping("/{indexVersionId}/archive")
    public SourceTaskCenterService.TaskActionView archive(@PathVariable UUID spaceId, @PathVariable UUID indexVersionId,
                                                           @RequestBody(required = false) SourceTaskCenterService.ActionRequest body,
                                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                           @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                           @AuthenticationPrincipal SessionPrincipal principal,
                                                           jakarta.servlet.http.HttpServletRequest request) {
        return taskCenter.command(spaceId, SourceTaskCenterService.ResourceType.INDEX, indexVersionId,
                SourceTaskCenterService.Operation.ARCHIVE, body, key, ifMatch, principal, request);
    }

    @DeleteMapping("/{indexVersionId}")
    public SourceTaskCenterService.TaskActionView delete(@PathVariable UUID spaceId, @PathVariable UUID indexVersionId,
                                                         @RequestBody(required = false) SourceTaskCenterService.ActionRequest body,
                                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                         @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                         @AuthenticationPrincipal SessionPrincipal principal,
                                                         jakarta.servlet.http.HttpServletRequest request) {
        return taskCenter.command(spaceId, SourceTaskCenterService.ResourceType.INDEX, indexVersionId,
                SourceTaskCenterService.Operation.DELETE, body, key, ifMatch, principal, request);
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

    private record IndexRow(IndexView index, Instant sortTime) { }

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
