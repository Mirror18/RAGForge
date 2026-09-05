package com.ragforge.server.prompt;

import com.ragforge.server.identity.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PromptManagementControllerTest {
    @Test
    void bindingUpdateDelegatesSpaceVersionAndPromptReference() {
        PromptManagementService service = mock(PromptManagementService.class);
        PromptManagementController controller = new PromptManagementController(service);
        UUID spaceId = UUID.randomUUID();
        PromptManagementService.PromptBindingUpdateRequest request =
                new PromptManagementService.PromptBindingUpdateRequest(3, UUID.randomUUID());
        SessionPrincipal principal = mock(SessionPrincipal.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);

        controller.updateBinding(spaceId, request, "3", principal, servletRequest);

        verify(service).updateBinding(spaceId, request, "3", principal, servletRequest);
    }
}
