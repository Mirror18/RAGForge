package com.ragforge.server.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.ProviderRepository;
import com.ragforge.server.provider.SpaceBindingRepository;
import com.ragforge.server.prompt.PromptRepository;
import com.ragforge.server.space.SpaceRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunExecutionServiceTest {
    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID OTHER_SPACE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID ROUTE = UUID.randomUUID();
    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID CONNECTION = UUID.randomUUID();
    private static final UUID PROMPT = UUID.randomUUID();

    @Test
    void fakeAdapterExecutionPersistsStepInvocationUsageAndSafeEvents() {
        Fixtures fixtures = new Fixtures();
        UUID runId = UUID.randomUUID();
        RunRepository.RunRecord queued = fixtures.run(RunRepository.RunStatus.QUEUED, 0, runId, null);
        RunRepository.RunRecord running = fixtures.run(RunRepository.RunStatus.RUNNING, 1, runId, null);
        RunRepository.RunRecord completed = fixtures.run(RunRepository.RunStatus.SUCCEEDED, 2, runId, null);
        when(fixtures.runs.createRun(any())).thenReturn(queued);
        when(fixtures.runs.transitionRun(eq(SPACE), eq(queued.id()), eq(RunRepository.RunStatus.RUNNING),
                isNull(), isNull(), any(), eq(0L))).thenReturn(running);
        when(fixtures.runs.transitionRun(eq(SPACE), eq(queued.id()), eq(RunRepository.RunStatus.SUCCEEDED),
                isNull(), isNull(), any(), eq(1L), anyString())).thenReturn(completed);
        when(fixtures.runs.createStep(any())).thenReturn(fixtures.step(RunRepository.RunStatus.QUEUED));
        when(fixtures.runs.updateStep(eq(SPACE), any(), eq(RunRepository.RunStatus.SUCCEEDED),
                isNull(), isNull(), any())).thenReturn(fixtures.step(RunRepository.RunStatus.SUCCEEDED));
        when(fixtures.runs.findRun(eq(SPACE), any())).thenReturn(Optional.of(completed));

        RunRepository.RunRecord result = fixtures.service().createRun(SPACE, CONVERSATION, fixtures.principal(),
                fixtures.request("hello"), UUID.randomUUID());

        assertThat(result.status()).isEqualTo(RunRepository.RunStatus.SUCCEEDED);
        verify(fixtures.runs).createInvocation(any());
        verify(fixtures.runs).recordUsage(any());
        verify(fixtures.events, times(3)).append(eq(SPACE), eq(queued.id()), any(), eq("run.status"), eq(1), anyString());
        verify(fixtures.events).append(eq(SPACE), eq(queued.id()), any(), eq("run.completed"), eq(1), anyString());
        verify(fixtures.events, never()).append(eq(SPACE), eq(queued.id()), any(), eq("answer.delta"), eq(1), anyString());
    }

    @Test
    void fakeAdapterFailurePersistsStructuredFailedStateWithoutRawMessage() {
        Fixtures fixtures = new Fixtures();
        UUID runId = UUID.randomUUID();
        RunRepository.RunRecord queued = fixtures.run(RunRepository.RunStatus.QUEUED, 0, runId, null);
        RunRepository.RunRecord running = fixtures.run(RunRepository.RunStatus.RUNNING, 1, runId, null);
        RunRepository.RunRecord failed = fixtures.run(RunRepository.RunStatus.FAILED, 2,
                runId, RunRepository.ErrorClass.INVALID_RESPONSE);
        when(fixtures.runs.createRun(any())).thenReturn(queued);
        when(fixtures.runs.transitionRun(eq(SPACE), eq(queued.id()), eq(RunRepository.RunStatus.RUNNING),
                isNull(), isNull(), any(), eq(0L))).thenReturn(running);
        when(fixtures.runs.transitionRun(eq(SPACE), eq(queued.id()), eq(RunRepository.RunStatus.FAILED),
                eq(RunRepository.ErrorClass.INVALID_RESPONSE), eq("invalid_response"), any(), eq(1L)))
                .thenReturn(failed);
        when(fixtures.runs.createStep(any())).thenReturn(fixtures.step(RunRepository.RunStatus.QUEUED));
        when(fixtures.runs.updateStep(eq(SPACE), any(), eq(RunRepository.RunStatus.FAILED),
                eq(RunRepository.ErrorClass.INVALID_RESPONSE), eq("invalid_response"), any()))
                .thenReturn(fixtures.step(RunRepository.RunStatus.FAILED));
        when(fixtures.runs.findRun(eq(SPACE), any())).thenReturn(Optional.of(failed));

        RunRepository.RunRecord result = fixtures.service().createRun(SPACE, CONVERSATION, fixtures.principal(),
                fixtures.request("__fake_error__ secret raw prompt"), UUID.randomUUID());

        assertThat(result.status()).isEqualTo(RunRepository.RunStatus.FAILED);
        assertThat(result.errorClass()).isEqualTo(RunRepository.ErrorClass.INVALID_RESPONSE);
        verify(fixtures.runs).createInvocation(any());
        verify(fixtures.runs, never()).recordUsage(any());
        verify(fixtures.events, times(3)).append(eq(SPACE), eq(queued.id()), any(), eq("run.status"), eq(1),
                org.mockito.ArgumentMatchers.argThat(payload -> !payload.contains("secret")));
    }

    @Test
    void crossSpaceConversationCannotBeUsed() {
        Fixtures fixtures = new Fixtures();
        when(fixtures.conversations.find(SPACE, CONVERSATION)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> fixtures.service().createRun(SPACE, CONVERSATION, fixtures.principal(),
                fixtures.request("hello"), UUID.randomUUID()))
                .isInstanceOf(com.ragforge.server.common.ApiException.class)
                .hasMessage("Conversation not found");
        verify(fixtures.providers, never()).findRouteVersion(any(), any());
        assertThat(OTHER_SPACE).isNotEqualTo(SPACE);
    }

    private static final class Fixtures {
        final ConversationRepository conversations = mock(ConversationRepository.class);
        final RunRepository runs = mock(RunRepository.class);
        final RunEventService events = mock(RunEventService.class);
        final com.ragforge.server.space.SpaceRepository spaces = mock(com.ragforge.server.space.SpaceRepository.class);
        final ProviderRepository providers = mock(ProviderRepository.class);
        final SpaceBindingRepository bindings = mock(SpaceBindingRepository.class);
        final PromptRepository prompts = mock(PromptRepository.class);

        Fixtures() {
            when(spaces.findRole(SPACE, USER)).thenReturn(Optional.of(SpaceRole.EDITOR));
            when(bindings.findCurrent(SPACE)).thenReturn(Optional.of(new SpaceBindingRepository.SpaceBindingRecord(
                    UUID.randomUUID(), SPACE, 1, ROUTE, null, null, PROMPT, false, null,
                    Instant.now(), Instant.now(), UUID.randomUUID())));
            when(conversations.find(SPACE, CONVERSATION)).thenReturn(Optional.of(
                    new ConversationRepository.ConversationRecord(CONVERSATION, SPACE, USER, "test",
                            Instant.now(), Instant.now(), 0)));
            when(providers.findRouteVersion(SPACE, ROUTE)).thenReturn(Optional.of(new ProviderRepository.ModelRouteVersion(
                    ROUTE, SPACE, "chat", 1, ProviderRepository.RoutePurpose.CHAT,
                    ProviderRepository.EgressPolicy.LOCAL_ONLY, false, ProviderRepository.SelectionPolicy.SINGLE,
                    "{}", ProviderRepository.ModelRouteStatus.PUBLISHED, Instant.now(), Instant.now(), UUID.randomUUID())));
            when(providers.findProfileVersion(SPACE, PROFILE)).thenReturn(Optional.of(new ProviderRepository.ModelProfileVersion(
                    PROFILE, SPACE, CONNECTION, "fake", 1, "fake-model", "[\"CHAT\"]", "{}", "{}",
                    4096, 256, null, null, "{}", null, "{}", ProviderRepository.ModelProfileStatus.PUBLISHED,
                    Instant.now(), Instant.now(), UUID.randomUUID())));
            when(providers.findConnection(SPACE, CONNECTION)).thenReturn(Optional.of(new ProviderRepository.ProviderConnection(
                    CONNECTION, SPACE, "fake", "Fake", ProviderRepository.ProviderType.AI_RUNTIME,
                    "http://localhost", "fake-ref", null, "NONE", "{}", ProviderRepository.ProviderStatus.ACTIVE,
                    ProviderRepository.EgressPolicy.LOCAL_ONLY, Instant.now(), Instant.now(), UUID.randomUUID(), 1)));
            when(providers.listRouteCandidates(SPACE, ROUTE)).thenReturn(List.of(new ProviderRepository.RouteCandidate(
                    UUID.randomUUID(), SPACE, ROUTE, 1, PROFILE, Instant.now(), Instant.now(), UUID.randomUUID())));
            when(prompts.findVersion(SPACE, PROMPT)).thenReturn(Optional.of(new PromptRepository.PromptVersion(
                    PROMPT, SPACE, "chat", 1, "You are a safe assistant.", "hash", "{}", "{}", null, USER,
                    PromptRepository.PromptStatus.PUBLISHED, Instant.now(), Instant.now(), UUID.randomUUID())));
        }

        RunExecutionService service() {
            return new RunExecutionService(conversations, runs, events, spaces, providers, bindings, prompts,
                    new ProviderAdapterRegistry(List.of(new FakeProviderAdapter())), new ObjectMapper());
        }

        SessionPrincipal principal() {
            return new SessionPrincipal(USER, UUID.randomUUID(), "user@example.test", "User", "csrf", "USER", Instant.MAX);
        }

        RunExecutionService.RunRequest request(String message) {
            return new RunExecutionService.RunRequest(ROUTE, PROFILE, CONNECTION, PROMPT, message, false, 5);
        }

        RunRepository.RunRecord run(RunRepository.RunStatus status, long version, UUID id,
                                    RunRepository.ErrorClass errorClass) {
            return new RunRepository.RunRecord(id, SPACE, CONVERSATION, USER, UUID.randomUUID(),
                    RunRepository.RequestKind.CHAT, status, ROUTE, PROMPT, "input", null,
                    errorClass, errorClass == null ? null : "invalid_response",
                    status == RunRepository.RunStatus.QUEUED ? null : Instant.now(), null,
                    Instant.now(), Instant.now(), version);
        }

        RunRepository.StepRecord step(RunRepository.RunStatus status) {
            return new RunRepository.StepRecord(UUID.randomUUID(), SPACE, UUID.randomUUID(), "generate",
                    RunRepository.StepType.GENERATE, 1, 1, status, null, null, Instant.now(), Instant.now(), UUID.randomUUID());
        }
    }
}
