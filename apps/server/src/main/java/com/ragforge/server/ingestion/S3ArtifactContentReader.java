package com.ragforge.server.ingestion;

import com.ragforge.server.provider.adapter.CancellationToken;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.UUID;

/**
 * S3-compatible reader for immutable artifacts. The database value is treated
 * as an opaque object key; it is never followed as an arbitrary URL.
 */
public final class S3ArtifactContentReader implements ArtifactContentReader {
    private final MinioClient client;
    private final String bucket;
    private final String prefix;
    private final long maxBytes;

    public S3ArtifactContentReader(MinioClient client, String bucket, String prefix, long maxBytes) {
        if (client == null || bucket == null || bucket.isBlank() || prefix == null
                || prefix.contains("..") || maxBytes <= 0 || maxBytes > 50_000_000) {
            throw new IllegalArgumentException("invalid artifact storage configuration");
        }
        this.client = client;
        this.bucket = bucket;
        this.prefix = prefix.replaceAll("/+$", "");
        this.maxBytes = maxBytes;
    }

    @Override
    public byte[] read(UUID spaceId, String storageUri, String expectedSha256,
                       long expectedByteLength, CancellationToken cancellationToken) {
        if (spaceId == null || storageUri == null || storageUri.isBlank()
                || expectedSha256 == null || !expectedSha256.matches("[0-9a-fA-F]{64}")
                || expectedByteLength < 0 || expectedByteLength > maxBytes
                || cancellationToken == null || cancellationToken.isCancellationRequested()) {
            throw new ArtifactContentReadException("artifact read request is invalid or cancelled");
        }
        String object = objectKey(spaceId, storageUri);
        try {
            var stat = client.statObject(StatObjectArgs.builder().bucket(bucket).object(object).build());
            if (stat.size() != expectedByteLength || stat.size() > maxBytes) {
                throw new ArtifactContentReadException("artifact size does not match its immutable metadata");
            }
            try (InputStream stream = client.getObject(GetObjectArgs.builder().bucket(bucket).object(object).build())) {
                byte[] bytes = readBounded(stream, expectedByteLength, cancellationToken);
                String actual = sha256(bytes);
                if (!actual.equalsIgnoreCase(expectedSha256)) {
                    throw new ArtifactContentReadException("artifact hash does not match its immutable metadata");
                }
                return bytes;
            }
        } catch (ArtifactContentReadException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ArtifactContentReadException("artifact storage is unavailable", exception);
        }
    }

    private String objectKey(UUID spaceId, String storageUri) {
        String value = storageUri;
        if (storageUri.startsWith("s3://")) {
            try {
                URI uri = URI.create(storageUri);
                if (!bucket.equals(uri.getHost()) || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                    throw new ArtifactContentReadException("artifact storage reference is not allowed");
                }
                value = uri.getPath();
            } catch (IllegalArgumentException exception) {
                throw new ArtifactContentReadException("artifact storage reference is invalid", exception);
            }
        }
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("..")
                || value.contains("\\") || value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)
                || !value.startsWith("spaces/" + spaceId + "/")) {
            throw new ArtifactContentReadException("artifact storage reference is not allowed");
        }
        return prefix.isBlank() ? value : prefix + "/" + value;
    }

    private byte[] readBounded(InputStream stream, long expectedLength, CancellationToken cancellationToken)
            throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(expectedLength, 8192));
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            if (cancellationToken.isCancellationRequested()) {
                throw new ArtifactContentReadException("artifact read was cancelled");
            }
            total += read;
            if (total > maxBytes || total > expectedLength) {
                throw new ArtifactContentReadException("artifact exceeds its immutable size limit");
            }
            output.write(buffer, 0, read);
        }
        if (total != expectedLength) {
            throw new ArtifactContentReadException("artifact byte length is incomplete");
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] content) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(content);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the runtime", impossible);
        }
    }
}
