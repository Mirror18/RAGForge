package com.ragforge.server.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.common.CorrelationIdFilter;
import com.ragforge.server.identity.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.Set;

/** REST adapter for read-only, candidate-only retrieval experiments. */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/retrieval-playground")
public final class RetrievalPlaygroundController {
    private final RetrievalPlaygroundService service;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public RetrievalPlaygroundController(RetrievalPlaygroundService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    RetrievalPlaygroundController(RetrievalPlaygroundService service) {
        this(service, new ObjectMapper().findAndRegisterModules());
    }

    @PostMapping("/experiments")
    @ResponseStatus(HttpStatus.CREATED)
    public RetrievalPlaygroundService.Experiment experiment(
            @PathVariable UUID spaceId,
            @RequestBody JsonNode body,
            @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest servletRequest) {
        RetrievalPlaygroundService.ExperimentRequest request = StudioRequestParser.parse(objectMapper, body,
                RetrievalPlaygroundService.ExperimentRequest.class,
                Set.of("query", "indexVersionId", "profileA", "profileB", "queryVector"));
        return service.run(spaceId, request, principal, UUID.fromString(CorrelationIdFilter.current(servletRequest)));
    }
}
