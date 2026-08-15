package com.ragforge.server.chunk;

import java.time.Instant;
import java.util.List;
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

import com.ragforge.server.index.IndexRepository;
import com.ragforge.server.index.IndexState;
import com.ragforge.server.index.IndexValidation;
import com.ragforge.server.retrieval.ExpansionMode;
import com.ragforge.server.retrieval.RetrievalProfileRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL proof for Phase 4 chunk/index/profile persistence boundaries. */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Phase4ChunkIndexPersistenceIntegrationTest {
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
    ChunkRepository chunks;

    @Autowired
    IndexRepository indexes;

    @Autowired
    RetrievalProfileRepository profiles;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE active_profile_pointers, retrieval_profiles, active_index_pointers, "
                + "index_versions, chunk_overrides, child_chunks, parent_chunks, ingestion_idempotency, "
                + "pipeline_step_executions, ingestion_job_attempts, ingestion_jobs, active_document_pointers, "
                + "parse_reports, document_revisions, artifacts, pipeline_versions, source_checkpoints, "
                + "source_documents, source_versions, sources, outbox_events, knowledge_spaces, users CASCADE");
    }

    private static final Instant NOW = Instant.parse("2026-08-15T08:00:00Z");
    private static final String SHA_1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_2 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void migrationIsVersionedAndPhase4TablesExist() {
        Set<String> tables = Set.copyOf(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN (
                    'parent_chunks', 'child_chunks', 'chunk_overrides', 'index_versions',
                    'active_index_pointers', 'retrieval_profiles', 'active_profile_pointers')
                """, String.class));
        assertThat(tables).containsExactlyInAnyOrder(
                "parent_chunks", "child_chunks", "chunk_overrides", "index_versions",
                "active_index_pointers", "retrieval_profiles", "active_profile_pointers");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE version = '9'", String.class))
                .isEqualTo("9");
    }

    @Test
    void chunksAreSpaceScopedImmutableAndRevisionBound() {
        UUID spaceA = createSpace("phase4-chunk-a");
        UUID spaceB = createSpace("phase4-chunk-b");
        UUID revisionA = createRevision(spaceA, "chunk-rev-a");
        UUID revisionB = createRevision(spaceB, "chunk-rev-b");

        UUID parentId = UUID.randomUUID();
        chunks.insertParents(List.of(new ChunkRepository.NewParentChunk(
                parentId, spaceA, revisionA, 0, 1, List.of("Chapter 2", "2.1 Setup"),
                0, 1200, 0, 8200, "s3://space-a/parent-0.txt", NOW)));
        UUID childId = UUID.randomUUID();
        chunks.insertChildren(List.of(new ChunkRepository.NewChildChunk(
                childId, spaceA, parentId, revisionA, 2, 1, List.of("Chapter 2", "2.1 Setup"),
                800, 1200, 5400, 8200, 7, null, null, 12, 30, null,
                "s3://space-a/child-2.txt", SHA_1, NOW)));

        assertThat(chunks.listParents(spaceA, revisionA)).hasSize(1);
        assertThat(chunks.listChildren(spaceA, revisionA)).hasSize(1);
        assertThat(chunks.listParents(spaceB, revisionB)).isEmpty();
        assertThat(chunks.listChildren(spaceB, revisionA)).isEmpty();
        assertThat(chunks.listChildren(spaceA, revisionB)).isEmpty();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE child_chunks SET text_hash = ? WHERE space_id = ? AND id = ?", SHA_2, spaceA, childId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void chunkOverridesFollowTheStateMachineAndNeverSilentlyReapply() {
        UUID space = createSpace("phase4-override");
        UUID revision = createRevision(space, "override-rev");
        UUID parentId = UUID.randomUUID();
        chunks.insertParents(List.of(new ChunkRepository.NewParentChunk(
                parentId, space, revision, 0, 1, List.of("2.1 Setup"), 0, 1200, 0, 8200,
                "s3://space/override/parent-0.txt", NOW)));
        UUID childId = UUID.randomUUID();
        chunks.insertChildren(List.of(new ChunkRepository.NewChildChunk(
                childId, space, parentId, revision, 0, 1, List.of("2.1 Setup"),
                0, 400, 0, 2600, 7, null, null, null, null, null,
                "s3://space/override/child-0.txt", SHA_1, NOW)));

        ChunkRepository.ChunkOverride active = chunks.createOverride(
                new ChunkRepository.NewChunkOverride(UUID.randomUUID(), space, childId, revision,
                        "人工修订标题", SHA_2, UUID.randomUUID(), NOW));
        assertThat(active.state()).isEqualTo(OverrideState.ACTIVE);

        ChunkRepository.ChunkOverride review = chunks.updateOverrideState(
                space, active.id(), OverrideState.NEEDS_REVIEW, NOW.plusSeconds(60));
        assertThat(review.state()).isEqualTo(OverrideState.NEEDS_REVIEW);
        assertThat(review.versionNo()).isEqualTo(2);

        ChunkRepository.ChunkOverride resolved = chunks.updateOverrideState(
                space, review.id(), OverrideState.ACTIVE, NOW.plusSeconds(120));
        assertThat(resolved.state()).isEqualTo(OverrideState.ACTIVE);
        assertThat(resolved.versionNo()).isEqualTo(3);

        // ACTIVE -> DISCARDED directly is forbidden; the machine requires NEEDS_REVIEW first.
        assertThatThrownBy(() -> chunks.updateOverrideState(
                space, resolved.id(), OverrideState.DISCARDED, NOW.plusSeconds(150)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
        ChunkRepository.ChunkOverride reviewAgain = chunks.updateOverrideState(
                space, resolved.id(), OverrideState.NEEDS_REVIEW, NOW.plusSeconds(180));
        assertThat(reviewAgain.state()).isEqualTo(OverrideState.NEEDS_REVIEW);
        ChunkRepository.ChunkOverride discarded = chunks.updateOverrideState(
                space, reviewAgain.id(), OverrideState.DISCARDED, NOW.plusSeconds(200));
        assertThat(discarded.state()).isEqualTo(OverrideState.DISCARDED);
        assertThatThrownBy(() -> chunks.updateOverrideState(
                space, discarded.id(), OverrideState.ACTIVE, NOW.plusSeconds(240)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");

        UUID newerRevision = createRevision(space, "override-rev-2");
        ChunkRepository.ChunkOverride forced = chunks.createOverride(
                new ChunkRepository.NewChunkOverride(UUID.randomUUID(), space, childId, newerRevision,
                        "源文本更新，需复核", SHA_2, UUID.randomUUID(), NOW));
        assertThat(forced.state()).isEqualTo(OverrideState.NEEDS_REVIEW);
        assertThat(forced.versionNo()).isGreaterThan(discarded.versionNo());
    }

    @Test
    void indexLifecycleValidatesThenPublishesAtomicallyWithRetention() {
        UUID space = createSpace("phase4-index");
        UUID versionId = UUID.randomUUID();
        indexes.createVersion(new IndexRepository.NewIndexVersion(
                versionId, space, 1, "ragforge_v1_" + space, "local-embedding-v3", "p4-default-v1",
                12, 240, NOW));

        assertThatThrownBy(() -> indexes.transitionState(space, versionId, IndexState.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
        indexes.transitionState(space, versionId, IndexState.VALIDATING);
        assertThatThrownBy(() -> indexes.transitionState(space, versionId, IndexState.BUILDING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");

        IndexValidation passed = new IndexValidation(12, 240, 768, 0, true, true, NOW.plusSeconds(60));
        indexes.recordValidation(space, versionId, passed);
        indexes.transitionState(space, versionId, IndexState.READY);

        IndexRepository.ActiveIndexPointer pointer = indexes.activate(space, versionId, NOW.plusSeconds(120));
        assertThat(pointer.activeIndexVersionId()).isEqualTo(versionId);
        assertThat(pointer.previousIndexVersionId()).isNull();
        IndexRepository.IndexVersion active = indexes.findVersion(space, versionId).orElseThrow();
        assertThat(active.state()).isEqualTo(IndexState.ACTIVE);
        assertThat(active.activatedAt()).isNotNull();
        assertThat(active.retentionDeadline()).isAfterOrEqualTo(active.activatedAt().plusSeconds(24 * 3600));

        UUID secondId = UUID.randomUUID();
        indexes.createVersion(new IndexRepository.NewIndexVersion(
                secondId, space, 2, "ragforge_v2_" + space, "local-embedding-v3", "p4-default-v1",
                13, 260, NOW.plusSeconds(200)));
        indexes.transitionState(space, secondId, IndexState.VALIDATING);
        indexes.recordValidation(space, secondId, new IndexValidation(13, 260, 768, 0, true, true, NOW.plusSeconds(260)));
        indexes.transitionState(space, secondId, IndexState.READY);
        IndexRepository.ActiveIndexPointer second = indexes.activate(space, secondId, NOW.plusSeconds(300));
        assertThat(second.activeIndexVersionId()).isEqualTo(secondId);
        assertThat(second.previousIndexVersionId()).isEqualTo(versionId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM active_index_pointers WHERE space_id = ?", Integer.class, space)).isEqualTo(1);

        indexes.retire(space, versionId, NOW.plusSeconds(400));
        assertThat(indexes.findVersion(space, versionId).orElseThrow().state()).isEqualTo(IndexState.RETIRED);
    }

    @Test
    void indexCannotBecomeActiveWithoutPassedValidation() {
        UUID space = createSpace("phase4-index-invalid");
        UUID versionId = UUID.randomUUID();
        indexes.createVersion(new IndexRepository.NewIndexVersion(
                versionId, space, 1, "ragforge_bad_" + space, "local-embedding-v3", "p4-default-v1", 1, 4, NOW));
        indexes.transitionState(space, versionId, IndexState.VALIDATING);
        indexes.recordValidation(space, versionId, new IndexValidation(1, 4, 768, 1, false, true, NOW));
        indexes.transitionState(space, versionId, IndexState.READY);
        assertThatThrownBy(() -> indexes.activate(space, versionId, NOW.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sample retrieval");
        // The SQL CHECK backstop also rejects a direct ACTIVE write without passed validation.
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE index_versions SET index_state = 'ACTIVE', activated_at = ?
                WHERE space_id = ? AND id = ?
                """, java.sql.Timestamp.from(NOW.plusSeconds(90)), space, versionId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void retrievalProfilesAreImmutableVersionedAndSinglePointerPerSpace() {
        UUID space = createSpace("phase4-profile");
        UUID profileId = UUID.randomUUID();
        RetrievalProfileRepository.RetrievalProfileVersion v1 = profiles.createVersion(
                new RetrievalProfileRepository.NewRetrievalProfileVersion(
                        UUID.randomUUID(), space, profileId, 1, 30, 30, 60, 1.0, 1.0, 20, 8,
                        ExpansionMode.PARENT_AND_NEIGHBOR, 1, 2, 4000, NOW));
        assertThat(v1.versionNo()).isEqualTo(1);
        assertThat(profiles.findVersion(space, profileId, 1)).isPresent();

        RetrievalProfileRepository.RetrievalProfileVersion v2 = profiles.createVersion(
                new RetrievalProfileRepository.NewRetrievalProfileVersion(
                        UUID.randomUUID(), space, profileId, 2, 40, 25, 60, 0.9, 1.0, 15, 6,
                        ExpansionMode.PARENT, 1, 1, 3000, NOW.plusSeconds(60)));
        assertThat(profiles.findLatestVersion(space, profileId).orElseThrow().versionNo()).isEqualTo(2);

        RetrievalProfileRepository.ActiveProfilePointer pointer = profiles.activateProfile(space, profileId, 2, NOW.plusSeconds(90));
        assertThat(pointer.activeVersionNo()).isEqualTo(2);
        UUID expectedVersionRowId = jdbc.queryForObject(
                "SELECT id FROM retrieval_profiles WHERE space_id = ? AND profile_id = ? AND version_no = 2",
                UUID.class, space, profileId);
        assertThat(pointer.activeProfileVersionId()).isEqualTo(expectedVersionRowId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM active_profile_pointers WHERE space_id = ?", Integer.class, space)).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO retrieval_profiles
                    (id, space_id, profile_id, version_no, dense_top_k, bm25_top_k, rrf_k,
                     rrf_dense_weight, rrf_bm25_weight, rerank_top_k, max_context_children,
                     expansion_mode, max_parents_per_child, max_neighbors_per_parent,
                     max_context_tokens, immutable, created_at)
                VALUES (?, ?, ?, 3, 30, 30, 60, 1.0, 1.0, 20, 8, 'PARENT', 1, 2, 4000, FALSE, ?)
                """, UUID.randomUUID(), space, profileId, java.sql.Timestamp.from(NOW)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> profiles.createVersion(
                new RetrievalProfileRepository.NewRetrievalProfileVersion(
                        UUID.randomUUID(), space, profileId, 3, 0, 30, 60, 1.0, 1.0, 20, 8,
                        ExpansionMode.PARENT, 1, 2, 4000, NOW.plusSeconds(120))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private UUID createRevision(UUID spaceId, String path) {
        UUID sourceId = UUID.randomUUID();
        UUID sourceVersionId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sources (id, space_id, created_at) VALUES (?, ?, ?)
                """, sourceId, spaceId, java.sql.Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO source_versions
                    (id, space_id, source_id, version_no, connector_type, display_name, source_state,
                     read_only, root_ref, include_rules, exclude_rules, credential_configured,
                     correlation_id, created_at, updated_at)
                VALUES (?, ?, ?, 1, 'LOCAL_DIRECTORY', 'synthetic', 'ACTIVE', TRUE, 'file:synthetic',
                        '[]'::jsonb, '[]'::jsonb, FALSE, ?, ?, ?)
                """, sourceVersionId, spaceId, sourceId, UUID.randomUUID(),
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO source_documents
                    (id, space_id, source_id, stable_source_object_id, canonical_source_path, basename,
                     version_no, current_state, correlation_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, 'ACTIVE', ?, ?, ?)
                """, documentId, spaceId, sourceId, "stable-" + path, path + ".md", path + ".md",
                UUID.randomUUID(), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO document_revisions
                    (id, space_id, source_document_id, revision_no, source_version, canonical_source_path,
                     content_hash, revision_state, immutable, discovered_at, created_at)
                VALUES (?, ?, ?, 1, 'source-v1', ?, ?, 'PARSED', TRUE, ?, ?)
                """, revisionId, spaceId, documentId, path + ".md",
                SHA_1, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        return revisionId;
    }

    private UUID createSpace(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge_spaces (id, name, description, status, created_at, updated_at, version)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)
                """, id, name, name, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        return id;
    }
}
