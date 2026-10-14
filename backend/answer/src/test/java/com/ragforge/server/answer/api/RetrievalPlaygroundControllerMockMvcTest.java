package com.ragforge.server.answer.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.answer.application.RetrievalPlaygroundService;
import com.ragforge.server.shared.application.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RetrievalPlaygroundControllerMockMvcTest {
    private final RetrievalPlaygroundService playground = mock(RetrievalPlaygroundService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new RetrievalPlaygroundController(playground))
                .setValidator(validator).setControllerAdvice(new GlobalExceptionHandler()).build();
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
