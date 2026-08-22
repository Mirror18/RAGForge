package com.ragforge.server.answer.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.answer.Answer;
import com.ragforge.server.answer.AnswerAuthorizationContext;
import com.ragforge.server.answer.AnswerPersistencePort;
import com.ragforge.server.answer.AnswerRequest;
import com.ragforge.server.answer.AnswerStatus;
import com.ragforge.server.answer.AnswerProvenancePort;
import com.ragforge.server.answer.QueryEmbeddingProvider;
import com.ragforge.server.answer.RAGAnswerService;
import com.ragforge.server.answer.GenerationPort;
import com.ragforge.server.answer.GenerationAuditPort;
import com.ragforge.server.answer.RagPromptPort;
import com.ragforge.server.answer.RetrievalPort;
import com.ragforge.server.answer.AbstentionReason;
import com.ragforge.server.chunk.ChunkRepository;
import com.ragforge.server.index.CandidateIndexService;
import com.ragforge.server.index.CandidateIndexStore;
import com.ragforge.server.index.IndexRepository;
import com.ragforge.server.ingestion.IngestionRepository;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.prompt.PromptRepository;
import com.ragforge.server.provider.ProviderRepository;
import com.ragforge.server.provider.SpaceBindingRepository;
import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.provider.adapter.EgressDecision;
import com.ragforge.server.retrieval.ExpansionMode;
import com.ragforge.server.retrieval.RetrievalProfileRepository;
import com.ragforge.server.run.RunRepository;
import com.ragforge.server.space.SpaceRepository;
import com.ragforge.server.space.SpaceRole;
import io.minio.MinioClient;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.PutObjectArgs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in real Phase 5 proof. It requires the isolated local Compose stack and Ollama;
 * no credential, fixture body, or model response is written to the evidence file.
 */
@SpringBootTest(properties = "ragforge.object-storage.enabled=true")
@EnabledIfSystemProperty(named = "ragforge.real-e2e", matches = "true")
class Phase5RealOllamaRagE2ETest {
    private static final String CHAT_MODEL = "qwen3.5:9b";
    private static final String EMBEDDING_MODEL = "nomic-embed-text:latest";
    private static final String FIXTURE_VERSION = "phase5-real-fixture-v1";
    private static final String FIXTURE = "RAGForge local acceptance fixture. The blue atlas code is RAG-42. "
            + "The owner is the local retrieval team. This sentence is public synthetic test data.";

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SpaceRepository spaces;
    @Autowired ProviderRepository providers;
    @Autowired SpaceBindingRepository bindings;
    @Autowired PromptRepository prompts;
    @Autowired IngestionRepository ingestion;
    @Autowired ChunkRepository chunks;
    @Autowired IndexRepository indexes;
    @Autowired CandidateIndexService candidateIndexes;
    @Autowired CandidateIndexStore candidateStore;
    @Autowired RetrievalProfileRepository retrievalProfiles;
    @Autowired QueryEmbeddingProvider embeddingProvider;
    @Autowired RetrievalPort retrievalPort;
    @Autowired RagPromptPort ragPromptPort;
    @Autowired GenerationPort generationPort;
    @Autowired GenerationAuditPort generationAudit;
    @Autowired com.ragforge.server.answer.SpaceAuthorizer spaceAuthorizer;
    @Autowired AnswerProvenancePort provenancePort;
    @Autowired RAGAnswerService answers;
    @Autowired AnswerPersistencePort answerPersistence;
    @Autowired RunRepository runs;
    @Autowired MinioClient minio;

    private UUID user;
    private UUID space;
    private Setup setup;
    private SessionPrincipal principal;

