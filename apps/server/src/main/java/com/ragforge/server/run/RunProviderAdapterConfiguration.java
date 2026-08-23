package com.ragforge.server.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.provider.adapter.CredentialResolver;
import com.ragforge.server.provider.ProviderRepository;
import com.ragforge.server.provider.adapter.EgressClass;
import com.ragforge.server.provider.adapter.OllamaProviderAdapter;
import com.ragforge.server.provider.adapter.MiMoProviderAdapter;
import com.ragforge.server.provider.adapter.OpenAiCompatibleProviderAdapter;
import com.ragforge.server.provider.adapter.ProviderAdapterException;
import com.ragforge.server.provider.adapter.ProviderConnection;
import com.ragforge.server.provider.adapter.ProviderErrorClass;
import com.ragforge.server.provider.adapter.ProviderType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;
import java.util.regex.Pattern;

/** Production adapter wiring. Credential resolution remains an explicit deployment seam. */
@Configuration
public class RunProviderAdapterConfiguration {
    private static final Pattern ENV_REFERENCE = Pattern.compile("env:([A-Z][A-Z0-9_]{1,127})");

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
                if (connection.providerType() == ProviderType.MIMO) {
                    return resolveEnvironmentCredential(connection.credentialRef());
                }
                throw notConfigured();
            }
        };
    }

    private static String resolveEnvironmentCredential(String credentialRef) {
        var match = credentialRef == null ? null : ENV_REFERENCE.matcher(credentialRef.trim());
        if (match == null || !match.matches()) {
            throw new ProviderAdapterException(ProviderErrorClass.AUTHENTICATION,
                    "MiMo credentialRef must use an env:NAME reference");
        }
        String value = System.getenv(match.group(1));
        if (value == null || value.isBlank()) {
            throw new ProviderAdapterException(ProviderErrorClass.AUTHENTICATION,
                    "MiMo credential environment variable is not configured");
        }
        return value;
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

    @Bean
    MiMoProviderAdapter miMoProviderAdapter(ObjectMapper objectMapper, CredentialResolver credentials) {
        return new MiMoProviderAdapter(objectMapper, credentials);
    }
}
