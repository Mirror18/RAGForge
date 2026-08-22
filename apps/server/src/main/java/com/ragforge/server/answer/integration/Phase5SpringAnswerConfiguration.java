package com.ragforge.server.answer.integration;

import com.ragforge.server.answer.AnswerPersistencePort;
import com.ragforge.server.answer.AnswerProvenancePort;
import com.ragforge.server.answer.RAGAnswerService;
import com.ragforge.server.answer.V11RunProvenanceRecorder;
import com.ragforge.server.run.RunRepository;
import com.ragforge.server.provider.SpaceAuthorization;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Installs the production-safe Phase 5 graph. Providers are deliberately
 * fail-closed until a real embedding/retrieval/prompt/generation route is
 * configured; refusal and audit records still use durable storage.
 */
@Configuration
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
