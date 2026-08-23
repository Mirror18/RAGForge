package com.ragforge.ingestion.config;

import com.ragforge.ingestion.objectstore.ContentAddressedObjectStore;
import com.ragforge.ingestion.objectstore.ObjectStoreLimits;
import com.ragforge.ingestion.objectstore.S3ObjectStore;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "ragforge.ingestion.enabled", havingValue = "true")
public class WorkerObjectStorageConfiguration {
    @Bean
    MinioClient workerMinioClient(@Value("${ragforge.object-storage.endpoint:http://localhost:9000}") String endpoint,
                                  @Value("${ragforge.object-storage.access-key:}") String accessKey,
                                  @Value("${ragforge.object-storage.secret-key:}") String secretKey) {
        if (endpoint.isBlank() || accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("worker object storage credentials are required");
        }
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }

    @Bean
    ContentAddressedObjectStore workerObjectStore(
            MinioClient client,
            @Value("${ragforge.object-storage.bucket:ragforge}") String bucket,
            @Value("${ragforge.object-storage.prefix:phase3}") String prefix) {
        return new S3ObjectStore(client, bucket, prefix, ObjectStoreLimits.defaults());
    }
}
