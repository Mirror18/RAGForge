package com.ragforge.server.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.server.provider.adapter.CredentialResolver;
import com.ragforge.server.provider.adapter.OllamaProviderAdapter;
import com.ragforge.server.provider.adapter.OpenAiCompatibleProviderAdapter;
import com.ragforge.server.provider.adapter.ProviderAdapterException;
import com.ragforge.server.provider.adapter.ProviderErrorClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Production adapter wiring. Credential resolution remains an explicit deployment seam. */
@Configuration
public class RunProviderAdapterConfiguration {
    @Bean
    @ConditionalOnMissingBean
    CredentialResolver credentialResolver() {
        return (spaceId, credentialRef) -> {
            throw new ProviderAdapterException(ProviderErrorClass.AUTHENTICATION,
                    "Provider credential resolution is not configured");
        };
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
