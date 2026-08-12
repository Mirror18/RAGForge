package com.ragforge.server.run;

import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.identity.SessionPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunExecutionControllerTest {
    @Test
    void createRunResponseContainsHashesOnlyAndNoRawPromptOrCredential() {
        RunExecutionService service = mock(RunExecutionService.class);
        RunExecutionController controller = new RunExecutionController(service);
        UUID space = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        RunRepository.RunRecord record = new RunRepository.RunRecord(run, space, conversation, UUID.randomUUID(),
                correlation, RunRepository.RequestKind.CHAT, RunRepository.RunStatus.SUCCEEDED, UUID.randomUUID(),
                UUID.randomUUID(), "input-hash", "output-hash", null, null, Instant.now(), Instant.now(),
                Instant.now(), Instant.now(), 2);
        when(service.createRun(eq(space), eq(conversation), any(), any(), eq(correlation))).thenReturn(record);

        UUID user = UUID.randomUUID();
        SessionPrincipal principal = new SessionPrincipal(user, UUID.randomUUID(), "u@test", "User", "csrf", "USER", Instant.MAX);
        Authentication authentication = new TestingAuthenticationToken(principal, null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CorrelationIdFilter.ATTRIBUTE, correlation.toString());

        RunExecutionController.RunResponse response = controller.createRun(space, conversation,
                new RunExecutionController.CreateRunRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), "raw prompt secret", false, 5), authentication, request);

        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(response.inputHash()).isEqualTo("input-hash");
        String serialized = response.toString();
        assertThat(serialized).doesNotContain("raw prompt secret", "credential", "apiKey", "accessToken");
    }

    @Test
    void createRunRequestRoundTripsThroughJackson() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        RunExecutionController.CreateRunRequest request = new RunExecutionController.CreateRunRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "hello", false, 5);
        assertThat(mapper.readValue(mapper.writeValueAsString(request),
                RunExecutionController.CreateRunRequest.class)).isEqualTo(request);
    }
}
