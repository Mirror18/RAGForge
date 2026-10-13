package com.ragforge.server.answer.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.space.infrastructure.CredentialResolver;
import com.ragforge.server.space.infrastructure.ProviderRepository;
import com.ragforge.server.space.infrastructure.MiMoProviderAdapter;
import com.ragforge.server.space.infrastructure.OpenAiCompatibleProviderAdapter;
import com.ragforge.server.space.infrastructure.ProviderAdapterException;
import com.ragforge.server.space.infrastructure.ProviderConnection;
import com.ragforge.server.space.infrastructure.ProviderErrorClass;
import com.ragforge.server.space.infrastructure.ProviderType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

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
                if (connection.providerType() == ProviderType.MIMO
                        || connection.providerType() == ProviderType.OPENAI_COMPATIBLE) {
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
                    "Cloud credentialRef must use an env:NAME reference");
        }
        String value = System.getenv(match.group(1));
        if (value == null || value.isBlank()) {
            throw new ProviderAdapterException(ProviderErrorClass.AUTHENTICATION,
                    "Cloud credential environment variable is not configured");
        }
        return value;
    }

    private static ProviderAdapterException notConfigured() {
        return new ProviderAdapterException(ProviderErrorClass.AUTHENTICATION,
                "Provider credential resolution is not configured");
    }

    @Bean
    @Profile("!test")
    OpenAiCompatibleProviderAdapter openAiCompatibleProviderAdapter(ObjectMapper objectMapper,
                                                                     CredentialResolver credentials) {
        return new OpenAiCompatibleProviderAdapter(objectMapper, credentials);
    }

    @Bean
    @Profile("!test")
    MiMoProviderAdapter miMoProviderAdapter(ObjectMapper objectMapper, CredentialResolver credentials) {
        return new MiMoProviderAdapter(objectMapper, credentials);
    }
}
