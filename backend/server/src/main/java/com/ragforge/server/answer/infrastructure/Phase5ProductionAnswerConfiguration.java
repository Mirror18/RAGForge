package com.ragforge.server.answer.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.answer.application.AnswerPersistencePort;
import com.ragforge.server.answer.application.AnswerProvenancePort;
import com.ragforge.server.answer.application.GenerationAuditPort;
import com.ragforge.server.answer.application.GenerationPort;
import com.ragforge.server.answer.application.QueryEmbeddingProvider;
import com.ragforge.server.answer.application.RAGAnswerService;
import com.ragforge.server.answer.application.RagPromptPort;
import com.ragforge.server.answer.application.RetrievalPort;
import com.ragforge.server.answer.application.SpaceAuthorizer;
import com.ragforge.server.answer.application.V11RunProvenanceRecorder;
import com.ragforge.server.knowledge.infrastructure.IndexRepository;
import com.ragforge.server.answer.infrastructure.JdbcRagPromptTemplateResolver;
import com.ragforge.server.answer.infrastructure.PromptRepository;
import com.ragforge.server.space.infrastructure.ProviderRepository;
import com.ragforge.server.space.domain.SpaceAuthorization;
import com.ragforge.server.space.infrastructure.SpaceBindingRepository;
import com.ragforge.server.knowledge.infrastructure.RetrievalProfileRepository;
import com.ragforge.server.knowledge.application.RetrievalExecutionSnapshotService;
import com.ragforge.server.knowledge.application.RetrievalService;
import com.ragforge.server.answer.infrastructure.ProviderAdapterRegistry;
import com.ragforge.server.answer.infrastructure.RunRepository;
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
    GenerationAuditPort phase5GenerationAudit(RunRepository runs, ProviderRepository providers,
                                              SpaceBindingRepository bindings) {
        return new JdbcGenerationAuditPort(runs,
                new RepositoryProviderRouteResolver(providers, bindings, Phase5IntegrationObserver.noop()));
    }

    @Bean
    RetrievalPort phase5Retrieval(RetrievalService retrieval, RetrievalExecutionResolver executions,
                                  RevisionArtifactMaterialService materials,
                                  RetrievalExecutionSnapshotService snapshots) {
        return Phase5IntegrationConfiguration.revisionArtifactRetrieval(retrieval, executions, materials,
                Phase5IntegrationObserver.noop(), snapshots);
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
                                             AnswerPersistencePort persistence, AnswerProvenancePort provenance,
                                             GenerationAuditPort generationAudit) {
        return new RAGAnswerService(authorizer, embedding, retrieval, prompt, generation, persistence,
                provenance, generationAudit);
    }
}
