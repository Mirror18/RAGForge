package com.ragforge.server.answer.integration;

import com.ragforge.server.answer.AnswerAuthorizationContext;
import com.ragforge.server.answer.AnswerRequest;
import com.ragforge.server.answer.SpaceAccessDeniedException;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceAuthorization;
import com.ragforge.server.run.RunRepository;
import com.ragforge.server.space.SpaceRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionSpaceAnswerAuthorizerTest {
    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID CORRELATION = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");

    @Test
    void rejectsLegacyCallWithoutTypedAuthorizationContext() {
        SpaceAuthorization spaces = mock(SpaceAuthorization.class);
        RunRepository runs = mock(RunRepository.class);
        SessionSpaceAnswerAuthorizer authorizer = new SessionSpaceAnswerAuthorizer(spaces, runs, fixedClock());

        assertThatThrownBy(() -> authorizer.requireAccess(SPACE, request()))
                .isInstanceOf(SpaceAccessDeniedException.class)
                .hasMessageContaining("context is required");
        verify(spaces, never()).requireMember(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(runs, never()).findRun(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsExpiredOrCrossSpaceContextBeforeRepositoryAccess() {
        SpaceAuthorization spaces = mock(SpaceAuthorization.class);
        RunRepository runs = mock(RunRepository.class);
        SessionSpaceAnswerAuthorizer authorizer = new SessionSpaceAnswerAuthorizer(spaces, runs, fixedClock());
        AnswerAuthorizationContext expired = context(SPACE, NOW);
        AnswerAuthorizationContext crossSpace = context(UUID.randomUUID(), NOW.plusSeconds(60));

        assertThatThrownBy(() -> authorizer.requireAccess(SPACE, request(), expired))
                .isInstanceOf(SpaceAccessDeniedException.class);
        assertThatThrownBy(() -> authorizer.requireAccess(SPACE, request(), crossSpace))
                .isInstanceOf(SpaceAccessDeniedException.class);
        verify(spaces, never()).requireMember(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(runs, never()).findRun(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rechecksMembershipAndRunOwnership() {
        SpaceAuthorization spaces = mock(SpaceAuthorization.class);
        RunRepository runs = mock(RunRepository.class);
        SessionSpaceAnswerAuthorizer authorizer = new SessionSpaceAnswerAuthorizer(spaces, runs, fixedClock());
        SessionPrincipal principal = principal(NOW.plusSeconds(60));
        AnswerAuthorizationContext context = new AnswerAuthorizationContext(principal, SPACE, SpaceRole.EDITOR,
                RUN, CORRELATION, RUN, NOW.plusSeconds(60));
        RunRepository.RunRecord run = new RunRepository.RunRecord(RUN, SPACE, USER, CORRELATION,
                RunRepository.RequestKind.CHAT, RunRepository.RunStatus.RUNNING, null, null, null, null,
                null, null, NOW, NOW, NOW, NOW, 0);
        when(spaces.requireMember(SPACE, principal)).thenReturn(SpaceRole.EDITOR);
        when(runs.findRun(SPACE, RUN)).thenReturn(Optional.of(run));

        assertThatCode(() -> authorizer.requireAccess(SPACE, request(), context)).doesNotThrowAnyException();
        verify(spaces).requireMember(SPACE, principal);
        verify(runs).findRun(SPACE, RUN);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static SessionPrincipal principal(Instant expiresAt) {
        return new SessionPrincipal(USER, UUID.randomUUID(), "user@example.com", "User", "csrf",
                "USER", expiresAt);
    }

    private static AnswerAuthorizationContext context(UUID space, Instant expiresAt) {
        return new AnswerAuthorizationContext(principal(expiresAt), space, SpaceRole.EDITOR,
                RUN, CORRELATION, RUN, expiresAt);
    }

    private static AnswerRequest request() {
        return new AnswerRequest(SPACE, RUN, CORRELATION, "answer-auth-test-0001", "What is verified?",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "local-test",
                com.ragforge.server.provider.adapter.EgressDecision.LOCAL_ONLY, 1000, Duration.ofSeconds(30),
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    }
}
