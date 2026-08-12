package com.ragforge.server.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.provider.adapter.CredentialResolver;
import com.ragforge.server.provider.ProviderRepository;
import com.ragforge.server.provider.adapter.EgressClass;
import com.ragforge.server.provider.adapter.OllamaProviderAdapter;
import com.ragforge.server.provider.adapter.OpenAiCompatibleProviderAdapter;
import com.ragforge.server.provider.adapter.ProviderAdapterException;
import com.ragforge.server.provider.adapter.ProviderConnection;
import com.ragforge.server.provider.adapter.ProviderErrorClass;
import com.ragforge.server.provider.adapter.ProviderType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/** Production adapter wiring. Credential resolution remains an explicit deployment seam. */
@Configuration
public class RunProviderAdapterConfiguration {
    @Bean
    @ConditionalOnMissingBean
    CredentialResolver credentialResolver(ProviderRepository providers) {
        return new CredentialResolver() {
            @Override
            public String resolveAuthorization(UUID spaceId, String credentialRef) {
                throw notConfigured();
            }

            @Override
            public String resolveAuthorization(ProviderConnection connection) {
                if (isPersistedLocalOllamaWithoutAuth(connection, providers)) {
                    return null;
                }
                throw notConfigured();
            }
        };
    }

    private static boolean isPersistedLocalOllamaWithoutAuth(ProviderConnection connection,
                                                              ProviderRepository providers) {
        if (connection.providerType() != ProviderType.OLLAMA
                || connection.egressClass() != EgressClass.LOCAL) {
            return false;
        }
        if (connection.isExplicitLocalNoAuth()) {
            return true;
        }
        return providers.findConnection(connection.spaceId(), connection.providerConnectionId())
                .filter(persisted -> connection.spaceId().equals(persisted.spaceId())
                        && persisted.providerType() == ProviderRepository.ProviderType.OLLAMA
                        && persisted.egressPolicy() == ProviderRepository.EgressPolicy.LOCAL_ONLY
                        && "NONE".equalsIgnoreCase(persisted.authScheme()))
                .isPresent();
    }

    private static ProviderAdapterException notConfigured() {
        return new ProviderAdapterException(ProviderErrorClass.AUTHENTICATION,
                "Provider credential resolution is not configured");
    }

    @Bean
    OllamaProviderAdapter ollamaProviderAdapter(ObjectMapper objectMapper, CredentialResolver credentials) {
        return new OllamaProviderAdapter(objectMapper, credentials);
    }

    @Bean
    OpenAiCompatibleProviderAdapter openAiCompatibleProviderAdapter(ObjectMapper objectMapper,
                                                                     CredentialResolver credentials) {
        return new OpenAiCompatibleProviderAdapter(objectMapper, credentials);
    }
}
