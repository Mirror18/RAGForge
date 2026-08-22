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
import com.ragforge.server.prompt.PromptRepository;
import com.ragforge.server.provider.ProviderRepository;
import com.ragforge.server.provider.SpaceBindingRepository;
import com.ragforge.server.run.ProviderAdapterRegistry;
import com.ragforge.server.retrieval.RetrievalService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/**
 * Explicit factory for the Phase 5 port graph. This class is deliberately not
 * a Spring {@code @Configuration}: component scanning must not silently turn
 * an incomplete deployment into a working-looking answer service.
 */
public final class Phase5IntegrationConfiguration {
    private Phase5IntegrationConfiguration() {
    }

    public record Ports(SpaceAuthorizer authorizer, QueryEmbeddingProvider embedding,
                        RetrievalPort retrieval, RagPromptPort prompt, GenerationPort generation,
                        AnswerPersistencePort persistence, AnswerProvenancePort provenance) {
        public Ports {
            Objects.requireNonNull(authorizer, "authorizer");
            Objects.requireNonNull(embedding, "embedding");
            Objects.requireNonNull(retrieval, "retrieval");
            Objects.requireNonNull(prompt, "prompt");
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(persistence, "persistence");
            Objects.requireNonNull(provenance, "provenance");
        }

        public RAGAnswerService answerService() {
            return new RAGAnswerService(authorizer, embedding, retrieval, prompt, generation,
                    persistence, provenance);
        }
    }

    public static Ports failClosed(AnswerPersistencePort persistence, AnswerProvenancePort provenance,
                                   Phase5IntegrationObserver observer) {
        return failClosed(persistence, provenance, observer, null);
    }

    public static Ports failClosed(AnswerPersistencePort persistence, AnswerProvenancePort provenance,
                                   Phase5IntegrationObserver observer, SpaceAuthorizer authorizer) {
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(provenance, "provenance");
        Phase5IntegrationObserver safeObserver = observer == null ? Phase5IntegrationObserver.noop() : observer;
        return new Ports(authorizer == null ? new FailClosedSpaceAuthorizer(safeObserver) : authorizer,
                new FailClosedQueryEmbeddingProvider(safeObserver), new FailClosedRetrievalPort(safeObserver),
                new FailClosedRagPromptPort(safeObserver), new FailClosedGenerationPort(safeObserver),
                persistence, provenance);
    }

    public static GenerationPort providerGeneration(ProviderRepository providers,
                                                     SpaceBindingRepository bindings,
                                                     ProviderAdapterRegistry adapters,
                                                     ObjectMapper objectMapper,
                                                     Phase5IntegrationObserver observer) {
        Phase5IntegrationObserver safeObserver = observer == null ? Phase5IntegrationObserver.noop() : observer;
        return new ProviderBackedGenerationPort(
                new RepositoryProviderRouteResolver(providers, bindings, safeObserver), adapters,
                objectMapper, java.time.Duration.ofSeconds(120), safeObserver);
    }

    public static QueryEmbeddingProvider providerEmbedding(ProviderRepository providers,
                                                            SpaceBindingRepository bindings,
                                                            ProviderAdapterRegistry adapters,
                                                            Phase5IntegrationObserver observer) {
        Phase5IntegrationObserver safeObserver = observer == null ? Phase5IntegrationObserver.noop() : observer;
        ProviderRouteResolver routes = new RepositoryProviderRouteResolver(providers, bindings, safeObserver);
        return new ProviderBackedQueryEmbeddingProvider(routes, adapters, java.time.Duration.ofSeconds(60), safeObserver);
    }

    public static RetrievalPort retrieval(RetrievalService service, RetrievalExecutionResolver executions,
                                          RetrievalServicePortAdapter.EvidenceMaterialResolver materials,
                                          Phase5IntegrationObserver observer) {
        return new RetrievalServicePortAdapter(service, executions, materials, observer);
    }

    public static RetrievalPort revisionArtifactRetrieval(RetrievalService service,
                                                          RetrievalExecutionResolver executions,
                                                          RevisionArtifactMaterialService materials,
                                                          Phase5IntegrationObserver observer) {
        return retrieval(service, executions, new RevisionArtifactMaterialResolver(materials), observer);
    }

    public static RagPromptPort versionedPrompt(PromptRepository prompts,
                                                com.ragforge.server.answer.V11RagPromptPort.TemplateResolver templates) {
        return new com.ragforge.server.answer.V11RagPromptPort(prompts, templates);
    }

    public static ActiveRetrievalExecutionResolver activeRetrieval(JdbcTemplate jdbc,
                                                                    com.ragforge.server.index.IndexRepository indexes,
                                                                    com.ragforge.server.retrieval.RetrievalProfileRepository profiles,
                                                                    ActiveRetrievalExecutionResolver.VersionIdentityProvider identities,
                                                                    Phase5IntegrationObserver observer) {
        return new ActiveRetrievalExecutionResolver(indexes, profiles,
                new JdbcRetrievalProfileVersionLoader(jdbc), identities, observer);
    }
}
