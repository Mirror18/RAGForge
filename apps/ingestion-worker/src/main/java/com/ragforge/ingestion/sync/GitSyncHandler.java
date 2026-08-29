package com.ragforge.ingestion.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.ingestion.connector.ChangeKind;
import com.ragforge.ingestion.connector.CheckpointCommitResult;
import com.ragforge.ingestion.connector.DiscoveryRules;
import com.ragforge.ingestion.connector.GitConnector;
import com.ragforge.ingestion.connector.GitRepositoryCheckout;
import com.ragforge.ingestion.connector.SourceChange;
import com.ragforge.ingestion.connector.SourceChangeSet;
import com.ragforge.ingestion.connector.SourceCheckpoint;
import com.ragforge.ingestion.connector.SourceObjectSnapshot;
import com.ragforge.ingestion.connector.FetchedContent;
import com.ragforge.ingestion.common.UuidV7;
import com.ragforge.ingestion.objectstore.ContentAddressedObjectStore;
import com.ragforge.ingestion.objectstore.ObjectKey;
import com.ragforge.ingestion.messaging.EnvelopeValidationException;
import com.ragforge.ingestion.messaging.GitSyncRequestedPayload;
import com.ragforge.ingestion.messaging.IngestionEventEnvelope;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Consumes source sync commands and atomically advances the durable Git catalog checkpoint. */
@Component
@ConditionalOnProperty(name = "ragforge.ingestion.enabled", havingValue = "true")
public class GitSyncHandler {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ContentAddressedObjectStore store;
    private final TransactionTemplate transaction;

