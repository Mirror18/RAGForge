package com.ragforge.server.answer.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.answer.AnswerPersistencePort;
import com.ragforge.server.answer.AnswerProvenancePort;
import com.ragforge.server.answer.GenerationPort;
import com.ragforge.server.answer.QueryEmbeddingProvider;
import com.ragforge.server.answer.RAGAnswerService;
import com.ragforge.server.answer.RagPromptPort;
import com.ragforge.server.answer.RetrievalPort;
import com.ragforge.server.answer.SpaceAuthorizer;
import com.ragforge.server.answer.V11RunProvenanceRecorder;
import com.ragforge.server.index.IndexRepository;
import com.ragforge.server.prompt.JdbcRagPromptTemplateResolver;
import com.ragforge.server.prompt.PromptRepository;
import com.ragforge.server.provider.ProviderRepository;
import com.ragforge.server.provider.SpaceAuthorization;
import com.ragforge.server.provider.SpaceBindingRepository;
import com.ragforge.server.retrieval.RetrievalProfileRepository;
import com.ragforge.server.retrieval.RetrievalService;
import com.ragforge.server.run.ProviderAdapterRegistry;
import com.ragforge.server.run.RunRepository;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Opt-in production graph. The object-storage flag is the explicit material
 * capability gate; missing active routes still produce provider-safe refusal.
 */
@Configuration
@ConditionalOnProperty(prefix = "ragforge.object-storage", name = "enabled", havingValue = "true")
public class Phase5ProductionAnswerConfiguration {
    @Bean
    ActiveRetrievalExecutionResolver.VersionIdentityProvider phase5RetrievalVersionIdentities(
            IndexRepository indexes) {
        return new DeterministicRetrievalVersionIdentityProvider(indexes);
    }

    @Bean
    RetrievalExecutionResolver phase5RetrievalExecutions(
            JdbcTemplate jdbc, IndexRepository indexes, RetrievalProfileRepository profiles,
            ActiveRetrievalExecutionResolver.VersionIdentityProvider identities) {
        return Phase5IntegrationConfiguration.activeRetrieval(jdbc, indexes, profiles, identities,
                Phase5IntegrationObserver.noop());
    }

    @Bean
    QueryEmbeddingProvider phase5QueryEmbedding(ProviderRepository providers, SpaceBindingRepository bindings,
                                                ProviderAdapterRegistry adapters) {
        return Phase5IntegrationConfiguration.providerEmbedding(providers, bindings, adapters,
                Phase5IntegrationObserver.noop());
    }

    @Bean
    GenerationPort phase5Generation(ProviderRepository providers, SpaceBindingRepository bindings,
                                    ProviderAdapterRegistry adapters, ObjectMapper objectMapper) {
        return Phase5IntegrationConfiguration.providerGeneration(providers, bindings, adapters, objectMapper,
                Phase5IntegrationObserver.noop());
    }

    @Bean
    RetrievalPort phase5Retrieval(RetrievalService retrieval, RetrievalExecutionResolver executions,
                                  RevisionArtifactMaterialService materials) {
        return Phase5IntegrationConfiguration.revisionArtifactRetrieval(retrieval, executions, materials,
                Phase5IntegrationObserver.noop());
    }

    @Bean
    RagPromptPort phase5Prompt(PromptRepository prompts) {
        return Phase5IntegrationConfiguration.versionedPrompt(prompts, new JdbcRagPromptTemplateResolver(prompts));
    }

    @Bean
    SpaceAuthorizer phase5SpaceAuthorizer(SpaceAuthorization authorization, RunRepository runs) {
        return new SessionSpaceAnswerAuthorizer(authorization, runs, Clock.systemUTC());
    }

    @Bean
    AnswerProvenancePort phase5AnswerProvenancePort(RunRepository runs) {
        return new V11RunProvenanceRecorder(runs);
    }

    @Bean
    RAGAnswerService phase5RealAnswerService(SpaceAuthorizer authorizer, QueryEmbeddingProvider embedding,
                                             RetrievalPort retrieval, RagPromptPort prompt, GenerationPort generation,
                                             AnswerPersistencePort persistence, AnswerProvenancePort provenance) {
        return new RAGAnswerService(authorizer, embedding, retrieval, prompt, generation, persistence, provenance);
    }
}
