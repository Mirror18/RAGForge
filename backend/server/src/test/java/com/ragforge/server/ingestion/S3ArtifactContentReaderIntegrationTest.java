package com.ragforge.server.ingestion;

import com.ragforge.server.provider.adapter.CancellationToken;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class S3ArtifactContentReaderIntegrationTest {
    private static final UUID SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");
    private static final String BUCKET = "ragforge";
    private static final String PREFIX = "phase5-material";
    private static final String OBJECT = "spaces/" + SPACE + "/artifacts/text";
    private static final byte[] CONTENT = "MinIO material integration fixture".getBytes(StandardCharsets.UTF_8);

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(
            "minio/minio:RELEASE.2024-12-18T13-15-44Z")
            .withEnv("MINIO_ROOT_USER", "ragforge")
            .withEnv("MINIO_ROOT_PASSWORD", "ragforge-secret")
            .withCommand("server /data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000)
                    .forStatusCode(200).withStartupTimeout(Duration.ofSeconds(60)));

    private static MinioClient client;

    @BeforeAll
    static void setUp() throws Exception {
        URI endpoint = URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        client = MinioClient.builder().endpoint(endpoint.toString())
                .credentials("ragforge", "ragforge-secret").build();
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
        client.putObject(PutObjectArgs.builder().bucket(BUCKET).object(PREFIX + "/" + OBJECT)
                .contentType("text/plain")
                .stream(new ByteArrayInputStream(CONTENT), CONTENT.length, -1).build());
    }

    @Test
    void readsAndVerifiesContentAddressedObject() {
        var reader = new S3ArtifactContentReader(client, BUCKET, PREFIX, 1_000_000);

        byte[] actual = reader.read(SPACE, OBJECT, sha256(CONTENT), CONTENT.length, new CancellationToken());

        assertThat(actual).isEqualTo(CONTENT);
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
