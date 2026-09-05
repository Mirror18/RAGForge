package com.ragforge.server.answer.integration;

import com.ragforge.server.answer.GenerationPort;
import com.ragforge.server.answer.QueryEmbeddingProvider;
import com.ragforge.server.answer.RAGAnswerService;
import com.ragforge.server.answer.RagPromptPort;
import com.ragforge.server.answer.RetrievalPort;
import com.ragforge.server.answer.SpaceAuthorizer;
import com.ragforge.server.ingestion.RevisionArtifactMaterialConfiguration;
import com.ragforge.server.answer.integration.RevisionArtifactMaterialService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that the explicitly enabled production graph is actually assembled. */
@SpringBootTest(properties = {
        "ragforge.object-storage.enabled=true",
        "ragforge.object-storage.endpoint=http://object-storage.invalid:9000",
        "ragforge.object-storage.access-key=phase5-test-access",
        "ragforge.object-storage.secret-key=phase5-test-secret",
        "ragforge.object-storage.bucket=ragforge",
        "ragforge.object-storage.prefix=phase5-material"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Phase5ProductionGraphContextTest {
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine");
    private static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.0.1-alpine")
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        try {
            VALKEY.start();
        } catch (RuntimeException exception) {
            POSTGRES.stop();
            throw exception;
        }
    }

    @AfterAll
    static void stopContainers() {
        try {
            VALKEY.stop();
        } finally {
            POSTGRES.stop();
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + VALKEY.getHost() + ":"
                + VALKEY.getMappedPort(6379));
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    RAGAnswerService answerService;

    @Test
    void explicitObjectStorageOptInAssemblesVersionedProductionPorts() {
        assertThat(answerService).isNotNull();
        assertThat(context.getBean(RAGAnswerService.class)).isSameAs(answerService);
        assertThat(context.getBean(QueryEmbeddingProvider.class)).isNotNull();
        assertThat(context.getBean(RetrievalPort.class)).isNotNull();
        assertThat(context.getBean(RagPromptPort.class)).isNotNull();
        assertThat(context.getBean(GenerationPort.class)).isNotNull();
        assertThat(context.getBean(SpaceAuthorizer.class))
                .isInstanceOf(SessionSpaceAnswerAuthorizer.class);
        assertThat(context.getBean(RevisionArtifactMaterialService.class)).isNotNull();
        assertThat(context.getBean(RevisionArtifactMaterialConfiguration.class)).isNotNull();

        assertThat(context.containsBean("phase5RealAnswerService")).isTrue();
        assertThat(context.containsBean("phase5AnswerService")).isFalse();
        assertThat(context.containsBean("phase5RetrievalVersionIdentities")).isTrue();
    }
}
