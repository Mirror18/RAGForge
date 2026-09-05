package com.ragforge.server.ingestion;

import com.ragforge.server.answer.integration.RevisionArtifactMaterialService;
import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Opt-in server-side material reader; disabled deployments remain fail-closed. */
@Configuration
public class RevisionArtifactMaterialConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "ragforge.object-storage", name = "enabled", havingValue = "true")
    MinioClient revisionArtifactMinioClient(
            @org.springframework.beans.factory.annotation.Value("${ragforge.object-storage.endpoint}") String endpoint,
            @org.springframework.beans.factory.annotation.Value("${ragforge.object-storage.access-key}") String accessKey,
            @org.springframework.beans.factory.annotation.Value("${ragforge.object-storage.secret-key}") String secretKey) {
        if (endpoint.isBlank() || accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("object storage credentials are required when material reading is enabled");
        }
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "ragforge.object-storage", name = "enabled", havingValue = "true")
    ArtifactContentReader revisionArtifactContentReader(
            MinioClient client,
            @org.springframework.beans.factory.annotation.Value("${ragforge.object-storage.bucket}") String bucket,
            @org.springframework.beans.factory.annotation.Value("${ragforge.object-storage.prefix:}") String prefix,
            @org.springframework.beans.factory.annotation.Value("${ragforge.object-storage.max-artifact-bytes:50000000}") long maxBytes) {
        return new S3ArtifactContentReader(client, bucket, prefix, maxBytes);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ragforge.object-storage", name = "enabled", havingValue = "true")
    RevisionArtifactMaterialService revisionArtifactMaterialService(JdbcTemplate jdbc, ArtifactContentReader reader) {
        return new JdbcRevisionArtifactMaterialService(jdbc, reader);
    }
}
