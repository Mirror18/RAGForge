package com.ragforge.server.knowledge.api;

import com.ragforge.server.shared.application.GlobalExceptionHandler;
import com.ragforge.server.knowledge.application.ChunkStudioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StudioControllerMockMvcTest {
    private final ChunkStudioService chunks = mock(ChunkStudioService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new ChunkStudioController(chunks))
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
}