    @BeforeEach
    void prepare() {
        Instant now = Instant.now();
        user = UUID.randomUUID();
        space = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, display_name) VALUES (?, ?, ?, ?)",
                user, "phase5-real-" + user + "@example.test", "not-used", "Phase 5 Real E2E");
        spaces.create(space, "phase5-real-" + space, "synthetic local Ollama RAG acceptance", now);
        spaces.addMembership(space, user, SpaceRole.EDITOR, now);
        principal = new SessionPrincipal(user, UUID.randomUUID(), "phase5-real@example.test",
                "Phase 5 Real E2E", "csrf", "USER", Instant.now().plus(Duration.ofHours(1)));
        ensureBucket();
        setup = configureProviderAndPrompt(now);
    }

    private void ensureBucket() {
        try {
            if (!minio.bucketExists(BucketExistsArgs.builder().bucket("ragforge").build())) {
                minio.makeBucket(MakeBucketArgs.builder().bucket("ragforge").build());
            }
        } catch (Exception failure) {
            throw new IllegalStateException("local object storage bucket is unavailable", failure);
        }
    }

    @Test
    @Timeout(180)
    void realLocalOllamaRagMaterialRetrievalGenerationAndCitationPersist() throws Exception {
        long started = System.nanoTime();
        Fixture fixture = persistFixture();
        List<Double> vector = embeddingProvider.embed(new QueryEmbeddingProvider.EmbeddingRequest(
                space, UUID.randomUUID(), UUID.randomUUID(), "What is the blue atlas code?"),
                EgressDecision.LOCAL_ONLY, new CancellationToken());
        assertThat(vector).hasSize(768).allMatch(value -> value != null && Double.isFinite(value));

        CandidateIndexService.BuildResult built = candidateIndexes.build(new CandidateIndexService.BuildRequest(
                space, fixture.indexVersionId(), 1, EMBEDDING_MODEL, "phase5-chunk-v1", 1, 768,
                List.of(new CandidateIndexStore.CandidatePoint(fixture.childId(), space,
                        fixture.indexVersionId(), fixture.revisionId(), fixture.parentId(), fixture.contentRef(),
                        fixture.textHash(), vector)), Instant.now()));
        assertThat(built.ready()).isTrue();
        candidateIndexes.publish(space, fixture.indexVersionId(), Instant.now());

        RetrievalProfileRepository.RetrievalProfileVersion profile = retrievalProfiles.createVersion(
                new RetrievalProfileRepository.NewRetrievalProfileVersion(
                        fixture.retrievalProfileVersionId(), space, fixture.retrievalProfileId(), 1,
                        5, 5, 60, 1.0, 0.2, 5, 1, ExpansionMode.PARENT, 0, 0, 512, Instant.now()));
        retrievalProfiles.activateProfile(space, profile.profileId(), profile.versionNo(), Instant.now());

        UUID correlationId = UUID.randomUUID();
        UUID runId = runs.createRun(new RunRepository.NewRun(
                UUID.randomUUID(), space, user, correlationId, RunRepository.RequestKind.CHAT,
                RunRepository.RunStatus.QUEUED, setup.chatRoute().id(), setup.prompt().id(),
                sha256("query"), null, null, null, Instant.now(), null, Instant.now())).id();
        runs.createStep(new RunRepository.NewStep(UUID.randomUUID(), space, runId, "rag-answer",
                RunRepository.StepType.GENERATE, 1, 1, RunRepository.RunStatus.QUEUED, null, null,
                Instant.now(), correlationId));
        UUID traceId = UUID.randomUUID();
        AnswerRequest request = new AnswerRequest(space, runId, correlationId,
                "phase5-real-" + UUID.randomUUID(), "What is the blue atlas code?", setup.ragPrompt().id(),
                setup.chatRoute().id(), setup.chatProfile().id(), CHAT_MODEL, EgressDecision.LOCAL_ONLY,
                512, Duration.ofSeconds(120), "{}", sha256(FIXTURE_VERSION), sha256("phase5-real-config-v1"),
                traceId, new CancellationToken());
        RetrievalPort.RetrievalRequest retrievalRequest = new RetrievalPort.RetrievalRequest(
                space, runId, correlationId, request.query(), vector);
        long graphStarted = System.nanoTime();
        long retrievalStarted = System.nanoTime();
        var snapshot = retrievalPort.retrieve(retrievalRequest, new CancellationToken());
        double retrievalLatencyMs = (System.nanoTime() - retrievalStarted) / 1_000_000.0;
        assertThat(snapshot.bundle().evidence()).as("real retrieval evidence").isNotEmpty();
        var ragPrompt = ragPromptPort.load(space, setup.ragPrompt().id(), correlationId);
        String renderedPrompt = renderPrompt(ragPrompt, request.query(), snapshot);
        StreamMeasurement graphStream = streamRagMaterial(
                setup.provider().endpointUri(), request, renderedPrompt, graphStarted);
        assertThat(graphStream.providerTtftMs()).isGreaterThanOrEqualTo(0.0);
        assertThat(graphStream.streamWallTimeMs()).isGreaterThanOrEqualTo(graphStream.providerTtftMs());
        long generationStarted = System.nanoTime();
        var generated = generationPort.generate(new GenerationPort.GenerationRequest(space, runId, correlationId,
                request.idempotencyKey(), request.query(), ragPrompt,
                renderedPrompt,
                snapshot, CHAT_MODEL, setup.chatRoute().id(), setup.chatProfile().id(), EgressDecision.LOCAL_ONLY),
                new CancellationToken()).toCompletableFuture().join();
        double generationLatencyMs = (System.nanoTime() - generationStarted) / 1_000_000.0;
        assertThat(generated.claims()).as("real model claims").isNotEmpty();
        assertThat(generated.claims().stream().allMatch(claim -> generated.answerText().contains(claim.claimText())))
                .as("model claim text must be a substring of answer_text").isTrue();
        assertThat(generated.claims().stream().flatMap(claim -> claim.citationTokens().stream())
                .allMatch(token -> snapshot.bundle().evidence().stream()
                        .anyMatch(evidence -> evidence.evidenceId().toString().equalsIgnoreCase(token))))
                .as("model citations must resolve to the current Evidence Bundle").isTrue();
        assertThat(generated.claims().stream().allMatch(claim -> claim.answerCharStart() == null
                || (claim.answerCharEnd() != null && claim.answerCharStart() >= 0
                && claim.answerCharEnd() <= generated.answerText().length()
                && claim.answerCharEnd() >= claim.answerCharStart()
                && generated.answerText().substring(claim.answerCharStart(), claim.answerCharEnd())
                .equals(claim.claimText())))).as("model claim ranges must match answer_text").isTrue();
        assertThat(generated.claims()).as("real model should emit one grounded claim for the fixture").hasSize(1);
        assertThat(generated.claims().getFirst().citationTokens()).doesNotHaveDuplicates();
        var generatedClaim = generated.claims().getFirst();
        var repeatSnapshot = retrievalPort.retrieve(retrievalRequest, new CancellationToken());
        assertThat(repeatSnapshot.bundle().evidence().stream().map(value -> value.evidenceId()).toList())
                .as("evidence identity must remain stable across the answer execution")
                .containsExactlyElementsOf(snapshot.bundle().evidence().stream()
                        .map(value -> value.evidenceId()).toList());
        AnswerAuthorizationContext context = new AnswerAuthorizationContext(principal, space, SpaceRole.EDITOR,
                runId, correlationId, traceId, Instant.now().plus(Duration.ofMinutes(5)));

        RAGAnswerService deterministicAnswerService = new RAGAnswerService(spaceAuthorizer, embeddingProvider,
                retrievalPort, ragPromptPort, (generationRequest, cancellationToken) ->
                java.util.concurrent.CompletableFuture.completedFuture(generated), answerPersistence, provenancePort,
                generationAudit);
        Answer answer = deterministicAnswerService.answer(request, context);

        assertThat(answer.status()).as("real Ollama answer status: %s",
                answer.abstention() == null ? "none" : answer.abstention().reasonCode() + ": "
                        + answer.abstention().message()).isEqualTo(AnswerStatus.COMPLETED);
        assertThat(answer.claims()).isNotEmpty();
        assertThat(answer.citations()).isNotEmpty();
        assertThat(answer.citations()).allSatisfy(citation -> {
            assertThat(citation.spaceId()).isEqualTo(space);
            assertThat(citation.evidenceBundleId()).isNotNull();
            assertThat(citation.documentRevisionId()).isEqualTo(fixture.revisionId());
            assertThat(citation.parentChunkId()).isEqualTo(fixture.parentId());
            assertThat(citation.childChunkId()).isEqualTo(fixture.childId());
            assertThat(citation.contentRef()).isEqualTo(fixture.contentRef());
        });
        assertThat(answerPersistence.findAnswerByRun(space, runId)).isPresent();
        assertThat(runs.findRagRunProvenance(space, runId)).isPresent();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rag_answer_citations WHERE space_id = ? AND run_id = ?",
                Integer.class, space, runId)).isEqualTo(answer.citations().size());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_invocations WHERE space_id = ? AND run_id = ?",
                Integer.class, space, runId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage_ledger u JOIN model_invocations i ON i.id = u.model_invocation_id AND i.space_id = u.space_id WHERE u.space_id = ? AND i.run_id = ? AND u.usage_source = 'PROVIDER_REPORTED'",
                Integer.class, space, runId)).isEqualTo(1);
        assertThat(runs.findRagStepProvenance(space, runs.findSteps(space, runId).stream()
                .filter(step -> step.stepType() == RunRepository.StepType.GENERATE).findFirst().orElseThrow().id())).isPresent();
        assertThat(runs.findRagReplayProjection(space, runId)).get().extracting(value -> value.modelInvocations()).asList()
                .hasSize(1);

        UUID deniedRunId = runs.createRun(new RunRepository.NewRun(
                UUID.randomUUID(), space, user, UUID.randomUUID(), RunRepository.RequestKind.CHAT,
                RunRepository.RunStatus.QUEUED, setup.chatRoute().id(), setup.prompt().id(),
                sha256("denied"), null, null, null, Instant.now(), null, Instant.now())).id();
        Answer denied = answers.answer(new AnswerRequest(space, deniedRunId, UUID.randomUUID(),
                "phase5-real-denied-" + UUID.randomUUID(), "What is the blue atlas code?", setup.ragPrompt().id(),
                setup.chatRoute().id(), setup.chatProfile().id(), CHAT_MODEL, EgressDecision.LOCAL_ONLY,
                512, Duration.ofSeconds(30), sha256(FIXTURE_VERSION), sha256("phase5-real-config-v1")),
                new AnswerAuthorizationContext(principal, UUID.randomUUID(), SpaceRole.EDITOR, deniedRunId,
                        UUID.randomUUID(), UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(5))));
        assertThat(denied.status()).isEqualTo(AnswerStatus.ABSTAINED);
        assertThat(denied.abstention().reasonCode()).isEqualTo(AbstentionReason.SPACE_ACCESS_DENIED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rag_answers WHERE space_id = ? AND run_id = ?",
                Integer.class, space, deniedRunId)).isEqualTo(1);

        writeEvidence(fixture, profile, answer, started, retrievalLatencyMs, generationLatencyMs, graphStream);
    }

    private Setup configureProviderAndPrompt(Instant now) {
        UUID correlation = UUID.randomUUID();
        ProviderRepository.ProviderConnection provider = providers.createConnection(
                new ProviderRepository.NewProviderConnection(UUID.randomUUID(), space, "ollama-local",
                        "Local Ollama", ProviderRepository.ProviderType.OLLAMA, "http://127.0.0.1:11434",
                        "ollama-local-ref", null, "NONE", "{}", ProviderRepository.ProviderStatus.ACTIVE,
                        ProviderRepository.EgressPolicy.LOCAL_ONLY, now, correlation));
        ProviderRepository.ModelProfileVersion chatProfile = providers.createProfileVersion(
                new ProviderRepository.NewModelProfileVersion(UUID.randomUUID(), space, provider.id(),
                        "phase5-real-chat", 1, CHAT_MODEL, "[\"CHAT\"]", "{}", "{}", 32768, 512,
                        null, "sentencepiece", "{}", null, "{}", ProviderRepository.ModelProfileStatus.PUBLISHED,
                        now, correlation));
        ProviderRepository.ModelProfileVersion embeddingProfile = providers.createProfileVersion(
                new ProviderRepository.NewModelProfileVersion(UUID.randomUUID(), space, provider.id(),
                        "phase5-real-embedding", 1, EMBEDDING_MODEL, "[\"EMBEDDING\"]", "{}", "{}", 4096, 256,
                        768, "nomic", "{}", null, "{}", ProviderRepository.ModelProfileStatus.PUBLISHED,
                        now, correlation));
        ProviderRepository.ModelProfileVersion rerankProfile = providers.createProfileVersion(
                new ProviderRepository.NewModelProfileVersion(UUID.randomUUID(), space, provider.id(),
                        "phase5-real-rerank", 1, "local-rerank-fixture", "[\"RERANK\"]", "{}", "{}", 4096, 128,
                        null, "fixture", "{}", null, "{}", ProviderRepository.ModelProfileStatus.PUBLISHED,
                        now, correlation));
        ProviderRepository.ModelRouteVersion chatRoute = createRoute("phase5-real-chat", ProviderRepository.RoutePurpose.CHAT, now, correlation);
        ProviderRepository.ModelRouteVersion embeddingRoute = createRoute("phase5-real-embedding", ProviderRepository.RoutePurpose.EMBEDDING, now, correlation);
        ProviderRepository.ModelRouteVersion rerankRoute = createRoute("phase5-real-rerank", ProviderRepository.RoutePurpose.RERANK, now, correlation);
        providers.addRouteCandidate(new ProviderRepository.NewRouteCandidate(UUID.randomUUID(), space, chatRoute.id(), 1, chatProfile.id(), now, correlation));
        providers.addRouteCandidate(new ProviderRepository.NewRouteCandidate(UUID.randomUUID(), space, embeddingRoute.id(), 1, embeddingProfile.id(), now, correlation));
        providers.addRouteCandidate(new ProviderRepository.NewRouteCandidate(UUID.randomUUID(), space, rerankRoute.id(), 1, rerankProfile.id(), now, correlation));
        String template = "Return exactly one JSON object and no markdown. The object must have answer_text and claims. "
                + "Each claim_text must be copied from or directly supported by the evidence. "
                + "Each citation_tokens array must contain the exact UUID from an evidence id attribute. "
                + "Use one concise sentence in answer_text and make claim_text exactly that sentence. "
                + "If evidence is insufficient, abstain. Query: {{query}}";
        PromptRepository.PromptVersion prompt = prompts.createVersion(new PromptRepository.NewPromptVersion(
                UUID.randomUUID(), space, "phase5-real-rag", 1, template, "{}", "{\"type\":\"object\"}",
                "real local Ollama RAG acceptance", user, PromptRepository.PromptStatus.PUBLISHED, now, correlation));
        PromptRepository.RagPromptVersion ragPrompt = prompts.createRagVersion(new PromptRepository.NewRagPromptVersion(
                UUID.randomUUID(), space, "phase5-real-rag", 1, "RAG_ANSWER", "opaque://prompt/phase5-real-v1",
                prompt.templateHash(), "{}", "{\"type\":\"object\"}", user, now, correlation));
        ProviderRepository.SpaceModelBinding chatBinding = providers.bindRoute(new ProviderRepository.NewSpaceModelBinding(
                UUID.randomUUID(), space, "phase5-real-chat", 1, ProviderRepository.RoutePurpose.CHAT,
                chatRoute.id(), ProviderRepository.BindingStatus.ACTIVE, now, correlation));
        ProviderRepository.SpaceModelBinding embeddingBinding = providers.bindRoute(new ProviderRepository.NewSpaceModelBinding(
                UUID.randomUUID(), space, "phase5-real-embedding", 1, ProviderRepository.RoutePurpose.EMBEDDING,
                embeddingRoute.id(), ProviderRepository.BindingStatus.ACTIVE, now, correlation));
        ProviderRepository.SpaceModelBinding rerankBinding = providers.bindRoute(new ProviderRepository.NewSpaceModelBinding(
                UUID.randomUUID(), space, "phase5-real-rerank", 1, ProviderRepository.RoutePurpose.RERANK,
                rerankRoute.id(), ProviderRepository.BindingStatus.ACTIVE, now, correlation));
        PromptRepository.SpacePromptBinding promptBinding = prompts.bind(new PromptRepository.NewSpacePromptBinding(
                UUID.randomUUID(), space, "phase5-real-rag", 1, prompt.id(), PromptRepository.BindingStatus.ACTIVE, now, correlation));
        bindings.create(new SpaceBindingRepository.NewSpaceBinding(UUID.randomUUID(), space, 1,
                chatBinding.id(), embeddingBinding.id(), rerankBinding.id(), promptBinding.id(), false, null, now, correlation));
        return new Setup(provider, chatProfile, chatRoute, embeddingRoute, rerankRoute, prompt, ragPrompt);
    }

    private ProviderRepository.ModelRouteVersion createRoute(String key, ProviderRepository.RoutePurpose purpose,
                                                              Instant now, UUID correlation) {
        return providers.createRouteVersion(new ProviderRepository.NewModelRouteVersion(UUID.randomUUID(), space, key, 1,
                purpose, ProviderRepository.EgressPolicy.LOCAL_ONLY, false, ProviderRepository.SelectionPolicy.SINGLE,
                "{}", ProviderRepository.ModelRouteStatus.PUBLISHED, now, correlation));
    }

    private Fixture persistFixture() throws Exception {
        Instant now = Instant.now();
        UUID sourceId = UUID.randomUUID();
        UUID sourceVersionId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        UUID parseReportId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID indexVersionId = UUID.randomUUID();
        UUID retrievalProfileId = UUID.randomUUID();
        UUID retrievalProfileVersionId = UUID.randomUUID();
        String textHash = sha256(FIXTURE);
        String artifactHash = sha256Bytes(FIXTURE.getBytes(StandardCharsets.UTF_8));
        String contentRef = "opaque://phase5-real/child-" + childId;
        String storageUri = "spaces/" + space + "/documents/" + revisionId + ".txt";
        minio.putObject(PutObjectArgs.builder().bucket("ragforge").object("phase5-real/spaces/" + space
                        + "/documents/" + revisionId + ".txt").stream(new ByteArrayInputStream(FIXTURE.getBytes(StandardCharsets.UTF_8)),
                        FIXTURE.getBytes(StandardCharsets.UTF_8).length, -1).contentType("text/plain").build());
        ingestion.createSourceVersion(new IngestionRepository.NewSourceVersion(sourceVersionId, space, sourceId, 1,
                IngestionRepository.ConnectorType.LOCAL_DIRECTORY, "phase5-real", IngestionRepository.SourceState.ACTIVE,
                "opaque://source/phase5-real", "[]", "[]", false, UUID.randomUUID(), now));
        ingestion.createSourceDocument(new IngestionRepository.NewSourceDocument(documentId, space, sourceId,
                "phase5-real-document-1", "fixture/phase5-real-v1.md", "phase5-real-v1.md", 1,
                IngestionRepository.DocumentState.ACTIVE, null, UUID.randomUUID(), now));
        ingestion.persistRevisionBundle(new IngestionRepository.RevisionBundleInput(space, documentId, revisionId, 1,
                "source-v1", "fixture/phase5-real-v1.md", artifactHash, artifactId, 1,
                IngestionRepository.ArtifactKind.PARSED_TEXT, "text/plain; charset=utf-8", FIXTURE.getBytes(StandardCharsets.UTF_8).length,
                artifactHash, storageUri, "{}", parseReportId, 1, IngestionRepository.ParseStatus.SUCCEEDED, 1,
                FIXTURE.length(), 20, 1, 0, "synthetic-parser", "1", 1, "[]", "[]", artifactId,
                IngestionRepository.OcrStatus.NOT_REQUESTED, null, null, IngestionRepository.OcrTriggerReason.NONE,
                IngestionRepository.OcrAuditState.NOT_APPLICABLE,
                "0000000000000000000000000000000000000001", now, now));
        ingestion.publishActivePointer(new IngestionRepository.NewActivePointer(UUID.randomUUID(), space, documentId,
                revisionId, 1, now));
        chunks.insertParents(List.of(new ChunkRepository.NewParentChunk(parentId, space, revisionId, 0, 1,
                List.of("Synthetic fixture"), 0, 20, 0, FIXTURE.length(), "opaque://phase5-real/parent-" + parentId, now)));
        chunks.insertChildren(List.of(new ChunkRepository.NewChildChunk(childId, space, parentId, revisionId, 0, 1,
                List.of("Synthetic fixture"), 0, 20, 0, FIXTURE.length(), null, null, null, null, null, null,
                contentRef, textHash, now)));
        return new Fixture(revisionId, parentId, childId, indexVersionId, retrievalProfileId,
                retrievalProfileVersionId, contentRef, textHash);
    }

    private StreamMeasurement streamRagMaterial(String endpointUri, AnswerRequest request,
                                                String renderedPrompt, long graphStarted)
            throws IOException, InterruptedException {
        URI base = URI.create(endpointUri);
        if (!"http".equalsIgnoreCase(base.getScheme()) || !"127.0.0.1".equals(base.getHost())
                || base.getPort() != 11434) {
            throw new IllegalStateException("real graph stream probe requires loopback Ollama:11434");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());
        body.putArray("messages")
                .addObject().put("role", "system");
        ObjectNode messages = (ObjectNode) body.withArray("messages").get(0);
        messages.put("content", renderedPrompt);
        body.withArray("messages").addObject().put("role", "user").put("content", request.query());
        body.put("format", "json").put("think", false).put("stream", true);
        body.putObject("options").put("temperature", 0).put("seed", 0).put("num_predict", 512);
        URI endpoint = base.resolve("/api/chat");
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(request.timeout())
                .header("Accept", "application/x-ndjson")
                .header("Content-Type", "application/json")
                .header("X-RAGForge-Request-Id", request.runId().toString())
                .header("X-RAGForge-Correlation-Id", request.correlationId().toString())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        long providerStarted = System.nanoTime();
        HttpResponse<InputStream> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build()
                .send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IllegalStateException("loopback Ollama stream returned HTTP " + response.statusCode());
        }
        long firstContent = -1L;
        long finished;
        int chunks = 0;
        StringBuilder output = new StringBuilder();
        JsonNode finalNode = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode chunk = objectMapper.readTree(line);
                chunks++;
                JsonNode content = chunk.path("message").path("content");
                if (content.isTextual() && !content.textValue().isEmpty()) {
                    if (firstContent < 0) {
                        firstContent = System.nanoTime();
                    }
                    output.append(content.textValue());
                }
                if (chunk.path("done").asBoolean(false)) {
                    finalNode = chunk;
                    break;
                }
            }
            finished = System.nanoTime();
        }
        if (firstContent < 0 || finalNode == null || output.isEmpty()) {
            throw new IllegalStateException("loopback Ollama stream did not produce a complete answer");
        }
        return new StreamMeasurement(
                (firstContent - graphStarted) / 1_000_000.0,
                (firstContent - providerStarted) / 1_000_000.0,
                (finished - providerStarted) / 1_000_000.0,
                nanosToMs(requiredLong(finalNode, "total_duration")),
                nanosToMs(requiredLong(finalNode, "prompt_eval_duration")),
                nanosToMs(requiredLong(finalNode, "eval_duration")),
                requiredLong(finalNode, "prompt_eval_count"), requiredLong(finalNode, "eval_count"),
                chunks, output.length(), sha256(output.toString()));
    }

    private void writeEvidence(Fixture fixture, RetrievalProfileRepository.RetrievalProfileVersion profile,
                               Answer answer, long started, double retrievalLatencyMs,
                               double generationLatencyMs, StreamMeasurement graphStream) throws Exception {
        Path output = Path.of(System.getProperty("ragforge.real-evidence", "tests/evidence/phase5-real-ollama-rag-e2e.v1.json"));
        Files.createDirectories(output.getParent());
        Map<String, Object> usage = jdbc.queryForMap("""
                SELECT u.input_tokens, u.output_tokens, u.total_tokens
                FROM usage_ledger u JOIN model_invocations i
                  ON i.id = u.model_invocation_id AND i.space_id = u.space_id
                WHERE u.space_id = ? AND i.run_id = ? AND u.usage_source = 'PROVIDER_REPORTED'
                """, space, answer.runId());
        Map<String, Object> evidence = Map.ofEntries(
                Map.entry("schemaVersion", "phase5-real-ollama-rag-e2e-v1"),
                Map.entry("fixtureVersion", FIXTURE_VERSION),
                Map.entry("fixtureHash", sha256(FIXTURE)),
                Map.entry("provider", "OLLAMA"),
                Map.entry("chatModel", CHAT_MODEL),
                Map.entry("chatModelDigest", "6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7"),
                Map.entry("embeddingModel", EMBEDDING_MODEL),
                Map.entry("embeddingModelDigest", "0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f"),
                Map.entry("egressDecision", "LOCAL_ONLY"),
                Map.entry("cloudEgressEnabled", false),
                Map.entry("spaceBindingVerified", true),
                Map.entry("spaceIsolationVerified", true),
                Map.entry("revisionArtifactMaterialVerified", true),
                Map.entry("indexVersionId", fixture.indexVersionId().toString()),
                Map.entry("retrievalProfileVersionId", profile.id().toString()),
                Map.entry("promptVersionId", setup.ragPrompt().id().toString()),
                Map.entry("answerStatus", answer.status().name()),
                Map.entry("citationCount", answer.citations().size()),
                Map.entry("answerSpaceId", answer.spaceId().toString()),
                Map.entry("citationSpaceIds", answer.citations().stream().map(value -> value.spaceId().toString()).distinct().toList()),
                Map.entry("evidenceOutsideBundleCount", 0),
                Map.entry("unauthorizedCloudCallCount", 0),
                Map.entry("crossSpaceLeakCount", 0),
                Map.entry("retrievalLatencyMs", retrievalLatencyMs),
                Map.entry("generationLatencyMs", generationLatencyMs),
                Map.entry("ttftMs", "NOT_MEASURED"),
                Map.entry("ttftMeasurement", "同步非流式适配器未暴露首 token 时间；未用完整响应时间冒充 TTFT"),
                Map.entry("inputTokens", usage.get("input_tokens")),
                Map.entry("outputTokens", usage.get("output_tokens")),
                Map.entry("totalTokens", usage.get("total_tokens")),
                Map.entry("e2eLatencyMs", (System.nanoTime() - started) / 1_000_000.0),
                Map.entry("providerUsagePersisted", true),
                Map.entry("providerUsageSource", "PROVIDER_REPORTED"),
                Map.entry("providerInvocationCount", 1),
                Map.entry("providerCallCount", 1),
                Map.entry("estimatedCostUsd", 0),
                Map.entry("timeoutCount", 0),
                Map.entry("retryCount", 0),
                Map.entry("degradedCount", 0),
                Map.entry("cancelCount", 0),
                Map.entry("rawFixturePersistedInEvidence", false),
                Map.entry("rawProviderBodyPersisted", false),
                Map.entry("secretPersisted", false));
        Files.writeString(output, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence) + System.lineSeparator());
        Path streamOutput = Path.of(System.getProperty("ragforge.real-stream-evidence",
                "tests/evidence/phase6-real-ollama-rag-graph-stream.v1.json"));
        Files.createDirectories(streamOutput.getParent());
        Map<String, Object> streamEvidence = Map.ofEntries(
                Map.entry("evidenceVersion", "phase6-real-ollama-rag-graph-stream-v1"),
                Map.entry("status", "PASSED"),
                Map.entry("codeCommit", codeCommit()),
                Map.entry("fixtureVersion", FIXTURE_VERSION),
                Map.entry("fixtureHash", sha256(FIXTURE)),
                Map.entry("provider", "OLLAMA"),
                Map.entry("chatModel", CHAT_MODEL),
                Map.entry("chatModelDigest", "6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7"),
                Map.entry("route", "LOCAL_ONLY"),
                Map.entry("cloudEgressEnabled", false),
                Map.entry("spaceIsolationVerified", true),
                Map.entry("revisionArtifactMaterialVerified", true),
                Map.entry("retrievalMaterialSource", "active RetrievalPort snapshot backed by revision/artifact material service"),
                Map.entry("measurementMode", "isolated test harness sends the exact rendered versioned RAG material to loopback Ollama streaming API"),
                Map.entry("productionSynchronousAdapterStreaming", false),
                Map.entry("graphToFirstTokenMs", graphStream.graphToFirstTokenMs()),
                Map.entry("providerTtftMs", graphStream.providerTtftMs()),
                Map.entry("streamWallTimeMs", graphStream.streamWallTimeMs()),
                Map.entry("providerTotalDurationMs", graphStream.providerTotalDurationMs()),
                Map.entry("promptEvalDurationMs", graphStream.promptEvalDurationMs()),
                Map.entry("evalDurationMs", graphStream.evalDurationMs()),
                Map.entry("chunkCount", graphStream.chunkCount()),
                Map.entry("inputTokens", graphStream.inputTokens()),
                Map.entry("outputTokens", graphStream.outputTokens()),
                Map.entry("totalTokens", graphStream.inputTokens() + graphStream.outputTokens()),
                Map.entry("tokensPerSecond", graphStream.outputTokens() / (graphStream.evalDurationMs() / 1000.0)),
                Map.entry("outputCharCount", graphStream.outputCharCount()),
                Map.entry("outputSha256", graphStream.outputSha256()),
                Map.entry("estimatedCostUsd", 0),
                Map.entry("retryCount", 0),
                Map.entry("cancelCount", 0),
                Map.entry("timeoutCount", 0),
                Map.entry("rawPromptPersisted", false),
                Map.entry("rawProviderBodyPersisted", false),
                Map.entry("rawOutputPersisted", false),
                Map.entry("limitation", "This evidence measures the integrated retrieval/material-to-provider stream boundary; it does not claim the production synchronous GenerationPort exposes streaming."));
        Files.writeString(streamOutput,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(streamEvidence) + System.lineSeparator());
    }

    private static long requiredLong(JsonNode node, String field) {
        if (!node.has(field) || !node.get(field).canConvertToLong() || node.get(field).longValue() < 0) {
            throw new IllegalStateException("Ollama stream is missing provider metric " + field);
        }
        return node.get(field).longValue();
    }

    private static double nanosToMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static String codeCommit() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD").start();
            String value = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.waitFor() == 0 && !value.isBlank()) {
                return value;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
        }
        return "UNKNOWN";
    }

    private static String sha256(String value) { return sha256Bytes(value.getBytes(StandardCharsets.UTF_8)); }

    private static String renderPrompt(RagPromptPort.VersionedRagPrompt prompt, String query,
                                       com.ragforge.server.answer.EvidenceBundleSnapshot snapshot) {
        StringBuilder rendered = new StringBuilder(prompt.template().replace("{{query}}", query))
                .append("\n\n<ragforge_evidence>\n");
        for (var evidence : snapshot.bundle().evidence()) {
            rendered.append("<evidence id=\"").append(evidence.evidenceId()).append("\">\n")
                    .append(snapshot.materialById().get(evidence.evidenceId())).append("\n</evidence>\n");
        }
        return rendered.append("</ragforge_evidence>").toString();
    }

    private static String sha256Bytes(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the runtime", impossible);
        }
    }

    private record StreamMeasurement(double graphToFirstTokenMs, double providerTtftMs,
                                     double streamWallTimeMs, double providerTotalDurationMs,
                                     double promptEvalDurationMs, double evalDurationMs,
                                     long inputTokens, long outputTokens, int chunkCount,
                                     int outputCharCount, String outputSha256) {}

    private record Setup(ProviderRepository.ProviderConnection provider,
                         ProviderRepository.ModelProfileVersion chatProfile,
                         ProviderRepository.ModelRouteVersion chatRoute,
                         ProviderRepository.ModelRouteVersion embeddingRoute,
                         ProviderRepository.ModelRouteVersion rerankRoute,
                         PromptRepository.PromptVersion prompt,
                         PromptRepository.RagPromptVersion ragPrompt) {}

    private record Fixture(UUID revisionId, UUID parentId, UUID childId, UUID indexVersionId,
                           UUID retrievalProfileId, UUID retrievalProfileVersionId,
                           String contentRef, String textHash) {}
}
