package com.ragforge.server.answer.infrastructure;

import com.ragforge.server.answer.application.AnswerPersistencePort;
import com.ragforge.server.answer.application.AnswerProvenancePort;
import com.ragforge.server.answer.application.RAGAnswerService;
import com.ragforge.server.answer.application.V11RunProvenanceRecorder;
import com.ragforge.server.answer.infrastructure.RunRepository;
import com.ragforge.server.space.domain.SpaceAuthorization;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Installs the production-safe Phase 5 graph. Providers are deliberately
 * fail-closed until a real embedding/retrieval/prompt/generation route is
 * configured; refusal and audit records still use durable storage.
 */
@Configuration
@ConditionalOnProperty(prefix = "ragforge.object-storage", name = "enabled", havingValue = "false", matchIfMissing = true)
public class Phase5SpringAnswerConfiguration {
    @Bean
    public AnswerProvenancePort phase5AnswerProvenancePort(RunRepository runs) {
        return new V11RunProvenanceRecorder(runs);
    }

    @Bean
    public RAGAnswerService phase5AnswerService(AnswerPersistencePort persistence,
                                    AnswerProvenancePort provenance, SpaceAuthorization authorization,
                                    RunRepository runs) {
        return Phase5IntegrationConfiguration.failClosed(persistence, provenance,
                Phase5IntegrationObserver.noop(),
                new SessionSpaceAnswerAuthorizer(authorization, runs, Clock.systemUTC())).answerService();
    }
}
