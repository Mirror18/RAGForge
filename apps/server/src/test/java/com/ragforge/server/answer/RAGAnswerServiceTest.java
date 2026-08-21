package com.ragforge.server.answer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.provider.adapter.EgressDecision;
import com.ragforge.server.retrieval.EvidenceBundle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RAGAnswerServiceTest {
    private static final UUID SPACE = uuid("018f0f70-8e10-7b14-8f1a-111111111111");
    private static final UUID FOREIGN_SPACE = uuid("018f0f70-8e10-7b14-8f1a-999999999999");
    private static final UUID RUN = uuid("018f0f70-8e10-7b14-8f1a-222222222222");
    private static final UUID CORRELATION = uuid("018f0f70-8e10-7b14-8f1a-333333333333");
    private static final UUID PROMPT = uuid("018f0f70-8e10-7b14-8f1a-444444444444");
    private static final UUID ROUTE = uuid("018f0f70-8e10-7b14-8f1a-555555555555");
    private static final UUID MODEL_PROFILE = uuid("018f0f70-8e10-7b14-8f1a-666666666666");
    private static final UUID INDEX = uuid("018f0f70-8e10-7b14-8f1a-777777777777");
    private static final UUID PROFILE = uuid("018f0f70-8e10-7b14-8f1a-888888888888");
    private static final UUID REVISION = uuid("018f0f70-8e10-7b14-8f1a-aaaaaaaaaaaa");
    private static final UUID PARENT = uuid("018f0f70-8e10-7b14-8f1a-bbbbbbbbbbbb");
    private static final UUID EVIDENCE_A = uuid("018f0f70-8e10-7b14-8f1a-000000000001");
    private static final UUID EVIDENCE_B = uuid("018f0f70-8e10-7b14-8f1a-000000000002");
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String KEY = "phase5-answer-key-01";

    @Test
    void validMultiClaimAnswerProjectsOnlyBundleCitationsAndVersionedProvenance() throws Exception {
        AtomicReference<GenerationPort.GenerationRequest> generationRequest = new AtomicReference<>();
        AtomicInteger generations = new AtomicInteger();
        InMemoryAnswerPersistence persistence = new InMemoryAnswerPersistence();
        RAGAnswerService service = service(snapshot(false, null), persistence,
                (request, token) -> {
                    generations.incrementAndGet();
                    return completed(new GenerationPort.GenerationResult(
                            "Alpha is stable. Beta is bounded.",
                            List.of(new GenerationPort.GeneratedClaim("Alpha is stable.", List.of(EVIDENCE_A.toString()), 0, 16),
                                    new GenerationPort.GeneratedClaim("Beta is bounded.", List.of(EVIDENCE_B.toString()), 17, 33)),
                            "local-test-model", EgressDecision.LOCAL_ONLY));
                }, generationRequest);

        Answer answer = service.answer(request(100, new CancellationToken()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.COMPLETED);
        assertThat(answer.claims()).hasSize(2);
        assertThat(answer.citations()).hasSize(2);
        assertThat(answer.citations()).extracting(Citation::evidenceId)
                .containsExactly(EVIDENCE_A, EVIDENCE_B);
        assertThat(answer.citations()).allSatisfy(citation -> {
            assertThat(citation.spaceId()).isEqualTo(SPACE);
            assertThat(citation.runId()).isEqualTo(RUN);
            assertThat(citation.evidenceBundleVersion()).isEqualTo(1);
            assertThat(citation.evidenceBundleHash()).matches("[0-9a-f]{64}");
            assertThat(citation.contentRef()).doesNotContain("http", ".pdf");
        });
        assertThat(answer.provenance().promptHash()).isEqualTo(HASH);
        assertThat(generationRequest.get().renderedPrompt()).contains(EVIDENCE_A.toString(), EVIDENCE_B.toString());
        assertThat(generations).hasValue(1);
        assertThat(persistence.size()).isEqualTo(1);
    }

    @Test
    void invalidOutsideMalformedAndDuplicateTokensBecomeFailedWithoutCitations() {
        for (List<String> tokens : List.of(
                List.of("[1]"),
                List.of(uuid("018f0f70-8e10-7b14-8f1a-ffffffffffff").toString()),
                List.of(EVIDENCE_A + ":prefix"),
                List.of(EVIDENCE_A.toString(), EVIDENCE_A.toString()))) {
            RAGAnswerService service = service(snapshot(false, null), new InMemoryAnswerPersistence(),
                    (request, token) -> completed(new GenerationPort.GenerationResult(
                            "Alpha is stable.", List.of(new GenerationPort.GeneratedClaim("Alpha is stable.", tokens)),
                            "local-test-model", EgressDecision.LOCAL_ONLY)), null);

            Answer answer = service.answer(request(100, new CancellationToken()));

            assertThat(answer.status()).isEqualTo(AnswerStatus.FAILED);
            assertThat(answer.claims()).isEmpty();
            assertThat(answer.citations()).isEmpty();
            assertThat(answer.abstention().reasonCode()).isEqualTo(AbstentionReason.POLICY_BLOCKED);
        }
    }

    @Test
    void lowConfidenceConflictingAndEmptyBundlesAbstainBeforeGeneration() {
        for (String reason : java.util.Arrays.asList(null, "LOW_CONFIDENCE", "CONFLICTING")) {
            AtomicInteger generations = new AtomicInteger();
            EvidenceBundleSnapshot bundle = reason == null ? snapshot(true, "NO_VERIFIED_EVIDENCE")
                    : snapshot(true, reason);
            RAGAnswerService service = service(bundle, new InMemoryAnswerPersistence(),
                    (request, token) -> {
                        generations.incrementAndGet();
                        return completed(new GenerationPort.GenerationResult("unused",
                                List.of(new GenerationPort.GeneratedClaim("unused", List.of(EVIDENCE_A.toString()))),
                                "model", EgressDecision.LOCAL_ONLY));
                    }, null);

            Answer answer = service.answer(request(100, new CancellationToken()));

            assertThat(answer.status()).isEqualTo(AnswerStatus.ABSTAINED);
            assertThat(answer.citations()).isEmpty();
            assertThat(generations).hasValue(0);
            assertThat(answer.abstention().reasonCode()).isIn(AbstentionReason.NO_EVIDENCE,
                    AbstentionReason.LOW_CONFIDENCE, AbstentionReason.EVIDENCE_CONFLICT);
        }
    }

    @Test
    void contextBudgetLimitsEvidenceInjectedIntoGeneration() {
        AtomicReference<GenerationPort.GenerationRequest> generated = new AtomicReference<>();
        RAGAnswerService service = service(snapshot(false, null), new InMemoryAnswerPersistence(),
                (request, token) -> completed(new GenerationPort.GenerationResult(
                        "Alpha is stable.", List.of(new GenerationPort.GeneratedClaim("Alpha is stable.",
                                List.of(EVIDENCE_A.toString()))), "model", EgressDecision.LOCAL_ONLY)), null,
                generated);

        Answer answer = service.answer(request(4, new CancellationToken()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.COMPLETED);
        assertThat(generated.get().evidenceBundle().bundle().evidence()).extracting(EvidenceBundle.Evidence::evidenceId)
                .containsExactly(EVIDENCE_A);
    }

    @Test
    void cancellationAndTimeoutNeverProduceSuccessfulCitations() {
        CancellationToken cancelled = new CancellationToken();
        cancelled.cancel();
        RAGAnswerService cancelledService = service(snapshot(false, null), new InMemoryAnswerPersistence(),
                (request, token) -> completed(new GenerationPort.GenerationResult("unused",
                        List.of(new GenerationPort.GeneratedClaim("unused", List.of(EVIDENCE_A.toString()))),
                        "model", EgressDecision.LOCAL_ONLY)), null);
        assertThat(cancelledService.answer(request(100, cancelled)).status()).isEqualTo(AnswerStatus.CANCELLED);

        RAGAnswerService timeoutService = service(snapshot(false, null), new InMemoryAnswerPersistence(),
                (request, token) -> new CompletableFuture<>(), null);
        Answer timedOut = timeoutService.answer(request(100, new CancellationToken(), Duration.ofMillis(10)));
        assertThat(timedOut.status()).isEqualTo(AnswerStatus.FAILED);
        assertThat(timedOut.citations()).isEmpty();
    }

    @Test
    void egressDecisionIsPassedToGenerationAndNoFallbackIsAttempted() {
        AtomicInteger generations = new AtomicInteger();
        RAGAnswerService service = service(snapshot(false, null), new InMemoryAnswerPersistence(),
                (request, token) -> {
                    generations.incrementAndGet();
                    return completed(new GenerationPort.GenerationResult("Alpha is stable.",
                            List.of(new GenerationPort.GeneratedClaim("Alpha is stable.", List.of(EVIDENCE_A.toString()))),
                            "model", EgressDecision.CLOUD_ALLOWED));
                }, null);

        Answer answer = service.answer(request(100, new CancellationToken()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.FAILED);
        assertThat(answer.abstention().reasonCode()).isEqualTo(AbstentionReason.POLICY_BLOCKED);
        assertThat(generations).hasValue(1);
    }

    @Test
    void sameIdempotencyKeyDoesNotRepeatGenerationOrPersistence() {
        AtomicInteger generations = new AtomicInteger();
        InMemoryAnswerPersistence persistence = new InMemoryAnswerPersistence();
        RAGAnswerService service = service(snapshot(false, null), persistence,
                (request, token) -> {
                    generations.incrementAndGet();
                    return completed(new GenerationPort.GenerationResult("Alpha is stable.",
                            List.of(new GenerationPort.GeneratedClaim("Alpha is stable.", List.of(EVIDENCE_A.toString()))),
                            "model", EgressDecision.LOCAL_ONLY));
                }, null);

        Answer first = service.answer(request(100, new CancellationToken()));
        Answer second = service.answer(request(100, new CancellationToken()));

        assertThat(second).isSameAs(first);
        assertThat(generations).hasValue(1);
        assertThat(persistence.size()).isEqualTo(1);
    }

    @Test
    void parserRejectsCrossSpaceBundleScopeAndNoRawCitationFieldsArePersisted() throws Exception {
        EvidenceBundle foreignBundle = new EvidenceBundle(FOREIGN_SPACE, INDEX, PROFILE, 1, "q", "q",
                List.of(evidence(EVIDENCE_B, FOREIGN_SPACE, 4, 8)), false, null);
        assertThatThrownBy(() -> new CitationTokenParser().parse(List.of(EVIDENCE_B.toString()), foreignBundle, SPACE))
                .isInstanceOf(CitationTokenParser.CitationTokenException.class);

        AtomicReference<GenerationPort.GenerationRequest> generationRequest = new AtomicReference<>();
        InMemoryAnswerPersistence persistence = new InMemoryAnswerPersistence();
        RAGAnswerService service = service(snapshot(false, null), persistence,
                (request, token) -> completed(new GenerationPort.GenerationResult("Alpha is stable.",
                        List.of(new GenerationPort.GeneratedClaim("Alpha is stable.", List.of(EVIDENCE_A.toString()))),
                        "model", EgressDecision.LOCAL_ONLY)), generationRequest);
        Answer answer = service.answer(request(100, new CancellationToken()));
        String redacted = new ObjectMapper().writeValueAsString(persistence.find(SPACE, KEY).orElseThrow());
        assertThat(redacted).doesNotContain(answer.answerText(), generationRequest.get().renderedPrompt());
        assertThat(redacted).doesNotContain("content:alpha");
    }

    private static RAGAnswerService service(EvidenceBundleSnapshot snapshot, AnswerPersistencePort persistence,
                                             GenerationPort generation, AtomicReference<GenerationPort.GenerationRequest> requestRef) {
        return service(snapshot, persistence, generation, requestRef, null);
    }

    private static RAGAnswerService service(EvidenceBundleSnapshot snapshot, AnswerPersistencePort persistence,
                                             GenerationPort generation, AtomicReference<GenerationPort.GenerationRequest> ignored,
                                             AtomicReference<GenerationPort.GenerationRequest> secondRef) {
        GenerationPort wrapped = (request, token) -> {
            if (ignored != null) ignored.set(request);
            if (secondRef != null) secondRef.set(request);
            return generation.generate(request, token);
        };
        return new RAGAnswerService((spaceId, request) -> {
                    if (!SPACE.equals(spaceId)) throw new SpaceAccessDeniedException("foreign space");
                },
                (request, decision, token) -> List.of(0.1, 0.2),
                (request, token) -> snapshot,
                (spaceId, promptVersionId, correlationId) -> new RagPromptPort.VersionedRagPrompt(
                        PROMPT, SPACE, "rag-answer", 1, "Answer the question {{query}} using only the evidence.",
                        "opaque:rag-answer-v1", HASH),
                wrapped, persistence, provenance -> {
                });
    }

    private static AnswerRequest request(int budget, CancellationToken token) {
        return request(budget, token, Duration.ofSeconds(1));
    }

    private static AnswerRequest request(int budget, CancellationToken token, Duration timeout) {
        return new AnswerRequest(SPACE, RUN, CORRELATION, KEY, "What is stable?", PROMPT, ROUTE, MODEL_PROFILE,
                "local-test-model", EgressDecision.LOCAL_ONLY, budget, timeout, "{}", HASH, HASH_B, RUN, token);
    }

    private static EvidenceBundleSnapshot snapshot(boolean abstained, String reason) {
        EvidenceBundle bundle = new EvidenceBundle(SPACE, INDEX, PROFILE, 1, "What is stable?", "What is stable?",
                List.of(evidence(EVIDENCE_A, SPACE, 0, 4), evidence(EVIDENCE_B, SPACE, 4, 8)), abstained,
                abstained ? (reason == null ? "NO_VERIFIED_EVIDENCE" : reason) : null);
        return new EvidenceBundleSnapshot(uuid("018f0f70-8e10-7b14-8f1a-121212121212"), 1, HASH_B,
                "evidence:phase5", bundle,
                List.of(new EvidenceBundleSnapshot.EvidenceMaterial(EVIDENCE_A, "alpha"),
                        new EvidenceBundleSnapshot.EvidenceMaterial(EVIDENCE_B, "beta")), HASH, HASH_B);
    }

    private static EvidenceBundle.Evidence evidence(UUID id, UUID space, int tokenStart, int tokenEnd) {
        return new EvidenceBundle.Evidence(id, space, INDEX, REVISION, PARENT, id, "content:" + id,
                id.equals(EVIDENCE_A) ? HASH : HASH_B,
                new EvidenceBundle.Anchor(List.of("Design"), tokenStart, tokenEnd, tokenStart * 10,
                        tokenEnd * 10, 1, null, null, null, null, null),
                0.9, 0.8, 0.7, 0.6, "direct-hit");
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
