package com.ragforge.ingestion.objectstore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ObjectStoreTest {
    private static final UUID SPACE = UUID.fromString("018f0f00-0000-7000-8000-000000000021");
    private static final UUID SOURCE = UUID.fromString("018f0f00-0000-7000-8000-000000000022");
    private static final UUID REVISION = UUID.fromString("018f0f00-0000-7000-8000-000000000023");
    private static final UUID ARTIFACT = UUID.fromString("018f0f00-0000-7000-8000-000000000024");
    private static final ObjectStoreLimits LIMITS = new ObjectStoreLimits(1024, Set.of("text/plain", "application/pdf"));

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>("minio/minio:RELEASE.2024-12-18T13-15-44Z")
            .withEnv("MINIO_ROOT_USER", "ragforge")
            .withEnv("MINIO_ROOT_PASSWORD", "ragforge-secret")
            .withCommand("server /data")
            .withExposedPorts(9000);

    @Test
    void localStoreEnforcesSpaceKeyChecksumLimitsAndImmutability(@TempDir Path temp) {
        LocalObjectStore store = new LocalObjectStore(temp, LIMITS);
        byte[] content = "synthetic object".getBytes(StandardCharsets.UTF_8);
        ObjectKey key = key(content, SPACE);
        StoredObject saved = store.put(key, "text/plain", content);
        assertThat(saved.content()).containsExactly(content);
        assertThat(store.get(key).content()).containsExactly(content);
        assertThat(store.put(key, "text/plain", content).content()).containsExactly(content);
        assertThatThrownBy(() -> store.put(key, "application/pdf", content))
                .isInstanceOf(ObjectStoreException.class)
                .extracting("failure").isEqualTo(ObjectStoreFailure.MIME_NOT_ALLOWED);
        assertThatThrownBy(() -> store.put(new ObjectKey(SPACE, SOURCE, REVISION, ARTIFACT,
                "0000000000000000000000000000000000000000000000000000000000000000"), "text/plain", content))
                .isInstanceOf(ObjectStoreException.class)
                .extracting("failure").isEqualTo(ObjectStoreFailure.CHECKSUM_MISMATCH);
        assertThatThrownBy(() -> ObjectKey.parse(UUID.randomUUID(), key.value()))
                .isInstanceOf(ObjectStoreException.class)
                .extracting("failure").isEqualTo(ObjectStoreFailure.SPACE_MISMATCH);
    }

    @Test
    void localStoreConcurrentClaimsKeepOneImmutableObject(@TempDir Path temp) throws Exception {
        LocalObjectStore store = new LocalObjectStore(temp, LIMITS);
        byte[] content = "concurrent object".getBytes(StandardCharsets.UTF_8);
        ObjectKey key = key(content, SPACE);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            Set<Future<StoredObject>> results = new java.util.HashSet<>();
            for (int index = 0; index < 20; index++) {
                results.add(executor.submit(() -> store.put(key, "text/plain", content)));
            }
            for (Future<StoredObject> result : results) {
                assertThat(result.get().content()).containsExactly(content);
            }
            assertThat(Files.walk(temp).filter(Files::isRegularFile)
                    .filter(path -> !path.toString().endsWith(".meta")).count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void s3CompatibleStorePersistsContentAddressedObjectAndMetadata() throws Exception {
        String bucket = "ragforge-test-" + UUID.randomUUID().toString().substring(0, 8);
        URI endpoint = URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        MinioClient client = MinioClient.builder().endpoint(endpoint.toString())
                .credentials("ragforge", "ragforge-secret").build();
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            S3ObjectStore store = new S3ObjectStore(client, bucket, "phase3", LIMITS);
            byte[] content = "s3 synthetic object".getBytes(StandardCharsets.UTF_8);
            ObjectKey key = key(content, SPACE);
            store.put(key, "text/plain", content);
            assertThat(store.exists(key)).isTrue();
            assertThat(store.get(key).content()).containsExactly(content);
        } finally {
            storeClose(client);
        }
    }

    private static void storeClose(MinioClient client) {
        // MinioClient 8.2.1 has no public close method; the test container owns the endpoint lifecycle.
    }

    private static ObjectKey key(byte[] content, UUID spaceId) {
        return new ObjectKey(spaceId, SOURCE, REVISION, ARTIFACT, ObjectStoreValidator.sha256(content));
    }
}
