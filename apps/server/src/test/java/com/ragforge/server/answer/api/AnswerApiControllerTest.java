package com.ragforge.server.answer.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.answer.Abstention;
import com.ragforge.server.answer.AbstentionReason;
import com.ragforge.server.answer.Answer;
import com.ragforge.server.answer.AnswerProvenance;
import com.ragforge.server.answer.AnswerRequest;
import com.ragforge.server.answer.AnswerStatus;
import com.ragforge.server.answer.Citation;
import com.ragforge.server.answer.Claim;
import com.ragforge.server.answer.RAGAnswerService;
import com.ragforge.server.answer.GenerationStreamObserver;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceAuthorization;
import com.ragforge.server.run.RunEvent;
import com.ragforge.server.run.RunEventService;
import com.ragforge.server.run.RunEventStore;
import com.ragforge.server.run.RunRepository;
import com.ragforge.server.retrieval.EvidenceBundle;
import com.ragforge.server.space.SpaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnswerApiControllerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID spaceId = UuidV7.random();
    private final UUID runId = UuidV7.random();
    private final UUID correlationId = UuidV7.random();
    private final UUID evidenceId = UuidV7.random();
    private final String idempotencyKey = "answer-api-key-0001";

    @Test
    void createIsSpaceAuthorizedIdempotentAndPublishesStructuredAnswer() throws Exception {
        RAGAnswerService answerService = mock(RAGAnswerService.class);
        RunEventService eventService = mock(RunEventService.class);
        SpaceAuthorization authorization = mock(SpaceAuthorization.class);
        doNothing().when(authorization).requireWrite(eq(spaceId), any());
        RunEventStore.Subscription streamSubscription = mock(RunEventStore.Subscription.class);
        when(eventService.subscribe(eq(spaceId), eq(runId), any())).thenReturn(streamSubscription);
        when(eventService.replay(spaceId, runId, null)).thenReturn(new RunEventStore.ReplayResult(
                List.of(), RunEventStore.CursorStatus.NO_CURSOR, null, 0, 0));
        Answer answer = completedAnswer();
        when(answerService.answer(any(AnswerRequest.class), isNull(), any(GenerationStreamObserver.class)))
                .thenReturn(answer);
        AnswerApiController controller = new AnswerApiController(answerService, eventService, authorization,
                objectMapper);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        Authentication authentication = authentication();
        String body = """
                {"runId":"%s","query":"What is verified?","promptVersionId":"%s",
                 "modelRouteVersionId":"%s","modelProfileVersionId":"%s","model":"local-test",
                 "maxContextTokens":1000,"timeoutSeconds":30,"toolSchemaVersionsJson":"{}",
                 "datasetHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "configHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                 "allowCloudEgress":false}
                """.formatted(runId, UuidV7.random(), UuidV7.random(), UuidV7.random());

        mvc.perform(post("/api/v1/spaces/{spaceId}/answers", spaceId)
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Correlation-Id", correlationId)
                        .principal(authentication).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.spaceId").value(spaceId.toString()))
                .andExpect(jsonPath("$.citations[0].evidenceId").value(evidenceId.toString()));

        mvc.perform(post("/api/v1/spaces/{spaceId}/answers", spaceId)
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Correlation-Id", correlationId)
                        .principal(authentication).content(body))
                .andExpect(status().isAccepted());
        verify(answerService).answer(any(AnswerRequest.class), isNull(), any(GenerationStreamObserver.class));
    }

    @Test
    void citationPreviewIsExactStoredProvenanceAndNeverReturnsBody() throws Exception {
        RAGAnswerService answerService = mock(RAGAnswerService.class);
        RunEventService eventService = mock(RunEventService.class);
        SpaceAuthorization authorization = mock(SpaceAuthorization.class);
        AnswerApiProjectionStore projections = new AnswerApiProjectionStore();
        Answer answer = completedAnswer();
        projections.saveIfAbsent(answer);
        AnswerApiController controller = new AnswerApiController(answerService, eventService, authorization,
                objectMapper, projections);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(authorization.requireMember(eq(spaceId), any())).thenReturn(SpaceRole.VIEWER);

        String response = mvc.perform(get("/api/v1/spaces/{spaceId}/runs/{runId}/citations/{evidenceId}/preview",
                        spaceId, runId, evidenceId).principal(authentication()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceId").value(evidenceId.toString()))
                .andExpect(jsonPath("$.documentRevisionId").value(answer.citations().getFirst().documentRevisionId().toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("fullText", "rawText", "documentContent", "quote", "url", "prompt");
    }

    @Test
    void cancelSignalsTheSameInFlightAnswerToken() throws Exception {
        RAGAnswerService answerService = mock(RAGAnswerService.class);
        RunEventService eventService = mock(RunEventService.class);
        SpaceAuthorization authorization = mock(SpaceAuthorization.class);
        doNothing().when(authorization).requireWrite(eq(spaceId), any());
        RunEventStore.Subscription cancellationSubscription = mock(RunEventStore.Subscription.class);
        when(eventService.subscribe(eq(spaceId), eq(runId), any())).thenReturn(cancellationSubscription);
        when(eventService.replay(spaceId, runId, null)).thenReturn(new RunEventStore.ReplayResult(
                List.of(), RunEventStore.CursorStatus.NO_CURSOR, null, 0, 0));
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<AnswerRequest> inFlight = new AtomicReference<>();
        when(answerService.answer(any(AnswerRequest.class), isNull(), any(GenerationStreamObserver.class)))
                .thenAnswer(invocation -> {
                    AnswerRequest request = invocation.getArgument(0);
                    inFlight.set(request);
                    started.countDown();
                    while (!request.cancellationToken().isCancellationRequested()) Thread.onSpinWait();
                    AnswerProvenance provenance = AnswerProvenance.unavailable(request.spaceId(),
                            request.correlationId(), request.runId(), request.idempotencyKey(), request.traceId(),
                            request.datasetHash(), request.configHash());
                    return Answer.refusal(request.answerId(), request.spaceId(), request.correlationId(),
                            request.runId(), request.idempotencyKey(), AnswerStatus.CANCELLED,
                            new Abstention(request.spaceId(), request.correlationId(), request.runId(),
                                    request.idempotencyKey(), AbstentionReason.CANCELLED, List.of(), "Cancelled"),
                            provenance);
                });
        RunEvent cancellationEvent = new RunEvent(UuidV7.random(), 1, runId, spaceId, correlationId,
                Instant.now(), "run.status", 1, "{}");
        when(eventService.cancel(spaceId, runId, correlationId))
                .thenReturn(new RunEventStore.CancellationResult(true, cancellationEvent));
        AnswerApiController controller = new AnswerApiController(answerService, eventService, authorization,
                objectMapper);
        MockHttpServletRequest createRequest = new MockHttpServletRequest();
        createRequest.setAttribute(CorrelationIdFilter.ATTRIBUTE, correlationId.toString());
        AnswerApiController.CreateAnswerRequest body = new AnswerApiController.CreateAnswerRequest(runId,
                "What is verified?", UuidV7.random(), UuidV7.random(), UuidV7.random(), "local-test", 1000,
                30, "{}", "a".repeat(64), "b".repeat(64), false);

        CompletableFuture<Answer> call = CompletableFuture.supplyAsync(() -> controller.create(spaceId, body,
                idempotencyKey, authentication(), createRequest));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        MockHttpServletRequest cancelRequest = new MockHttpServletRequest();
        cancelRequest.setAttribute(CorrelationIdFilter.ATTRIBUTE, correlationId.toString());
        AnswerApiController.CancelResponse cancelled = controller.cancel(spaceId, runId,
                new AnswerApiController.CancelRequest("user_cancelled"), "answer-cancel-key-0001",
                authentication(), cancelRequest);

        assertThat(cancelled.firstCancellation()).isTrue();
        assertThat(call.get(2, TimeUnit.SECONDS).status()).isEqualTo(AnswerStatus.CANCELLED);
        assertThat(inFlight.get().cancellationToken().isCancellationRequested()).isTrue();
    }

    @Test
    void remoteCancellationEventSignalsTheInFlightAnswerToken() throws Exception {
        RAGAnswerService answerService = mock(RAGAnswerService.class);
        RunEventService eventService = mock(RunEventService.class);
        SpaceAuthorization authorization = mock(SpaceAuthorization.class);
        doNothing().when(authorization).requireWrite(eq(spaceId), any());
        RunEventStore.Subscription cancellationSubscription = mock(RunEventStore.Subscription.class);
        AtomicReference<Consumer<RunEvent>> subscribed = new AtomicReference<>();
        when(eventService.subscribe(eq(spaceId), eq(runId), any())).thenAnswer(invocation -> {
            subscribed.set(invocation.getArgument(2));
            return cancellationSubscription;
        });
        when(eventService.replay(spaceId, runId, null)).thenReturn(new RunEventStore.ReplayResult(
                List.of(), RunEventStore.CursorStatus.NO_CURSOR, null, 0, 0));
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<AnswerRequest> inFlight = new AtomicReference<>();
        when(answerService.answer(any(AnswerRequest.class), isNull(), any(GenerationStreamObserver.class)))
                .thenAnswer(invocation -> {
                    AnswerRequest request = invocation.getArgument(0);
                    inFlight.set(request);
                    started.countDown();
                    while (!request.cancellationToken().isCancellationRequested()) Thread.onSpinWait();
                    AnswerProvenance provenance = AnswerProvenance.unavailable(request.spaceId(),
                            request.correlationId(), request.runId(), request.idempotencyKey(), request.traceId(),
                            request.datasetHash(), request.configHash());
                    return Answer.refusal(request.answerId(), request.spaceId(), request.correlationId(),
                            request.runId(), request.idempotencyKey(), AnswerStatus.CANCELLED,
                            new Abstention(request.spaceId(), request.correlationId(), request.runId(),
                                    request.idempotencyKey(), AbstentionReason.CANCELLED, List.of(), "Cancelled"),
                            provenance);
                });
        AnswerApiController controller = new AnswerApiController(answerService, eventService, authorization,
                objectMapper);
        MockHttpServletRequest createRequest = new MockHttpServletRequest();
        createRequest.setAttribute(CorrelationIdFilter.ATTRIBUTE, correlationId.toString());
        AnswerApiController.CreateAnswerRequest body = new AnswerApiController.CreateAnswerRequest(runId,
                "What is verified?", UuidV7.random(), UuidV7.random(), UuidV7.random(), "local-test", 1000,
                30, "{}", "a".repeat(64), "b".repeat(64), false);

        CompletableFuture<Answer> call = CompletableFuture.supplyAsync(() -> controller.create(spaceId, body,
                idempotencyKey, authentication(), createRequest));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        subscribed.get().accept(new RunEvent(UuidV7.random(), 1, runId, spaceId, correlationId, Instant.now(),
                RunEventStore.RUN_STATUS_EVENT_TYPE, 1, "{\"status\":\"CANCELLED\"}"));

        assertThat(call.get(2, TimeUnit.SECONDS).status()).isEqualTo(AnswerStatus.CANCELLED);
        assertThat(inFlight.get().cancellationToken().isCancellationRequested()).isTrue();
    }

    @Test
    void crossSpacePreviewDoesNotResolve() {
        AnswerApiProjectionStore projections = new AnswerApiProjectionStore();
        projections.saveIfAbsent(completedAnswer());
        assertThatThrownBy(() -> projections.preview(UuidV7.random(), runId, evidenceId))
                .isInstanceOf(AnswerApiProjectionStore.AnswerApiNotFoundException.class);
    }

    @Test
    void lastEventIdIsPassedToRunEventServiceAndBadEventsAreRejected() {
        RunEventService eventService = mock(RunEventService.class);
        SpaceAuthorization authorization = mock(SpaceAuthorization.class);
        AnswerApiController controller = new AnswerApiController(mock(RAGAnswerService.class), eventService,
                authorization, objectMapper);
        RunEventStore.Subscription subscription = mock(RunEventStore.Subscription.class);
        RunEventStore.OpenedStream opened = new RunEventStore.OpenedStream(
                new RunEventStore.ReplayResult(List.of(), RunEventStore.CursorStatus.AVAILABLE, null, 3, 1),
                subscription);
        when(eventService.openStream(eq(spaceId), eq(runId), eq("event-cursor-01"), any())).thenReturn(opened);
        when(authorization.requireMember(eq(spaceId), any())).thenReturn(SpaceRole.VIEWER);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CorrelationIdFilter.ATTRIBUTE, correlationId.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.events(spaceId, runId, "event-cursor-01", authentication(), request, response);
        verify(eventService).openStream(eq(spaceId), eq(runId), eq("event-cursor-01"), any());

        RunEvent malformed = new RunEvent(UuidV7.random(), 1, runId, spaceId, correlationId, Instant.now(),
                "answer.delta", 1, "{\"idempotency_key\":\"bad\",\"answer_id\":\"bad\"}");
        assertThatThrownBy(() -> controller.eventEnvelope(malformed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sseAdapterRejectsSensitiveOrUnsupportedEventsInsteadOfIgnoringThem() {
        AnswerSseEventAdapter adapter = new AnswerSseEventAdapter(objectMapper);
        RunEvent unsupported = new RunEvent(UuidV7.random(), 1, runId, spaceId, correlationId, Instant.now(),
                "unknown.event", 1, "{\"idempotency_key\":\"answer-api-key-0001\"}");
        assertThatThrownBy(() -> adapter.toEnvelope(unsupported)).isInstanceOf(IllegalArgumentException.class);
        RunEvent control = new RunEvent(UuidV7.random(), 2, runId, spaceId, correlationId, Instant.now(),
                "run.status", 1, "{\"status\":\"CANCELLED\"}");
        assertThat(adapter.isControlEvent(control)).isTrue();
        assertThatThrownBy(() -> adapter.toEnvelope(control)).isInstanceOf(IllegalArgumentException.class);
        RunEvent sensitive = new RunEvent(UuidV7.random(), 1, runId, spaceId, correlationId, Instant.now(),
                "answer.error", 1,
                "{\"idempotency_key\":\"answer-api-key-0001\",\"prompt\":\"secret\"}");
        assertThatThrownBy(() -> adapter.toEnvelope(sensitive)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publisherOutputPassesTheVersionedSseProjectionWithoutInternalPayloadFields() {
        RunEventService eventService = mock(RunEventService.class);
        AnswerEventPublisher publisher = new AnswerEventPublisher(eventService, objectMapper);
        publisher.publish(completedAnswer());

        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        verify(eventService, org.mockito.Mockito.times(3)).append(eq(spaceId), eq(runId), eq(correlationId),
                types.capture(), eq(1), payloads.capture());
        AnswerSseEventAdapter adapter = new AnswerSseEventAdapter(objectMapper);
        for (int index = 0; index < types.getAllValues().size(); index++) {
            RunEvent event = new RunEvent(UuidV7.random(), index + 1, runId, spaceId, correlationId, Instant.now(),
                    types.getAllValues().get(index), 1, payloads.getAllValues().get(index));
            JsonNode envelope = adapter.toEnvelope(event);
            assertThat(envelope.get("idempotency_key").asText()).isEqualTo(idempotencyKey);
            assertThat(envelope.get("payload").has("idempotency_key")).isFalse();
            assertThat(envelope.get("payload").has("space_id")).isFalse();
            if ("answer.citation".equals(types.getAllValues().get(index))) {
                assertThat(envelope.at("/payload/citation/anchor/token_start").isInt()).isTrue();
                assertThat(fields(envelope.at("/payload"))).containsExactlyInAnyOrder("answer_id", "claim_id", "citation");
                assertThat(fields(envelope.at("/payload/citation"))).containsExactlyInAnyOrder(
                        "schema_version", "evidence_id", "space_id", "correlation_id", "run_id",
                        "evidence_bundle_id", "index_version_id", "document_revision_id", "parent_chunk_id",
                        "child_chunk_id", "content_ref", "text_hash", "anchor", "citation_allowed");
                assertThat(envelope.at("/payload/citation").has("claim_id")).isFalse();
                assertThat(envelope.at("/payload/citation").has("idempotency_key")).isFalse();
                assertThat(envelope.at("/payload/citation").has("evidence_bundle_version")).isFalse();
                assertThat(envelope.at("/payload/citation").has("evidence_bundle_hash")).isFalse();
                assertThat(envelope.at("/payload/citation").has("retrieval_profile_id")).isFalse();
                assertThat(envelope.at("/payload/citation").has("retrieval_profile_version")).isFalse();
            }
        }
    }

    @Test
    void publisherProjectsRagUsageFromTheSameSpaceAndRun() throws Exception {
        RunEventService eventService = mock(RunEventService.class);
        RunRepository runs = mock(RunRepository.class);
        when(runs.findUsageByRun(spaceId, runId)).thenReturn(List.of(new RunRepository.UsageLedgerRecord(
                UuidV7.random(), spaceId, UuidV7.random(), "rag-" + runId,
                RunRepository.UsageSource.PROVIDER_REPORTED, "rag-" + runId,
                384L, 79L, 463L, java.math.BigDecimal.ZERO, "USD", "{}",
                Instant.now(), Instant.now(), correlationId)));

        new AnswerEventPublisher(eventService, objectMapper, null, runs).publish(completedAnswer());

        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        verify(eventService, org.mockito.Mockito.times(4)).append(eq(spaceId), eq(runId), eq(correlationId),
                types.capture(), eq(1), payloads.capture());
        int usageIndex = types.getAllValues().indexOf("answer.usage");
        assertThat(usageIndex).isGreaterThanOrEqualTo(0);
        JsonNode usage = objectMapper.readTree(payloads.getAllValues().get(usageIndex));
        assertThat(usage.get("inputTokens").asLong()).isEqualTo(384L);
        assertThat(usage.get("outputTokens").asLong()).isEqualTo(79L);
        assertThat(usage.get("totalTokens").asLong()).isEqualTo(463L);
        assertThat(usage.get("providerReported").asBoolean()).isTrue();
    }

    @Test
    void publisherRetainsIncrementalDeltasWithoutRepeatingTheFullAnswer() throws Exception {
        RunEventService eventService = mock(RunEventService.class);
        Answer answer = completedAnswer();
        AnswerRequest request = new AnswerRequest(spaceId, runId, correlationId, idempotencyKey,
                "What is verified?", UuidV7.random(), UuidV7.random(), UuidV7.random(), "local-test",
                com.ragforge.server.provider.adapter.EgressDecision.LOCAL_ONLY, 1000,
                java.time.Duration.ofSeconds(30), "{}", "a".repeat(64), "b".repeat(64), runId,
                new com.ragforge.server.provider.adapter.CancellationToken(), answer.answerId());
        AnswerEventPublisher publisher = new AnswerEventPublisher(eventService, objectMapper);

        publisher.publishDelta(request, answer.answerId(), "Verified ");
        publisher.publishDelta(request, answer.answerId(), "answer");
        publisher.publish(answer, true);

        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        verify(eventService, org.mockito.Mockito.times(4)).append(eq(spaceId), eq(runId), eq(correlationId),
                types.capture(), eq(1), payloads.capture());
        assertThat(types.getAllValues()).containsExactly("answer.delta", "answer.delta",
                "answer.citation", "answer.done");
        assertThat(objectMapper.readTree(payloads.getAllValues().get(0)).path("delta").asText()
                + objectMapper.readTree(payloads.getAllValues().get(1)).path("delta").asText())
                .isEqualTo("Verified answer");
        assertThat(payloads.getAllValues().subList(2, 4)).noneMatch(value -> value.contains("Verified answer"));
    }

    @Test
    void abstentionPayloadMatchesTheStrictAbstentionContract() {
        RunEventService eventService = mock(RunEventService.class);
        Answer answer = Answer.refusal(spaceId, correlationId, runId, idempotencyKey, AnswerStatus.ABSTAINED,
                new Abstention(spaceId, correlationId, runId, idempotencyKey, AbstentionReason.NO_EVIDENCE,
                        List.of(evidenceId), "No verified evidence was available."), unavailableProvenance());
        new AnswerEventPublisher(eventService, objectMapper).publish(answer);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        verify(eventService, org.mockito.Mockito.times(2)).append(eq(spaceId), eq(runId), eq(correlationId),
                type.capture(), eq(1), payload.capture());
        int abstentionIndex = type.getAllValues().indexOf("answer.abstention");
        RunEvent event = new RunEvent(UuidV7.random(), 1, runId, spaceId, correlationId, Instant.now(),
                "answer.abstention", 1, payload.getAllValues().get(abstentionIndex));
        JsonNode envelope = new AnswerSseEventAdapter(objectMapper).toEnvelope(event);
        assertThat(fields(envelope.at("/payload"))).containsExactlyInAnyOrder("answer_id", "abstention");
        assertThat(fields(envelope.at("/payload/abstention"))).containsExactlyInAnyOrder(
                "schema_version", "abstention_id", "space_id", "correlation_id", "run_id",
                "reason_code", "evidence_ids", "message");
        assertThat(envelope.at("/payload/abstention").has("idempotency_key")).isFalse();
    }

    private Authentication authentication() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(new SessionPrincipal(UuidV7.random(), UuidV7.random(),
                "test@example.invalid", "Test", "csrf", "USER", Instant.now().plusSeconds(300)));
        return authentication;
    }

    private Answer completedAnswer() {
        UUID claimId = UuidV7.random();
        EvidenceBundle.Anchor anchor = new EvidenceBundle.Anchor(List.of("Guide"), 0, 3, 0, 20,
                null, null, null, null, null, null);
        Citation citation = new Citation(evidenceId, claimId, spaceId, correlationId, runId, idempotencyKey,
                UuidV7.random(), 1, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                UuidV7.random(), UuidV7.random(), 1, UuidV7.random(), UuidV7.random(), UuidV7.random(),
                "chunk:internal", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", anchor, 0, 20);
        Claim claim = new Claim(spaceId, correlationId, runId, idempotencyKey, "Verified answer",
                List.of(evidenceId), 0, 15);
        AnswerProvenance provenance = new AnswerProvenance("v1", spaceId, correlationId, runId, idempotencyKey,
                citation.evidenceBundleId(), 1, citation.evidenceBundleHash(), "bundle:internal",
                citation.indexVersionId(), citation.retrievalProfileId(), 1, UuidV7.random(),
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", UuidV7.random(), UuidV7.random(),
                "local-test", "{}", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", UuidV7.random());
        return Answer.completed(spaceId, correlationId, runId, idempotencyKey, "Verified answer",
                List.of(claim), List.of(citation), provenance);
    }

    private AnswerProvenance unavailableProvenance() {
        return AnswerProvenance.unavailable(spaceId, correlationId, runId, idempotencyKey, UuidV7.random(),
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }
}
