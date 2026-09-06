package com.ragforge.server.ingestion;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL proof for Phase 3 versioned ingestion boundaries. */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Phase3IngestionPersistenceIntegrationTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.0.1-alpine")
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        try {
            VALKEY.start();
        } catch (RuntimeException exception) {
            POSTGRES.stop();
            throw exception;
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + VALKEY.getHost() + ":"
                + VALKEY.getMappedPort(6379));
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IngestionRepository ingestion;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE artifact_manifests, ingestion_idempotency, pipeline_step_executions, ingestion_job_attempts, "
                + "ingestion_jobs, active_document_pointers, parse_reports, document_revisions, artifacts, "
                + "pipeline_versions, source_checkpoints, source_documents, source_versions, sources, "
                + "outbox_events, knowledge_spaces, users CASCADE");
    }

    @Test
    void migrationIsVersionedAndIngestionRowsAreSpaceScopedAndImmutable() {
        Set<String> tables = Set.copyOf(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN (
                    'source_versions', 'source_checkpoints', 'source_documents', 'document_revisions',
                    'artifacts', 'pipeline_versions', 'parse_reports', 'active_document_pointers',
                    'ingestion_jobs', 'ingestion_job_attempts', 'pipeline_step_executions', 'ingestion_idempotency')
                """, String.class));
        assertThat(tables).containsExactlyInAnyOrder(
                "source_versions", "source_checkpoints", "source_documents", "document_revisions",
                "artifacts", "pipeline_versions", "parse_reports", "active_document_pointers",
                "ingestion_jobs", "ingestion_job_attempts", "pipeline_step_executions", "ingestion_idempotency");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE version = '8'", String.class))
                .isEqualTo("8");

        UUID spaceA = createSpace("phase3-a");
        UUID spaceB = createSpace("phase3-b");
        UUID sourceId = UUID.randomUUID();
        UUID sourceVersionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        UUID correlation = UUID.randomUUID();
        ingestion.createSourceVersion(new IngestionRepository.NewSourceVersion(sourceVersionId, spaceA, sourceId, 1,
                IngestionRepository.ConnectorType.LOCAL_DIRECTORY, "Synthetic source",
                IngestionRepository.SourceState.ACTIVE, "file:synthetic", "[]", "[]", false, correlation, now));
        ingestion.createCheckpoint(new IngestionRepository.NewSourceCheckpoint(UUID.randomUUID(), spaceA, sourceId,
                sourceVersionId, 1, IngestionRepository.CursorType.FILESYSTEM_SCAN, "cursor-1", null, now));

        IngestionRepository.SourceDocument first = ingestion.createSourceDocument(
                new IngestionRepository.NewSourceDocument(UUID.randomUUID(), spaceA, sourceId, "object-a",
                        "alpha/meeting-notes.md", "meeting-notes.md", 1, IngestionRepository.DocumentState.ACTIVE,
                        null, correlation, now));
        IngestionRepository.SourceDocument second = ingestion.createSourceDocument(
                new IngestionRepository.NewSourceDocument(UUID.randomUUID(), spaceA, sourceId, "object-b",
                        "beta/meeting-notes.md", "meeting-notes.md", 1, IngestionRepository.DocumentState.ACTIVE,
                        null, correlation, now));
        assertThat(ingestion.listSourceDocuments(spaceA)).extracting(IngestionRepository.SourceDocument::id)
                .containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(ingestion.findSourceDocument(spaceB, first.id())).isEmpty();

        assertThatThrownBy(() -> jdbc.update("UPDATE source_versions SET display_name = 'tampered' WHERE id = ?",
                sourceVersionId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO source_documents
                    (id, space_id, source_id, stable_source_object_id, canonical_source_path,
                     basename, version_no, current_state, correlation_id, created_at, updated_at)
                VALUES (?, ?, ?, 'cross-space', 'cross-space.md', 'cross-space.md', 1, 'ACTIVE', ?, ?, ?)
                """, UUID.randomUUID(), spaceB, sourceId, correlation, now, now))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> ingestion.commitCheckpoint(new IngestionRepository.CheckpointCommit(
                spaceA, sourceId, sourceVersionId, 1, IngestionRepository.CursorType.FILESYSTEM_SCAN,
                "cursor-failed", UUID.randomUUID(), false, false, false, false, false, now)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ingestion.findCheckpoint(spaceA, sourceId)).get()
                .extracting(IngestionRepository.SourceCheckpoint::cursor).isEqualTo("cursor-1");
        ingestion.commitCheckpoint(new IngestionRepository.CheckpointCommit(
                spaceA, sourceId, sourceVersionId, 1, IngestionRepository.CursorType.FILESYSTEM_SCAN,
                "cursor-2", UUID.randomUUID(), true, true, true, true, true, now));
        assertThat(ingestion.findCheckpoint(spaceA, sourceId)).get()
                .extracting(IngestionRepository.SourceCheckpoint::cursor).isEqualTo("cursor-2");
    }

    @Test
    void jobIdempotencyIsSpaceScopedAndDuplicateKeysAreRejected() {
        UUID spaceA = createSpace("phase3-job-a");
        UUID sourceId = UUID.randomUUID();
        UUID sourceVersionId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        UUID correlation = UUID.randomUUID();
        ingestion.createSourceVersion(new IngestionRepository.NewSourceVersion(sourceVersionId, spaceA, sourceId, 1,
                IngestionRepository.ConnectorType.GIT, "Synthetic git", IngestionRepository.SourceState.ACTIVE,
                "git:synthetic", "[\"**/*.md\"]", "[]", false, correlation, now));
        ingestion.createPipelineVersion(new IngestionRepository.NewPipelineVersion(pipelineId, spaceA, 1,
                "default", "native-fixture-parser", "1.0.0",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", correlation, now));
        UUID jobId = UUID.randomUUID();
        ingestion.createJob(new IngestionRepository.NewIngestionJob(jobId, spaceA, sourceId, null, null, pipelineId,
                IngestionRepository.JobStatus.REQUESTED, "sync-001", correlation, null, 1, now));
        assertThatThrownBy(() -> ingestion.createJob(new IngestionRepository.NewIngestionJob(UUID.randomUUID(), spaceA,
                sourceId, null, null, pipelineId, IngestionRepository.JobStatus.REQUESTED, "sync-001", correlation,
                null, 1, now))).isInstanceOf(DataAccessException.class);
        IngestionRepository.JobAttempt attempt = ingestion.createAttempt(new IngestionRepository.NewJobAttempt(
                UUID.randomUUID(), spaceA, jobId, 1, IngestionRepository.AttemptStatus.RUNNING, "attempt-001",
                correlation, now, null));
        assertThat(ingestion.findAttempt(spaceA, attempt.id())).isPresent();
    }

    @Test
    void failedOrUnavailableParseCannotChangeActivePointer() {
        UUID space = createSpace("phase3-failed-parse");
        UUID sourceId = UUID.randomUUID();
        UUID sourceVersionId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        ingestion.createSourceVersion(new IngestionRepository.NewSourceVersion(sourceVersionId, space, sourceId, 1,
                IngestionRepository.ConnectorType.LOCAL_DIRECTORY, "Failed fixture", IngestionRepository.SourceState.ACTIVE,
                "file:failed-fixture", "[]", "[]", false, correlation, now));
        ingestion.createCheckpoint(new IngestionRepository.NewSourceCheckpoint(UUID.randomUUID(), space, sourceId,
                sourceVersionId, 1, IngestionRepository.CursorType.FILESYSTEM_SCAN, "cursor-1", null, now));
        ingestion.createPipelineVersion(new IngestionRepository.NewPipelineVersion(pipelineId, space, 1,
                "failed-fixture", "native-fixture-parser", "1.0.0",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", correlation, now));
        ingestion.createSourceDocument(new IngestionRepository.NewSourceDocument(documentId, space, sourceId,
                "stable-failed", "image-only.pdf", "image-only.pdf", 1, IngestionRepository.DocumentState.ACTIVE,
                null, correlation, now));
        UUID successfulRevisionId = UUID.randomUUID();
        UUID successfulArtifactId = UUID.randomUUID();
        UUID successfulReportId = UUID.randomUUID();
        ingestion.persistRevisionBundle(new IngestionRepository.RevisionBundleInput(
                space, documentId, successfulRevisionId, 1, "source-v0", "image-only.pdf",
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", successfulArtifactId, 1,
                IngestionRepository.ArtifactKind.SOURCE_BYTES, "application/pdf", 128,
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "spaces/" + space + "/sources/" + sourceId + "/revisions/" + successfulRevisionId
                        + "/artifacts/" + successfulArtifactId,
                "{}", successfulReportId, 1, IngestionRepository.ParseStatus.SUCCEEDED, 1, 32, 8, 1, 0,
                "native-fixture-parser", "1.0.0", 2, "[]", "[]", successfulArtifactId,
                IngestionRepository.OcrStatus.NOT_REQUESTED, null, null,
                IngestionRepository.OcrTriggerReason.NONE, IngestionRepository.OcrAuditState.NOT_APPLICABLE,
                null, now, now));
        ingestion.publishActivePointer(new IngestionRepository.NewActivePointer(
                UUID.randomUUID(), space, documentId, successfulRevisionId, 1, now));

        UUID revisionId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        ingestion.persistRevisionBundle(new IngestionRepository.RevisionBundleInput(
                space, documentId, revisionId, 2, "source-v1", "image-only.pdf",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", artifactId, 1,
                IngestionRepository.ArtifactKind.SOURCE_BYTES, "application/pdf", 128,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "spaces/" + space + "/sources/" + sourceId + "/revisions/" + revisionId + "/artifacts/" + artifactId,
                "{}", reportId, 1, IngestionRepository.ParseStatus.OCR_UNAVAILABLE, 1, 0, 0, 0, 0,
                "native-fixture-parser", "1.0.0", 2, "[]", "[\"OCR_UNAVAILABLE\"]", null,
                IngestionRepository.OcrStatus.UNAVAILABLE, null, null,
                IngestionRepository.OcrTriggerReason.IMAGE_ONLY_PDF, IngestionRepository.OcrAuditState.BLOCKED,
                null, now, now));

        assertThatThrownBy(() -> ingestion.publishActivePointer(new IngestionRepository.NewActivePointer(
                UUID.randomUUID(), space, documentId, revisionId, 1, now)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parsed revision");
        assertThat(ingestion.findSourceDocument(space, documentId)).get()
                .extracting(IngestionRepository.SourceDocument::activeRevisionId).isEqualTo(successfulRevisionId);
    }

    @Test
    void artifactManifestIsIdempotentImmutableAndFailsClosedAcrossSpaces() {
        UUID spaceA = createSpace("manifest-a");
        UUID spaceB = createSpace("manifest-b");
        UUID sourceId = UUID.randomUUID();
        UUID sourceVersionId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID sourceArtifactId = UUID.randomUUID();
        UUID textArtifactId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-06T00:00:00Z");
        String sourceHash = "a".repeat(64);
        String textHash = "b".repeat(64);
        String textRef = "spaces/" + spaceA + "/sources/" + sourceId + "/revisions/" + revisionId
                + "/artifacts/" + textArtifactId + "/sha256/" + textHash;

        ingestion.createSourceVersion(new IngestionRepository.NewSourceVersion(sourceVersionId, spaceA, sourceId, 1,
                IngestionRepository.ConnectorType.LOCAL_DIRECTORY, "Manifest fixture",
                IngestionRepository.SourceState.ACTIVE, "file:manifest", "[]", "[]", false, correlation, now));
        ingestion.createPipelineVersion(new IngestionRepository.NewPipelineVersion(pipelineId, spaceA, 1,
                "manifest", "native-fixture-parser", "1.0.0", "c".repeat(64), correlation, now));
        ingestion.createSourceDocument(new IngestionRepository.NewSourceDocument(documentId, spaceA, sourceId,
                "manifest-object", "manifest.md", "manifest.md", 1,
                IngestionRepository.DocumentState.ACTIVE, null, correlation, now));
        ingestion.persistRevisionBundle(new IngestionRepository.RevisionBundleInput(
                spaceA, documentId, revisionId, 1, "source-v1", "manifest.md", sourceHash,
                sourceArtifactId, 1, IngestionRepository.ArtifactKind.SOURCE_BYTES, "text/markdown", 16,
                sourceHash, "spaces/" + spaceA + "/sources/" + sourceId + "/revisions/" + revisionId
                        + "/artifacts/" + sourceArtifactId + "/sha256/" + sourceHash,
                "{}", reportId, 1, IngestionRepository.ParseStatus.SUCCEEDED, 1, 16, 4, 1, 0,
                "native-fixture-parser", "1.0.0", 1, "[]", "[]", sourceArtifactId,
                IngestionRepository.OcrStatus.NOT_REQUESTED, null, null,
                IngestionRepository.OcrTriggerReason.NONE, IngestionRepository.OcrAuditState.NOT_APPLICABLE,
                null, now, now));
        jdbc.update("""
                INSERT INTO artifacts (id, space_id, source_document_id, document_revision_id, version_no, artifact_kind,
                    media_type, byte_length, sha256, storage_uri, metadata, immutable, created_at)
                VALUES (?, ?, ?, ?, 1, 'PARSED_TEXT', 'text/plain', 12, ?, ?, '{}'::jsonb, TRUE, ?)
                """, textArtifactId, spaceA, documentId, revisionId, textHash, textRef, java.sql.Timestamp.from(now));

        IngestionRepository.NewArtifactManifest input = new IngestionRepository.NewArtifactManifest(
                UUID.randomUUID(), spaceA, revisionId, pipelineId, sourceArtifactId, textArtifactId, textHash,
                textRef, "native-fixture-parser", "1.0.0", "object-key.v1", now);
        IngestionRepository.ArtifactManifest first = ingestion.persistArtifactManifest(input);
        IngestionRepository.ArtifactManifest retry = ingestion.persistArtifactManifest(
                new IngestionRepository.NewArtifactManifest(UUID.randomUUID(), spaceA, revisionId, pipelineId,
                        sourceArtifactId, textArtifactId, textHash, textRef, "native-fixture-parser", "1.0.0",
                        "object-key.v1", now.plusSeconds(1)));
        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(ingestion.findArtifactManifest(spaceB, revisionId, pipelineId, "native-fixture-parser", "1.0.0"))
                .isEmpty();
        assertThatThrownBy(() -> ingestion.persistArtifactManifest(new IngestionRepository.NewArtifactManifest(
                UUID.randomUUID(), spaceA, revisionId, pipelineId, sourceArtifactId, textArtifactId,
                "d".repeat(64), textRef, "native-fixture-parser", "1.0.0", "object-key.v1", now)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different immutable input");
        assertThatThrownBy(() -> ingestion.persistArtifactManifest(new IngestionRepository.NewArtifactManifest(
                UUID.randomUUID(), spaceB, revisionId, pipelineId, sourceArtifactId, textArtifactId, textHash,
                textRef, "native-fixture-parser", "1.0.0", "object-key.v1", now)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requested space");
        assertThatThrownBy(() -> jdbc.update("UPDATE artifact_manifests SET object_ref = 'tampered' WHERE id = ?", first.id()))
                .isInstanceOf(DataAccessException.class);
    }

    private UUID createSpace(String name) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        jdbc.update("""
                INSERT INTO knowledge_spaces (id, name, description, status, created_at, updated_at, version)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)
                """, id, name, name, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        return id;
    }
}
