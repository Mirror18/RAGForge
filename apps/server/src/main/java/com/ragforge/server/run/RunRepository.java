package com.ragforge.server.run;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for Run, Step, Model Invocation and Usage Ledger records. */
@Repository
public class RunRepository {
    private final JdbcTemplate jdbc;

    public RunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public RunRecord createRun(NewRun input) {
        jdbc.update("""
                        INSERT INTO runs
                            (id, space_id, conversation_id, actor_user_id, correlation_id, request_kind, status,
                             model_route_version_id, prompt_version_id, input_hash, output_hash,
                             error_class, error_code, started_at, completed_at, created_at, updated_at, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                        """, input.id(), input.spaceId(), input.conversationId(), input.actorUserId(), input.correlationId(),
                input.requestKind().name(), input.status().name(), input.routeVersionId(), input.promptVersionId(),
                input.inputHash(), input.outputHash(), nullableError(input.errorClass()), input.errorCode(),
                timestamp(input.startedAt()), timestamp(input.completedAt()), timestamp(input.now()),
                timestamp(input.now()));
        return findRun(input.spaceId(), input.id()).orElseThrow();
    }

    public Optional<RunRecord> findRun(UUID spaceId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, space_id, conversation_id, actor_user_id, correlation_id, request_kind, status,
                                   model_route_version_id, prompt_version_id, input_hash, output_hash,
                                   error_class, error_code, started_at, completed_at, created_at, updated_at, version
                            FROM runs WHERE id = ? AND space_id = ?
                            """, (rs, rowNum) -> mapRun(rs), id, spaceId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Transactional
    public RunRecord transitionRun(UUID spaceId, UUID runId, RunStatus nextStatus, ErrorClass errorClass,
                                   String errorCode, Instant now, long expectedVersion) {
        return transitionRun(spaceId, runId, nextStatus, errorClass, errorCode, now, expectedVersion, null);
    }

    @Transactional
    public RunRecord transitionRun(UUID spaceId, UUID runId, RunStatus nextStatus, ErrorClass errorClass,
                                   String errorCode, Instant now, long expectedVersion, String outputHash) {
        RunRecord current = findRun(spaceId, runId).orElseThrow();
        assertTransition(current.status(), nextStatus);
        int updated = jdbc.update("""
                        UPDATE runs
                        SET status = ?, error_class = ?, error_code = ?, output_hash = COALESCE(?, output_hash),
                            started_at = CASE WHEN ? = 'RUNNING' AND started_at IS NULL THEN ? ELSE started_at END,
                            completed_at = CASE WHEN ? IN ('SUCCEEDED', 'FAILED', 'CANCELLED') THEN ? ELSE completed_at END,
                            updated_at = ?, version = version + 1
                        WHERE id = ? AND space_id = ? AND version = ?
                        """, nextStatus.name(), nullableError(errorClass), errorCode, outputHash, nextStatus.name(), timestamp(now),
                nextStatus.name(), timestamp(now), timestamp(now), runId, spaceId, expectedVersion);
        if (updated != 1) {
            throw new IllegalStateException("Run version changed while transitioning");
        }
        return findRun(spaceId, runId).orElseThrow();
    }

    @Transactional
    public StepRecord createStep(NewStep input) {
        jdbc.update("""
                        INSERT INTO run_steps
                            (id, space_id, run_id, step_key, step_type, attempt, sequence_no,
                             status, error_class, error_code, created_at, updated_at, correlation_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, input.id(), input.spaceId(), input.runId(), input.stepKey(), input.stepType().name(),
                input.attempt(), input.sequenceNo(), input.status().name(), nullableError(input.errorClass()),
                input.errorCode(), timestamp(input.now()), timestamp(input.now()), input.correlationId());
        return findStep(input.spaceId(), input.id()).orElseThrow();
    }

