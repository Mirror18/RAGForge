package com.ragforge.server.answer.api;

import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceAuthorization;
import com.ragforge.server.space.SpaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnswerReadinessControllerTest {
    private final UUID spaceId = UuidV7.random();

    @Test
    void memberCanReadReadinessForTheRequestedSpace() throws Exception {
        AnswerReadinessService readiness = mock(AnswerReadinessService.class);
        SpaceAuthorization authorization = mock(SpaceAuthorization.class);
        when(authorization.requireMember(eq(spaceId), any())).thenReturn(SpaceRole.VIEWER);
        when(readiness.inspect(spaceId)).thenReturn(new AnswerReadinessService.AnswerReadiness(spaceId, true, List.of()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AnswerReadinessController(readiness, authorization)).build();

        mvc.perform(get("/api/v1/spaces/{spaceId}/answer-readiness", spaceId)
                        .principal(authentication()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spaceId").value(spaceId.toString()))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.gaps").isEmpty());
    }

    @Test
    void otherSpaceIsRejectedBeforeInspection() {
        AnswerReadinessService readiness = mock(AnswerReadinessService.class);
        SpaceAuthorization authorization = mock(SpaceAuthorization.class);
        UUID other = UuidV7.random();
        when(authorization.requireMember(eq(other), any())).thenThrow(
                new ApiException(HttpStatus.NOT_FOUND, "space_not_found", "Space not found", "Space not found"));
        AnswerReadinessController controller = new AnswerReadinessController(readiness, authorization);
        assertThatThrownBy(() -> controller.get(other, authentication()))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("space_not_found");
        verify(readiness, never()).inspect(any());
    }

    private Authentication authentication() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(new SessionPrincipal(UuidV7.random(), UuidV7.random(),
                "test@example.invalid", "Test", "csrf", "USER", Instant.now().plusSeconds(300)));
        return authentication;
    }
}
