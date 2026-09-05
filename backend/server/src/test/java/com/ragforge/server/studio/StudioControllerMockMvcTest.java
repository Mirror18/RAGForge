package com.ragforge.server.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StudioControllerMockMvcTest {
    private final ChunkStudioService chunks = mock(ChunkStudioService.class);
    private final RetrievalPlaygroundService playground = mock(RetrievalPlaygroundService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new ChunkStudioController(chunks),
                        new RetrievalPlaygroundController(playground))
                .setValidator(validator).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void overrideBodyRejectsFullTextAndClientAuditFields() throws Exception {
        UUID space = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        String body = "{" +
                "\"documentRevisionId\":\"" + UUID.randomUUID() + "\"," +
                "\"contentRef\":\"opaque://replacement/1\"," +
                "\"textHash\":\"" + "a".repeat(64) + "\"," +
                "\"reason\":\"review\"," +
                "\"fullText\":\"must-not-be-accepted\"," +
                "\"createdBy\":\"" + UUID.randomUUID() + "\"}";

        mvc.perform(post("/api/v1/spaces/{space}/chunk-studio/children/{child}/overrides", space, child)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(chunks);
    }

    @Test
    void playgroundRejectsBodySpaceBoundaryAndMissingCandidateFlag() throws Exception {
        UUID space = UUID.randomUUID();
        String body = mapper.writeValueAsString(new java.util.LinkedHashMap<>(java.util.Map.of(
                "spaceId", space, "query", "hello", "indexVersionId", UUID.randomUUID(),
                "profileA", java.util.Map.of("profileId", UUID.randomUUID(), "version", 1,
                        "candidateOnly", false), "queryVector", List.of(0.1))));

        mvc.perform(post("/api/v1/spaces/{space}/retrieval-playground/experiments", space)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(playground);
    }

}