    public Optional<StepRecord> findStep(UUID spaceId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, space_id, run_id, step_key, step_type, attempt, sequence_no,
                                   status, error_class, error_code, created_at, updated_at, correlation_id
                            FROM run_steps WHERE id = ? AND space_id = ?
                            """, (rs, rowNum) -> mapStep(rs), id, spaceId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<StepRecord> findSteps(UUID spaceId, UUID runId) {
        return jdbc.query("""
                        SELECT id, space_id, run_id, step_key, step_type, attempt, sequence_no,
                               status, error_class, error_code, created_at, updated_at, correlation_id
                        FROM run_steps WHERE run_id = ? AND space_id = ?
                        ORDER BY sequence_no, attempt, created_at, id
                        """, (rs, rowNum) -> mapStep(rs), runId, spaceId);
    }

    @Transactional
    public StepRecord updateStep(UUID spaceId, UUID stepId, RunStatus status, ErrorClass errorClass,
                                 String errorCode, Instant now) {
        int updated = jdbc.update("""
                        UPDATE run_steps SET status = ?, error_class = ?, error_code = ?, updated_at = ?
                        WHERE id = ? AND space_id = ?
                        """, status.name(), nullableError(errorClass), errorCode, timestamp(now), stepId, spaceId);
        if (updated != 1) {
            throw new IllegalStateException("Run step is not available in the requested space");
        }
        return findStep(spaceId, stepId).orElseThrow();
    }

    @Transactional
    public ModelInvocationRecord createInvocation(NewModelInvocation input) {
        jdbc.update("""
                        INSERT INTO model_invocations
                            (id, space_id, run_id, step_id, provider_connection_id, model_profile_version_id,
                             model_route_version_id, prompt_version_id, provider_request_identity,
                             prompt_render_hash, request_metadata, response_hash, status, error_class,
                             error_code, created_at, updated_at, correlation_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?)
                        """, input.id(), input.spaceId(), input.runId(), input.stepId(), input.providerConnectionId(),
                input.profileVersionId(), input.routeVersionId(), input.promptVersionId(),
                input.providerRequestIdentity(), input.promptRenderHash(), jsonOrEmpty(input.requestMetadataJson()),
                input.responseHash(), input.status().name(), nullableError(input.errorClass()), input.errorCode(),
                timestamp(input.now()), timestamp(input.now()), input.correlationId());
        return findInvocation(input.spaceId(), input.id()).orElseThrow();
    }

    public Optional<ModelInvocationRecord> findInvocation(UUID spaceId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, space_id, run_id, step_id, provider_connection_id,
                                   model_profile_version_id, model_route_version_id, prompt_version_id,
                                   provider_request_identity, prompt_render_hash, request_metadata,
                                   response_hash, status, error_class, error_code, created_at, updated_at,
                                   correlation_id
                            FROM model_invocations WHERE id = ? AND space_id = ?
                            """, (rs, rowNum) -> mapInvocation(rs), id, spaceId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<ModelInvocationRecord> findInvocations(UUID spaceId, UUID runId) {
        return jdbc.query("""
                        SELECT id, space_id, run_id, step_id, provider_connection_id,
                               model_profile_version_id, model_route_version_id, prompt_version_id,
                               provider_request_identity, prompt_render_hash, request_metadata,
                               response_hash, status, error_class, error_code, created_at, updated_at,
                               correlation_id
                        FROM model_invocations WHERE run_id = ? AND space_id = ?
                        ORDER BY created_at, id
                        """, (rs, rowNum) -> mapInvocation(rs), runId, spaceId);
    }

    /**
     * Records provider-reported and locally estimated usage independently. Repeating the
     * same invocation/source/dedupe key updates the existing ledger row and never creates
     * a second charge; provider request identity remains audit metadata.
     */
    @Transactional
    public UsageLedgerRecord recordUsage(NewUsageLedgerEntry input) {
        jdbc.update("""
                        INSERT INTO usage_ledger
                            (id, space_id, model_invocation_id, provider_request_identity, usage_source,
                             dedupe_key, input_tokens, output_tokens, total_tokens, estimated_cost, currency, metadata,
                             created_at, updated_at, correlation_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                        ON CONFLICT (space_id, model_invocation_id, usage_source, dedupe_key) DO UPDATE
                        SET model_invocation_id = EXCLUDED.model_invocation_id,
                            input_tokens = EXCLUDED.input_tokens,
                            output_tokens = EXCLUDED.output_tokens,
                            total_tokens = EXCLUDED.total_tokens,
                            estimated_cost = EXCLUDED.estimated_cost,
                            currency = EXCLUDED.currency,
                            metadata = EXCLUDED.metadata,
                            updated_at = EXCLUDED.updated_at,
                            correlation_id = EXCLUDED.correlation_id
                        """, input.id(), input.spaceId(), input.invocationId(), input.providerRequestIdentity(),
                input.source().name(), input.dedupeKey(), input.inputTokens(), input.outputTokens(),
                input.totalTokens(), input.estimatedCost(), input.currency(), jsonOrEmpty(input.metadataJson()),
                timestamp(input.now()), timestamp(input.now()), input.correlationId());
        return findUsage(input.spaceId(), input.invocationId(), input.source(), input.dedupeKey()).orElseThrow();
    }

    public Optional<UsageLedgerRecord> findUsage(UUID spaceId, UUID invocationId, UsageSource source,
                                                 String dedupeKey) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, space_id, model_invocation_id, provider_request_identity, usage_source,
                                   dedupe_key, input_tokens, output_tokens, total_tokens, estimated_cost, currency, metadata,
                                   created_at, updated_at, correlation_id
                            FROM usage_ledger
                            WHERE space_id = ? AND model_invocation_id = ? AND usage_source = ? AND dedupe_key = ?
                            """, (rs, rowNum) -> mapUsage(rs), spaceId, invocationId, source.name(), dedupeKey));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /** Returns redacted usage rows for one explicitly space-scoped run. */
    public List<UsageLedgerRecord> findUsageByRun(UUID spaceId, UUID runId) {
        return jdbc.query("""
                        SELECT u.id, u.space_id, u.model_invocation_id, u.provider_request_identity,
                               u.usage_source, u.dedupe_key, u.input_tokens, u.output_tokens, u.total_tokens,
                               u.estimated_cost, u.currency, u.metadata, u.created_at, u.updated_at,
                               u.correlation_id
                        FROM usage_ledger u
                        JOIN model_invocations i
                          ON i.id = u.model_invocation_id AND i.space_id = u.space_id
                        WHERE u.space_id = ? AND i.run_id = ? AND i.space_id = ?
                          AND i.provider_request_identity LIKE 'rag-%'
                        ORDER BY u.created_at, u.id
                        """, (rs, rowNum) -> mapUsage(rs), spaceId, runId, spaceId);
    }

    /** Stores the immutable RAG run projection without changing the no-RAG run row. */
    @Transactional
    public RagRunProvenance createRagRunProvenance(NewRagRunProvenance input) {
        jdbc.update(ragProvenanceInsert("rag_run_provenance"),
                input.id(), input.spaceId(), input.runId(), input.ragPromptVersionId(), input.promptHash(),
                input.indexVersionId(), input.retrievalProfileId(), input.retrievalProfileVersion(),
                input.modelRouteVersionId(), input.modelProfileVersionId(), input.evidenceBundleVersion(),
                input.evidenceBundleHash(), input.evidenceBundleRef(), jsonOrEmpty(input.toolSchemaVersionsJson()),
                input.datasetHash(), input.configHash(), input.traceId(), input.correlationId(), timestamp(input.createdAt()));
        return findRagRunProvenance(input.spaceId(), input.runId()).orElseThrow();
    }

    /** Stores one immutable RAG step projection with an explicit space-scoped step FK. */
    @Transactional
    public RagStepProvenance createRagStepProvenance(NewRagStepProvenance input) {
        jdbc.update(ragProvenanceInsert("rag_step_provenance"),
                input.id(), input.spaceId(), input.runId(), input.stepId(), input.ragPromptVersionId(), input.promptHash(),
                input.indexVersionId(), input.retrievalProfileId(), input.retrievalProfileVersion(),
                input.modelRouteVersionId(), input.modelProfileVersionId(), input.evidenceBundleVersion(),
                input.evidenceBundleHash(), input.evidenceBundleRef(), jsonOrEmpty(input.toolSchemaVersionsJson()),
                input.datasetHash(), input.configHash(), input.traceId(), input.correlationId(), timestamp(input.createdAt()));
        return findRagStepProvenance(input.spaceId(), input.stepId()).orElseThrow();
    }

    /** Stores one immutable RAG model-invocation projection with no request/response body. */
    @Transactional
    public RagModelInvocationProvenance createRagModelInvocationProvenance(NewRagModelInvocationProvenance input) {
        jdbc.update(ragProvenanceInsert("rag_model_invocation_provenance"),
                input.id(), input.spaceId(), input.runId(), input.stepId(), input.modelInvocationId(),
                input.ragPromptVersionId(), input.promptHash(), input.indexVersionId(), input.retrievalProfileId(),
                input.retrievalProfileVersion(), input.modelRouteVersionId(), input.modelProfileVersionId(),
                input.evidenceBundleVersion(), input.evidenceBundleHash(), input.evidenceBundleRef(),
                jsonOrEmpty(input.toolSchemaVersionsJson()), input.datasetHash(), input.configHash(), input.traceId(),
                input.correlationId(), timestamp(input.createdAt()));
        return findRagModelInvocationProvenance(input.spaceId(), input.modelInvocationId()).orElseThrow();
    }

    public Optional<RagRunProvenance> findRagRunProvenance(UUID spaceId, UUID runId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(ragSelect("rag_run_provenance", "run_id = ?"),
                    (rs, rowNum) -> mapRagRunProvenance(rs), spaceId, runId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<RagStepProvenance> findRagStepProvenance(UUID spaceId, UUID stepId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(ragSelect("rag_step_provenance", "step_id = ?"),
                    (rs, rowNum) -> mapRagStepProvenance(rs), spaceId, stepId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<RagModelInvocationProvenance> findRagModelInvocationProvenance(UUID spaceId,
                                                                                    UUID modelInvocationId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    ragSelect("rag_model_invocation_provenance", "model_invocation_id = ?"),
                    (rs, rowNum) -> mapRagModelInvocationProvenance(rs), spaceId, modelInvocationId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Returns the redacted replay projection only when the requested run and
     * every child query are in the same space.  A foreign-space lookup is
     * indistinguishable from a missing projection.
     */
    public Optional<RagReplayProjection> findRagReplayProjection(UUID spaceId, UUID runId) {
        Optional<RagRunProvenance> run = findRagRunProvenance(spaceId, runId);
        if (run.isEmpty()) {
            return Optional.empty();
        }
        List<RagStepProvenance> steps = jdbc.query(
                ragSelect("rag_step_provenance", "run_id = ? ORDER BY created_at, id"),
                (rs, rowNum) -> mapRagStepProvenance(rs), spaceId, runId);
        List<RagModelInvocationProvenance> invocations = jdbc.query(
                ragSelect("rag_model_invocation_provenance", "run_id = ? ORDER BY created_at, id"),
                (rs, rowNum) -> mapRagModelInvocationProvenance(rs), spaceId, runId);
        return Optional.of(new RagReplayProjection(run.get(), steps, invocations));
    }

    private String ragProvenanceInsert(String table) {
        String columns = "id, space_id, run_id, "
                + ("rag_step_provenance".equals(table) ? "step_id, " : "")
                + ("rag_model_invocation_provenance".equals(table) ? "step_id, model_invocation_id, " : "")
                + "rag_prompt_version_id, prompt_hash, index_version_id, retrieval_profile_id, retrieval_profile_version, "
                + "model_route_version_id, model_profile_version_id, evidence_bundle_version, evidence_bundle_hash, "
                + "evidence_bundle_ref, tool_schema_versions, dataset_hash, config_hash, trace_id, correlation_id, created_at";
        return "INSERT INTO " + table + " (" + columns + ") VALUES ("
                + "?, ?, ?, "
                + ("rag_run_provenance".equals(table) ? "" : ("rag_step_provenance".equals(table) ? "?, " : "?, ?, "))
                + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)";
    }

    private String ragSelect(String table, String predicate) {
        return "SELECT id, space_id, run_id, "
                + ("rag_step_provenance".equals(table) ? "step_id, " : "")
                + ("rag_model_invocation_provenance".equals(table) ? "step_id, model_invocation_id, " : "")
                + "rag_prompt_version_id, prompt_hash, index_version_id, retrieval_profile_id, retrieval_profile_version, "
                + "model_route_version_id, model_profile_version_id, evidence_bundle_version, evidence_bundle_hash, "
                + "evidence_bundle_ref, tool_schema_versions, dataset_hash, config_hash, trace_id, correlation_id, created_at "
                + "FROM " + table + " WHERE space_id = ? AND " + predicate;
    }

    private RagRunProvenance mapRagRunProvenance(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RagRunProvenance(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("run_id", UUID.class), commonRagValues(rs));
    }

    private RagStepProvenance mapRagStepProvenance(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RagStepProvenance(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getObject("step_id", UUID.class), commonRagValues(rs));
    }

    private RagModelInvocationProvenance mapRagModelInvocationProvenance(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new RagModelInvocationProvenance(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getObject("step_id", UUID.class),
                rs.getObject("model_invocation_id", UUID.class), commonRagValues(rs));
    }

    private RagValues commonRagValues(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RagValues(rs.getObject("rag_prompt_version_id", UUID.class), rs.getString("prompt_hash"),
                rs.getObject("index_version_id", UUID.class), rs.getObject("retrieval_profile_id", UUID.class),
                rs.getInt("retrieval_profile_version"), rs.getObject("model_route_version_id", UUID.class),
                rs.getObject("model_profile_version_id", UUID.class), rs.getInt("evidence_bundle_version"),
                rs.getString("evidence_bundle_hash"), rs.getString("evidence_bundle_ref"),
                rs.getString("tool_schema_versions"), rs.getString("dataset_hash"), rs.getString("config_hash"),
                rs.getObject("trace_id", UUID.class), rs.getObject("correlation_id", UUID.class),
                instant(rs, "created_at"));
    }

    private RunRecord mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RunRecord(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getObject("actor_user_id", UUID.class),
                rs.getObject("correlation_id", UUID.class),
                RequestKind.valueOf(rs.getString("request_kind")), RunStatus.valueOf(rs.getString("status")),
                rs.getObject("model_route_version_id", UUID.class), rs.getObject("prompt_version_id", UUID.class),
                rs.getString("input_hash"), rs.getString("output_hash"), readError(rs.getString("error_class")),
                rs.getString("error_code"), nullableInstant(rs, "started_at"), nullableInstant(rs, "completed_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }

    private StepRecord mapStep(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StepRecord(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getString("step_key"),
                StepType.valueOf(rs.getString("step_type")), rs.getInt("attempt"), rs.getInt("sequence_no"),
                RunStatus.valueOf(rs.getString("status")), readError(rs.getString("error_class")),
                rs.getString("error_code"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getObject("correlation_id", UUID.class));
    }

    private ModelInvocationRecord mapInvocation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ModelInvocationRecord(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getObject("step_id", UUID.class),
                rs.getObject("provider_connection_id", UUID.class),
                rs.getObject("model_profile_version_id", UUID.class),
                rs.getObject("model_route_version_id", UUID.class), rs.getObject("prompt_version_id", UUID.class),
                rs.getString("provider_request_identity"), rs.getString("prompt_render_hash"),
                rs.getString("request_metadata"), rs.getString("response_hash"),
                InvocationStatus.valueOf(rs.getString("status")), readError(rs.getString("error_class")),
                rs.getString("error_code"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getObject("correlation_id", UUID.class));
    }

    private UsageLedgerRecord mapUsage(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UsageLedgerRecord(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("model_invocation_id", UUID.class), rs.getString("provider_request_identity"),
                UsageSource.valueOf(rs.getString("usage_source")), rs.getString("dedupe_key"),
                (Long) rs.getObject("input_tokens"),
                (Long) rs.getObject("output_tokens"), (Long) rs.getObject("total_tokens"),
                rs.getBigDecimal("estimated_cost"), rs.getString("currency"), rs.getString("metadata"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getObject("correlation_id", UUID.class));
    }

    private static void assertTransition(RunStatus current, RunStatus next) {
        if (current == next || current == RunStatus.SUCCEEDED || current == RunStatus.FAILED
                || current == RunStatus.CANCELLED) {
            throw new IllegalStateException("Run status transition is not allowed: " + current + " -> " + next);
        }
        if (current == RunStatus.QUEUED && next != RunStatus.RUNNING && next != RunStatus.CANCELLED) {
            throw new IllegalStateException("Queued run can only start or cancel");
        }
        if (current == RunStatus.RUNNING && next == RunStatus.QUEUED) {
            throw new IllegalStateException("Running run cannot return to queued");
        }
    }

    private static String nullableError(ErrorClass value) {
        return value == null ? null : value.name();
    }

    private static ErrorClass readError(String value) {
        return value == null ? null : ErrorClass.valueOf(value);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String jsonOrEmpty(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    public enum RequestKind { CHAT, EMBEDDING, RERANK }

    public enum RunStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    public enum StepType { REWRITE, RETRIEVE, RERANK, TOOL, GENERATE }

    public enum InvocationStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    public enum UsageSource { PROVIDER_REPORTED, LOCAL_ESTIMATE }

    public enum ErrorClass {
        AUTHENTICATION, RATE_LIMIT, QUOTA, MODEL_NOT_FOUND, CONTEXT_OVERFLOW,
        CONTENT_POLICY, TIMEOUT, UNAVAILABLE, UNSUPPORTED_CAPABILITY, INVALID_RESPONSE,
        CANCELLED, SPACE_EGRESS_DENIED, IDEMPOTENCY_CONFLICT
    }

    public record NewRun(UUID id, UUID spaceId, UUID conversationId, UUID actorUserId, UUID correlationId, RequestKind requestKind,
                          RunStatus status, UUID routeVersionId, UUID promptVersionId, String inputHash,
                          String outputHash, ErrorClass errorClass, String errorCode, Instant startedAt,
                          Instant completedAt, Instant now) {
        public NewRun(UUID id, UUID spaceId, UUID actorUserId, UUID correlationId, RequestKind requestKind,
                      RunStatus status, UUID routeVersionId, UUID promptVersionId, String inputHash,
                      String outputHash, ErrorClass errorClass, String errorCode, Instant startedAt,
                      Instant completedAt, Instant now) {
            this(id, spaceId, null, actorUserId, correlationId, requestKind, status, routeVersionId,
                    promptVersionId, inputHash, outputHash, errorClass, errorCode, startedAt, completedAt, now);
        }
    }

    public record RunRecord(UUID id, UUID spaceId, UUID conversationId, UUID actorUserId, UUID correlationId, RequestKind requestKind,
                            RunStatus status, UUID routeVersionId, UUID promptVersionId, String inputHash,
                            String outputHash, ErrorClass errorClass, String errorCode, Instant startedAt,
                            Instant completedAt, Instant createdAt, Instant updatedAt, long version) {
        public RunRecord(UUID id, UUID spaceId, UUID actorUserId, UUID correlationId, RequestKind requestKind,
                         RunStatus status, UUID routeVersionId, UUID promptVersionId, String inputHash,
                         String outputHash, ErrorClass errorClass, String errorCode, Instant startedAt,
                         Instant completedAt, Instant createdAt, Instant updatedAt, long version) {
            this(id, spaceId, null, actorUserId, correlationId, requestKind, status, routeVersionId,
                    promptVersionId, inputHash, outputHash, errorClass, errorCode, startedAt, completedAt,
                    createdAt, updatedAt, version);
        }
    }

    public record NewStep(UUID id, UUID spaceId, UUID runId, String stepKey, StepType stepType, int attempt,
                          int sequenceNo, RunStatus status, ErrorClass errorClass, String errorCode, Instant now,
                          UUID correlationId) {
    }

    public record StepRecord(UUID id, UUID spaceId, UUID runId, String stepKey, StepType stepType, int attempt,
                             int sequenceNo, RunStatus status, ErrorClass errorClass, String errorCode,
                             Instant createdAt, Instant updatedAt, UUID correlationId) {
    }

    public record NewModelInvocation(UUID id, UUID spaceId, UUID runId, UUID stepId, UUID providerConnectionId,
                                     UUID profileVersionId, UUID routeVersionId, UUID promptVersionId,
                                     String providerRequestIdentity, String promptRenderHash,
                                     String requestMetadataJson, String responseHash, InvocationStatus status,
                                     ErrorClass errorClass, String errorCode, Instant now, UUID correlationId) {
    }

    public record ModelInvocationRecord(UUID id, UUID spaceId, UUID runId, UUID stepId,
                                        UUID providerConnectionId, UUID profileVersionId, UUID routeVersionId,
                                        UUID promptVersionId, String providerRequestIdentity,
                                        String promptRenderHash, String requestMetadataJson, String responseHash,
                                        InvocationStatus status, ErrorClass errorClass, String errorCode,
                                        Instant createdAt, Instant updatedAt, UUID correlationId) {
    }

    public record NewUsageLedgerEntry(UUID id, UUID spaceId, UUID invocationId, String providerRequestIdentity,
                                      UsageSource source, String dedupeKey, Long inputTokens, Long outputTokens, Long totalTokens,
                                      java.math.BigDecimal estimatedCost, String currency, String metadataJson,
                                      Instant now, UUID correlationId) {
    }

    public record UsageLedgerRecord(UUID id, UUID spaceId, UUID invocationId, String providerRequestIdentity,
                                    UsageSource source, String dedupeKey, Long inputTokens, Long outputTokens, Long totalTokens,
                                    java.math.BigDecimal estimatedCost, String currency, String metadataJson,
                                    Instant createdAt, Instant updatedAt, UUID correlationId) {
    }

    public record NewRagRunProvenance(UUID id, UUID spaceId, UUID runId, UUID ragPromptVersionId,
                                      String promptHash, UUID indexVersionId, UUID retrievalProfileId,
                                      int retrievalProfileVersion, UUID modelRouteVersionId,
                                      UUID modelProfileVersionId, int evidenceBundleVersion,
                                      String evidenceBundleHash, String evidenceBundleRef,
                                      String toolSchemaVersionsJson, String datasetHash, String configHash,
                                      UUID traceId, UUID correlationId, Instant createdAt) {
    }

    public record NewRagStepProvenance(UUID id, UUID spaceId, UUID runId, UUID stepId, UUID ragPromptVersionId,
                                       String promptHash, UUID indexVersionId, UUID retrievalProfileId,
                                       int retrievalProfileVersion, UUID modelRouteVersionId,
                                       UUID modelProfileVersionId, int evidenceBundleVersion,
                                       String evidenceBundleHash, String evidenceBundleRef,
                                       String toolSchemaVersionsJson, String datasetHash, String configHash,
                                       UUID traceId, UUID correlationId, Instant createdAt) {
    }

    public record NewRagModelInvocationProvenance(UUID id, UUID spaceId, UUID runId, UUID stepId,
                                                  UUID modelInvocationId, UUID ragPromptVersionId,
                                                  String promptHash, UUID indexVersionId,
                                                  UUID retrievalProfileId, int retrievalProfileVersion,
                                                  UUID modelRouteVersionId, UUID modelProfileVersionId,
                                                  int evidenceBundleVersion, String evidenceBundleHash,
                                                  String evidenceBundleRef, String toolSchemaVersionsJson,
                                                  String datasetHash, String configHash, UUID traceId,
                                                  UUID correlationId, Instant createdAt) {
    }

    public record RagRunProvenance(UUID id, UUID spaceId, UUID runId, UUID ragPromptVersionId,
                                   String promptHash, UUID indexVersionId, UUID retrievalProfileId,
                                   int retrievalProfileVersion, UUID modelRouteVersionId,
                                   UUID modelProfileVersionId, int evidenceBundleVersion,
                                   String evidenceBundleHash, String evidenceBundleRef,
                                   String toolSchemaVersionsJson, String datasetHash, String configHash,
                                   UUID traceId, UUID correlationId, Instant createdAt) {
        private RagRunProvenance(UUID id, UUID spaceId, UUID runId, RagValues values) {
            this(id, spaceId, runId, values.ragPromptVersionId(), values.promptHash(), values.indexVersionId(),
                    values.retrievalProfileId(), values.retrievalProfileVersion(), values.modelRouteVersionId(),
                    values.modelProfileVersionId(), values.evidenceBundleVersion(), values.evidenceBundleHash(),
                    values.evidenceBundleRef(), values.toolSchemaVersionsJson(), values.datasetHash(),
                    values.configHash(), values.traceId(), values.correlationId(), values.createdAt());
        }
    }

    public record RagStepProvenance(UUID id, UUID spaceId, UUID runId, UUID stepId, UUID ragPromptVersionId,
                                    String promptHash, UUID indexVersionId, UUID retrievalProfileId,
                                    int retrievalProfileVersion, UUID modelRouteVersionId,
                                    UUID modelProfileVersionId, int evidenceBundleVersion,
                                    String evidenceBundleHash, String evidenceBundleRef,
                                    String toolSchemaVersionsJson, String datasetHash, String configHash,
                                    UUID traceId, UUID correlationId, Instant createdAt) {
        private RagStepProvenance(UUID id, UUID spaceId, UUID runId, UUID stepId, RagValues values) {
            this(id, spaceId, runId, stepId, values.ragPromptVersionId(), values.promptHash(), values.indexVersionId(),
                    values.retrievalProfileId(), values.retrievalProfileVersion(), values.modelRouteVersionId(),
                    values.modelProfileVersionId(), values.evidenceBundleVersion(), values.evidenceBundleHash(),
                    values.evidenceBundleRef(), values.toolSchemaVersionsJson(), values.datasetHash(),
                    values.configHash(), values.traceId(), values.correlationId(), values.createdAt());
        }
    }

    public record RagModelInvocationProvenance(UUID id, UUID spaceId, UUID runId, UUID stepId,
                                               UUID modelInvocationId, UUID ragPromptVersionId,
                                               String promptHash, UUID indexVersionId, UUID retrievalProfileId,
                                               int retrievalProfileVersion, UUID modelRouteVersionId,
                                               UUID modelProfileVersionId, int evidenceBundleVersion,
                                               String evidenceBundleHash, String evidenceBundleRef,
                                               String toolSchemaVersionsJson, String datasetHash,
                                               String configHash, UUID traceId, UUID correlationId,
                                               Instant createdAt) {
        private RagModelInvocationProvenance(UUID id, UUID spaceId, UUID runId, UUID stepId,
                                             UUID modelInvocationId, RagValues values) {
            this(id, spaceId, runId, stepId, modelInvocationId, values.ragPromptVersionId(), values.promptHash(),
                    values.indexVersionId(), values.retrievalProfileId(), values.retrievalProfileVersion(),
                    values.modelRouteVersionId(), values.modelProfileVersionId(), values.evidenceBundleVersion(),
                    values.evidenceBundleHash(), values.evidenceBundleRef(), values.toolSchemaVersionsJson(),
                    values.datasetHash(), values.configHash(), values.traceId(), values.correlationId(),
                    values.createdAt());
        }
    }

    public record RagReplayProjection(RagRunProvenance run, List<RagStepProvenance> steps,
                                      List<RagModelInvocationProvenance> modelInvocations) {
        public RagReplayProjection {
            steps = List.copyOf(steps);
            modelInvocations = List.copyOf(modelInvocations);
        }
    }

    private record RagValues(UUID ragPromptVersionId, String promptHash, UUID indexVersionId,
                             UUID retrievalProfileId, int retrievalProfileVersion, UUID modelRouteVersionId,
                             UUID modelProfileVersionId, int evidenceBundleVersion,
                             String evidenceBundleHash, String evidenceBundleRef, String toolSchemaVersionsJson,
                             String datasetHash, String configHash, UUID traceId, UUID correlationId,
                             Instant createdAt) {
    }
}
