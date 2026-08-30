package com.ragforge.server.provider;

import com.ragforge.server.identity.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProviderConnectionControllerTest {
    @Test
    void updateDelegatesSpaceAndVersionPreconditions() {
        ProviderConnectionService service = mock(ProviderConnectionService.class);
        ProviderConnectionController controller = new ProviderConnectionController(service);
        UUID spaceId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        ProviderConnectionService.ProviderConnectionUpdateRequest request =
                new ProviderConnectionService.ProviderConnectionUpdateRequest(
                        "Local", "OLLAMA", "LOCAL", "http://localhost:11434", "credential-ref", "ACTIVE", 1);
        SessionPrincipal principal = mock(SessionPrincipal.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);

        controller.update(spaceId, connectionId, request, "\"1\"", principal, servletRequest);

        verify(service).update(spaceId, connectionId, request, "\"1\"", principal, servletRequest);
    }
}