    public GitSyncHandler(JdbcTemplate jdbc, ObjectMapper mapper, ContentAddressedObjectStore store,
                          PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc; this.mapper = mapper; this.store = store;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @RabbitListener(queues = "${ragforge.messaging.source-sync-queue:ragforge.ingestion.source-sync}",
            ackMode = "AUTO", autoStartup = "${ragforge.ingestion.enabled:false}")
    public void onMessage(Message message) throws Exception {
        IngestionEventEnvelope envelope = mapper.readValue(message.getBody(), IngestionEventEnvelope.class);
        envelope.validate();
        GitSyncRequestedPayload payload = mapper.treeToValue(envelope.payload(), GitSyncRequestedPayload.class);
        payload.validate();
        try {
            transaction.executeWithoutResult(status -> handle(envelope, payload));
        } catch (RuntimeException failure) {
            // The sync transaction has rolled back. Persist failure status separately so
            // retries and the status API can observe the terminal state.
            transaction.executeWithoutResult(status -> updateJob(envelope.spaceId(), payload.jobId(), "FAILED"));
            throw failure;
        }
    }

    void handle(IngestionEventEnvelope envelope, GitSyncRequestedPayload payload) {
        UUID spaceId = envelope.spaceId();
        SourceConfig config = loadConfig(spaceId, payload.sourceId());
        updateJob(spaceId, payload.jobId(), "RUNNING");
        Path checkout = GitRepositoryCheckout.checkout(config.remote(), config.branch());
        try {
            // Git discovery always enumerates the complete selected tree. FULL_SYNC controls
            // the caller's intent; the durable checkpoint remains the comparison baseline so
            // a full scan can still detect deletes and renames safely.
            SourceCheckpoint checkpoint = loadCheckpoint(spaceId, payload.sourceId());
            GitConnector connector = new GitConnector(spaceId, payload.sourceId(), checkout);
            SourceChangeSet changes = connector.discover(checkpoint, config.rules());
            persistCatalog(spaceId, payload.sourceId(), changes, envelope.correlationId());
            enqueueDocumentJobs(spaceId, payload.sourceId(), changes, connector, envelope);
            connector.commitCheckpoint(changes, CheckpointCommitResult.successful(changes.changeSetId()));
            updateCheckpoint(spaceId, payload.sourceId(), changes);
            updateJob(spaceId, payload.jobId(), "SUCCEEDED");
        } finally {
            delete(checkout);
        }
    }

    private SourceConfig loadConfig(UUID spaceId, UUID sourceId) {
        return jdbc.queryForObject("""
                SELECT root_ref, COALESCE(git_branch, 'main'), include_rules::text, exclude_rules::text
                FROM source_versions WHERE space_id = ? AND source_id = ? AND connector_type = 'GIT'
                ORDER BY version_no DESC LIMIT 1
                """, (rs, row) -> new SourceConfig(rs.getString(1), rs.getString(2),
                rules(rs.getString(3), rs.getString(4))), spaceId, sourceId);
    }

    private SourceCheckpoint loadCheckpoint(UUID spaceId, UUID sourceId) {
        var row = jdbc.queryForMap("SELECT cursor_value FROM source_checkpoints WHERE space_id = ? AND source_id = ?", spaceId, sourceId);
        Map<String, SourceObjectSnapshot> objects = new HashMap<>();
        jdbc.query("""
                SELECT stable_source_object_id, canonical_source_path, source_version, content_hash, byte_length, media_type, provenance
                FROM source_checkpoint_objects WHERE space_id = ? AND source_id = ?
                """, (rs, n) -> {
            SourceObjectSnapshot snapshot = new SourceObjectSnapshot(spaceId, sourceId,
                    UUID.fromString(rs.getString("stable_source_object_id")), rs.getString("canonical_source_path"),
                    rs.getString("source_version"), rs.getString("content_hash"), rs.getLong("byte_length"),
                    rs.getString("media_type"), rs.getString("provenance"));
            objects.put(snapshot.canonicalPath(), snapshot); return snapshot;
        }, spaceId, sourceId);
        return new SourceCheckpoint(spaceId, sourceId, row.get("cursor_value") == null ? "" : row.get("cursor_value").toString(), objects);
    }

    private void persistCatalog(UUID spaceId, UUID sourceId, SourceChangeSet changes, UUID correlationId) {
        Instant now = Instant.now();
        for (SourceChange change : changes.changes()) {
            if (change.kind() == ChangeKind.DELETE) {
                jdbc.update("DELETE FROM source_checkpoint_objects WHERE space_id = ? AND source_id = ? AND canonical_source_path = ?", spaceId, sourceId, change.canonicalPath());
                jdbc.update("UPDATE source_documents SET current_state = 'DELETED', active_revision_id = NULL, updated_at = ? WHERE space_id = ? AND source_id = ? AND stable_source_object_id = ?", Timestamp.from(now), spaceId, sourceId, change.stableSourceObjectId().toString());
                jdbc.update("DELETE FROM active_document_pointers WHERE space_id = ? AND source_document_id IN (SELECT id FROM source_documents WHERE space_id = ? AND source_id = ? AND stable_source_object_id = ?)", spaceId, spaceId, sourceId, change.stableSourceObjectId().toString());
                continue;
            }
            if (change.previousCanonicalPath() != null) jdbc.update("DELETE FROM source_checkpoint_objects WHERE space_id = ? AND source_id = ? AND canonical_source_path = ?", spaceId, sourceId, change.previousCanonicalPath());
            jdbc.update("""
                    INSERT INTO source_checkpoint_objects (id, space_id, source_id, canonical_source_path, stable_source_object_id,
                        source_version, content_hash, byte_length, media_type, provenance, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (space_id, source_id, canonical_source_path) DO UPDATE SET stable_source_object_id = EXCLUDED.stable_source_object_id,
                        source_version = EXCLUDED.source_version, content_hash = EXCLUDED.content_hash, byte_length = EXCLUDED.byte_length,
                        media_type = EXCLUDED.media_type, provenance = EXCLUDED.provenance, updated_at = EXCLUDED.updated_at
                    """, UuidV7.random(), spaceId, sourceId, change.canonicalPath(), change.stableSourceObjectId().toString(), change.sourceVersion(), change.contentHash(), change.byteLength(), change.mediaType(), change.provenance(), Timestamp.from(now));
            if (change.kind() == ChangeKind.UNCHANGED) continue;
            var existing = jdbc.query("SELECT id FROM source_documents WHERE space_id = ? AND source_id = ? AND stable_source_object_id = ?", (rs, n) -> rs.getObject(1, UUID.class), spaceId, sourceId, change.stableSourceObjectId().toString());
            if (existing.isEmpty()) jdbc.update("INSERT INTO source_documents (id, space_id, source_id, stable_source_object_id, canonical_source_path, basename, version_no, current_state, correlation_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 1, 'ACTIVE', ?, ?, ?)", UuidV7.random(), spaceId, sourceId, change.stableSourceObjectId().toString(), change.canonicalPath(), Path.of(change.canonicalPath()).getFileName().toString(), correlationId, Timestamp.from(now), Timestamp.from(now));
            else jdbc.update("UPDATE source_documents SET canonical_source_path = ?, basename = ?, current_state = 'ACTIVE', version_no = version_no + 1, updated_at = ? WHERE space_id = ? AND id = ?", change.canonicalPath(), Path.of(change.canonicalPath()).getFileName().toString(), Timestamp.from(now), spaceId, existing.get(0));
        }
    }

    private void enqueueDocumentJobs(UUID spaceId, UUID sourceId, SourceChangeSet changes, GitConnector connector,
                                     IngestionEventEnvelope syncEnvelope) {
        for (SourceChange change : changes.changes()) {
            if (change.kind() == ChangeKind.UNCHANGED || change.kind() == ChangeKind.DELETE) continue;
            try (FetchedContent content = connector.fetch(change.reference(), changes.sourceVersion())) {
                byte[] bytes = content.stream().readAllBytes();
                UUID documentId = jdbc.queryForObject("SELECT id FROM source_documents WHERE space_id = ? AND source_id = ? AND stable_source_object_id = ?", UUID.class, spaceId, sourceId, change.stableSourceObjectId().toString());
                UUID revisionId = UuidV7.random();
                UUID discoveryRevisionId = UuidV7.random();
                UUID artifactId = UuidV7.random();
                UUID jobId = UuidV7.random();
                UUID attemptId = UuidV7.random();
                UUID pipelineId = UuidV7.random();
                Instant now = Instant.now();
                String hash = change.contentHash();
                ObjectKey key = new ObjectKey(spaceId, sourceId, revisionId, artifactId, hash);
                store.put(key, content.metadata().mediaType(), bytes);
                int pipelineVersion = jdbc.queryForObject("SELECT COALESCE(MAX(version_no), 0) + 1 FROM pipeline_versions WHERE space_id = ? AND pipeline_name = ?", Integer.class, spaceId, "git-document");
                jdbc.update("INSERT INTO pipeline_versions (id, space_id, version_no, pipeline_name, parser_name, parser_version, configuration_hash, correlation_id, created_at) VALUES (?, ?, ?, 'git-document', 'ragforge-native-parser', '1.0.0', ?, ?, ?)", pipelineId, spaceId, pipelineVersion, "git-document-v1", syncEnvelope.correlationId(), Timestamp.from(now));
                int revisionNo = jdbc.queryForObject("SELECT COALESCE(MAX(revision_no), 0) + 1 FROM document_revisions WHERE space_id = ? AND source_document_id = ?", Integer.class, spaceId, documentId);
                jdbc.update("INSERT INTO document_revisions (id, space_id, source_document_id, revision_no, source_version, canonical_source_path, content_hash, revision_state, immutable, discovered_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'DISCOVERED', TRUE, ?, ?)", discoveryRevisionId, spaceId, documentId, revisionNo, changes.sourceVersion(), change.canonicalPath(), hash, Timestamp.from(now), Timestamp.from(now));
                String storageUri = key.value();
                jdbc.update("INSERT INTO ingestion_jobs (id, space_id, source_id, source_document_id, document_revision_id, pipeline_version_id, status, idempotency_key, correlation_id, causation_id, version_no, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?, ?, 1, ?, ?)", jobId, spaceId, sourceId, documentId, discoveryRevisionId, pipelineId, "git-" + changes.sourceVersion() + "-" + change.stableSourceObjectId(), syncEnvelope.correlationId(), syncEnvelope.eventId(), Timestamp.from(now), Timestamp.from(now));
                jdbc.update("INSERT INTO ingestion_job_attempts (id, space_id, job_id, attempt_no, status, idempotency_key, correlation_id, started_at) VALUES (?, ?, ?, 1, 'RUNNING', ?, ?, ?)", attemptId, spaceId, jobId, "git-" + jobId, syncEnvelope.correlationId(), Timestamp.from(now));
                Map<String, Object> artifact = Map.of("artifactId", artifactId, "mediaType", content.metadata().mediaType(), "byteLength", bytes.length, "sha256", hash, "storageUri", storageUri);
                Map<String, Object> eventPayload = Map.of("jobId", jobId, "sourceId", sourceId, "documentRevisionId", revisionId,
                        "pipelineVersionId", pipelineId, "attemptId", attemptId, "operation", "DOCUMENT_UPSERT",
                        "sourceVersion", changes.sourceVersion(), "checkpointManagedBySourceSync", true, "artifactRef", artifact);
                String serialized = mapper.writeValueAsString(eventPayload);
                jdbc.update("INSERT INTO outbox_events (id, event_type, aggregate_id, space_id, correlation_id, causation_id, payload, occurred_at) VALUES (?, 'ingestion.job.requested.v1', ?, ?, ?, ?, CAST(? AS jsonb), ?)", UuidV7.random(), jobId, spaceId, syncEnvelope.correlationId(), syncEnvelope.eventId(), serialized, Timestamp.from(now));
            } catch (Exception exception) {
                throw new IllegalStateException("git document task creation failed", exception);
            }
        }
    }

    private void updateCheckpoint(UUID spaceId, UUID sourceId, SourceChangeSet changes) { jdbc.update("UPDATE source_checkpoints SET cursor_type = 'GIT_COMMIT', cursor_value = ?, last_successful_changeset_id = ?, updated_at = ? WHERE space_id = ? AND source_id = ?", changes.sourceVersion(), changes.changeSetId(), Timestamp.from(Instant.now()), spaceId, sourceId); }
    private void updateJob(UUID spaceId, UUID jobId, String status) { jdbc.update("UPDATE ingestion_jobs SET status = ?, version_no = version_no + 1, updated_at = ? WHERE space_id = ? AND id = ?", status, Timestamp.from(Instant.now()), spaceId, jobId); }
    private static DiscoveryRules rules(String include, String exclude) { try { ObjectMapper mapper = new ObjectMapper(); return new DiscoveryRules(mapper.readValue(include, new TypeReference<List<String>>() { }), mapper.readValue(exclude, new TypeReference<List<String>>() { }), Set.of(".md", ".markdown", ".txt"), 10L * 1024 * 1024 * 1024, 100_000); } catch (Exception e) { throw new EnvelopeValidationException("SOURCE_RULES_INVALID"); } }
    private record SourceConfig(String remote, String branch, DiscoveryRules rules) { }
    private static void delete(Path path) { try (var stream = Files.walk(path)) { stream.sorted(java.util.Comparator.reverseOrder()).forEach(value -> { try { Files.deleteIfExists(value); } catch (Exception ignored) { } }); } catch (Exception ignored) { } }
}
