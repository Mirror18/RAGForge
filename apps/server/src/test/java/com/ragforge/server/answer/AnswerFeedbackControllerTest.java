package com.ragforge.server.answer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceAuthorization;
import com.ragforge.server.space.SpaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnswerFeedbackControllerTest {
    private final UUID spaceId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID evidenceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final AnswerFeedbackRepository repository = mock(AnswerFeedbackRepository.class);
    private final SpaceAuthorization authorization = mock(SpaceAuthorization.class);
    private final Authentication authentication = mock(Authentication.class);
    private final SessionPrincipal principal = new SessionPrincipal(userId, UUID.randomUUID(),
            "feedback@example.test", "Feedback User", "csrf", "USER", Instant.MAX);

    private MockMvc mvc() {
        when(authentication.getPrincipal()).thenReturn(principal);
        return MockMvcBuilders.standaloneSetup(new AnswerFeedbackController(repository, authorization)).build();
    }

    @Test
    void feedbackIsBoundToTheRequestedSpaceRunAndEvidence() throws Exception {
        when(authorization.requireMember(eq(spaceId), eq(principal))).thenReturn(SpaceRole.VIEWER);
        AnswerFeedbackRepository.FeedbackRecord saved = new AnswerFeedbackRepository.FeedbackRecord(
                UUID.randomUUID(), spaceId, runId, evidenceId, userId, "HELPFUL", null,
                "feedback-key-0001", "a".repeat(64), 0, Instant.now(), Instant.now());
        when(repository.save(eq(spaceId), eq(runId), eq(evidenceId), eq(userId), eq("HELPFUL"),
                eq(null), eq("feedback-key-0001"), eq(null))).thenReturn(saved);

        mvc().perform(post("/api/v1/spaces/{space}/answers/{run}/feedback", spaceId, runId)
                        .principal(authentication).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "feedback-key-0001")
                        .content("{\"evidenceId\":\"" + evidenceId + "\",\"sentiment\":\"HELPFUL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.spaceId").value(spaceId.toString()))
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.evidenceId").value(evidenceId.toString()));
        verify(repository).save(eq(spaceId), eq(runId), eq(evidenceId), eq(userId), eq("HELPFUL"),
                eq(null), eq("feedback-key-0001"), eq(null));
    }

    @Test
    void feedbackRequiresMembershipInTheRequestedSpace() throws Exception {
        when(authentication.getPrincipal()).thenReturn(principal);
        when(authorization.requireMember(eq(spaceId), eq(principal))).thenThrow(new RuntimeException("denied"));
        assertThatThrownBy(() -> new AnswerFeedbackController(repository, authorization).create(spaceId, runId,
                new AnswerFeedbackController.FeedbackRequest(evidenceId, "HELPFUL", null, null),
                "feedback-key-denied", null, authentication)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void feedbackForwardsOptimisticVersionAndIfMatch() throws Exception {
        when(authorization.requireMember(eq(spaceId), eq(principal))).thenReturn(SpaceRole.VIEWER);
        AnswerFeedbackRepository.FeedbackRecord saved = new AnswerFeedbackRepository.FeedbackRecord(
                UUID.randomUUID(), spaceId, runId, evidenceId, userId, "NOT_HELPFUL", "incorrect",
                "feedback-key-0002", "b".repeat(64), 2, Instant.now(), Instant.now());
        when(repository.save(eq(spaceId), eq(runId), eq(evidenceId), eq(userId), eq("NOT_HELPFUL"),
                eq("incorrect"), eq("feedback-key-0002"), eq(2L))).thenReturn(saved);

        mvc().perform(post("/api/v1/spaces/{space}/answers/{run}/feedback", spaceId, runId)
                        .principal(authentication).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "feedback-key-0002").header("If-Match", "\"2\"")
                        .content("{\"evidenceId\":\"" + evidenceId + "\",\"sentiment\":\"NOT_HELPFUL\",\"reason\":\"incorrect\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));
        verify(repository).save(eq(spaceId), eq(runId), eq(evidenceId), eq(userId), eq("NOT_HELPFUL"),
                eq("incorrect"), eq("feedback-key-0002"), eq(2L));
    }
}
