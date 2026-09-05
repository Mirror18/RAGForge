package com.ragforge.server.ingestion;

import com.ragforge.server.identity.SessionPrincipal;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BusinessIngestionControllerTest {
    @Test
    void documentProjectionDelegatesSpaceScopedLookup() {
        BusinessIngestionService service = mock(BusinessIngestionService.class);
        WebSourceIngestionService webSources = mock(WebSourceIngestionService.class);
        GitSourceService gitSources = mock(GitSourceService.class);
        SourceTaskCenterService taskCenter = mock(SourceTaskCenterService.class);
        BusinessIngestionController controller = new BusinessIngestionController(service, webSources, gitSources, taskCenter);
        UUID spaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        SessionPrincipal principal = mock(SessionPrincipal.class);

        controller.document(spaceId, documentId, principal);

        verify(service).document(spaceId, documentId, principal);
    }

    @Test
    void revisionProjectionDelegatesBothDocumentAndRevisionIds() {
        BusinessIngestionService service = mock(BusinessIngestionService.class);
        BusinessIngestionController controller = new BusinessIngestionController(service, mock(WebSourceIngestionService.class),
                mock(GitSourceService.class), mock(SourceTaskCenterService.class));
        UUID spaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        SessionPrincipal principal = mock(SessionPrincipal.class);

        controller.revision(spaceId, documentId, revisionId, principal);

        verify(service).revision(spaceId, documentId, revisionId, principal);
    }
}
