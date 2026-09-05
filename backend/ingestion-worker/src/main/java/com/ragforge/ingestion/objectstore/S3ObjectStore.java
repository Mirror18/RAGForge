package com.ragforge.ingestion.objectstore;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;

public final class S3ObjectStore implements ContentAddressedObjectStore, AutoCloseable {
    private final MinioClient client;
    private final String bucket;
    private final String prefix;
    private final ObjectStoreLimits limits;

    public S3ObjectStore(MinioClient client, String bucket, String prefix, ObjectStoreLimits limits) {
        if (client == null || bucket == null || bucket.isBlank() || prefix == null || prefix.contains("..")) {
            throw new ObjectStoreException(ObjectStoreFailure.OBJECT_STORE_UNAVAILABLE, "S3 configuration is invalid");
        }
        this.client = client;
        this.bucket = bucket;
        this.prefix = prefix.replaceAll("/+$", "");
        this.limits = limits;
    }

    @Override
    public StoredObject put(ObjectKey key, String mediaType, byte[] content) {
        ObjectStoreValidator.validate(key, mediaType, content, limits);
        String objectKey = objectKey(key);
        try {
            if (exists(key)) {
                StoredObject existing = get(key);
                if (!existing.mediaType().equalsIgnoreCase(mediaType)) {
                    throw new ObjectStoreException(ObjectStoreFailure.MIME_NOT_ALLOWED,
                            "immutable object already exists with a different media type");
                }
                return existing;
            }
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey)
                    .contentType(mediaType)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .build());
            return new StoredObject(key, mediaType, content.length, key.contentHash().toLowerCase(), Instant.now(), content);
        } catch (ObjectStoreException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ObjectStoreException(ObjectStoreFailure.OBJECT_STORE_UNAVAILABLE, "object could not be stored", exception);
        }
    }

    @Override
    public StoredObject get(ObjectKey key) {
        try {
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey(key)).build());
            String mediaType = stat.contentType();
            if (mediaType == null || mediaType.isBlank()) {
                throw new ObjectStoreException(ObjectStoreFailure.MIME_NOT_ALLOWED, "stored object has no media type");
            }
            byte[] content;
            try (var stream = client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey(key)).build())) {
                content = stream.readAllBytes();
            }
            ObjectStoreValidator.validate(key, mediaType, content, limits);
            return new StoredObject(key, mediaType, content.length, key.contentHash().toLowerCase(),
                    stat.lastModified() == null ? Instant.now() : stat.lastModified().toInstant(), content);
        } catch (ObjectStoreException exception) {
            throw exception;
        } catch (io.minio.errors.ErrorResponseException exception) {
            if (exception.errorResponse().code().equals("NoSuchKey")
                    || exception.errorResponse().code().equals("NoSuchBucket")) {
                throw new ObjectStoreException(ObjectStoreFailure.OBJECT_NOT_FOUND, "object does not exist", exception);
            }
            throw new ObjectStoreException(ObjectStoreFailure.OBJECT_STORE_UNAVAILABLE, "object could not be read", exception);
        } catch (Exception exception) {
            throw new ObjectStoreException(ObjectStoreFailure.OBJECT_STORE_UNAVAILABLE, "object could not be read", exception);
        }
    }

    @Override
    public boolean exists(ObjectKey key) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey(key)).build());
            return true;
        } catch (io.minio.errors.ErrorResponseException exception) {
            if (exception.errorResponse().code().equals("NoSuchKey")
                    || exception.errorResponse().code().equals("NoSuchBucket")) return false;
            throw new ObjectStoreException(ObjectStoreFailure.OBJECT_STORE_UNAVAILABLE,
                    "object existence could not be checked", exception);
        } catch (Exception exception) {
            throw new ObjectStoreException(ObjectStoreFailure.OBJECT_STORE_UNAVAILABLE,
                    "object existence could not be checked", exception);
        }
    }

    @Override
    public void close() {
        // MinioClient owns an internal HTTP client and does not expose a close operation in this SDK version.
    }

    private String objectKey(ObjectKey key) {
        return prefix.isBlank() ? key.value() : prefix + "/" + key.value();
    }
}
