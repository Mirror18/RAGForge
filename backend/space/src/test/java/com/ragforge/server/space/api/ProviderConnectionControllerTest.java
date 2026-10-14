package com.ragforge.server.space.api;

import com.ragforge.server.shared.domain.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import com.ragforge.server.space.api.ProviderConnectionController;
import com.ragforge.server.space.application.ProviderConnectionService;

class ProviderConnectionControllerTest {
    @Test
    void updateDelegatesSpaceAndVersionPreconditions() {
        ProviderConnectionService service = mock(ProviderConnectionService.class);
        ProviderConnectionController controller = new ProviderConnectionController(service);
        UUID spaceId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        ProviderConnectionService.ProviderConnectionUpdateRequest request =
                new ProviderConnectionService.ProviderConnectionUpdateRequest(
                        "Cloud", "OPENAI_COMPATIBLE", "CLOUD", "https://api.example.invalid/v1", "credential-ref", "ACTIVE", 1);
        SessionPrincipal principal = mock(SessionPrincipal.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);

        controller.update(spaceId, connectionId, request, "\"1\"", principal, servletRequest);

        verify(service).update(spaceId, connectionId, request, "\"1\"", principal, servletRequest);
    }
}
