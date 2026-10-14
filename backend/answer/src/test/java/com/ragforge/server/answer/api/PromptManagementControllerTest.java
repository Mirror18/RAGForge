package com.ragforge.server.answer.api;

import com.ragforge.server.shared.domain.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import com.ragforge.server.answer.api.PromptManagementController;
import com.ragforge.server.answer.application.PromptManagementService;

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
