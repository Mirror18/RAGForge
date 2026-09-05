package com.ragforge.ingestion.objectstore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class LocalObjectStore implements ContentAddressedObjectStore {
    private final Path root;
    private final ObjectStoreLimits limits;
    private final Object writeLock = new Object();

    public LocalObjectStore(Path root, ObjectStoreLimits limits) {
        this.root = root.toAbsolutePath().normalize();
        this.limits = limits;
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw new ObjectStoreException(ObjectStoreFailure.OBJECT_STORE_UNAVAILABLE, "object store root is unavailable", exception);
        }
    }

    @Override
    public StoredObject put(ObjectKey key, String mediaType, byte[] content) {
        ObjectStoreValidator.validate(key, mediaType, content, limits);
        synchronized (writeLock) {
            Path target = resolve(key);
            Path metadata = target.resolveSibling(target.getFileName() + ".meta");
            Path temporary = null;
            boolean claimedTarget = false;
            try {
                if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    StoredObject existing = get(key);
                    if (!existing.mediaType().equalsIgnoreCase(mediaType)) {
                        throw new ObjectStoreException(ObjectStoreFailure.MIME_NOT_ALLOWED,
                                "immutable object already exists with a different media type");
                    }
                    return existing;
                }
                Files.createDirectories(target.getParent());
                temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
                Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    claimedTarget = true;
                } catch (FileAlreadyExistsException race) {
                    Files.deleteIfExists(temporary);
                    StoredObject existing = get(key);
                    if (!existing.mediaType().equalsIgnoreCase(mediaType)) {
                        throw new ObjectStoreException(ObjectStoreFailure.MIME_NOT_ALLOWED,
                                "immutable object already exists with a different media type");
                    }
                    return existing;
                }
                Files.writeString(metadata, mediaType, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return new StoredObject(key, mediaType, content.length, key.contentHash().toLowerCase(), Instant.now(), content);
            } catch (IOException exception) {
                try {
                    if (temporary != null) {
                        Files.deleteIfExists(temporary);
                    }
                    if (claimedTarget) {
                        Files.deleteIfExists(target);
                        Files.deleteIfExists(metadata);
                    }
                } catch (IOException ignored) {
                    // Best-effort cleanup preserves the original storage failure.
                }
                throw new ObjectStoreException(ObjectStoreFailure.OBJECT_STORE_UNAVAILABLE, "object could not be stored", exception);
            }
        }
    }

    @Override
    public StoredObject get(ObjectKey key) {
        Path target = resolve(key);
        try {
            if (!Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new ObjectStoreException(ObjectStoreFailure.OBJECT_NOT_FOUND, "object does not exist");
            }
            byte[] content = Files.readAllBytes(target);
            String mediaType = Files.readString(target.resolveSibling(target.getFileName() + ".meta"));
            ObjectStoreValidator.validate(key, mediaType, content, limits);
            return new StoredObject(key, mediaType, content.length, key.contentHash().toLowerCase(),
                    Instant.ofEpochMilli(Files.getLastModifiedTime(target).toMillis()), content);
        } catch (ObjectStoreException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ObjectStoreException(ObjectStoreFailure.OBJECT_NOT_FOUND, "object could not be read", exception);
        }
    }

    @Override
    public boolean exists(ObjectKey key) {
        Path target = resolve(key);
        return Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    private Path resolve(ObjectKey key) {
        Path target = root.resolve(key.value().replace('/', root.getFileSystem().getSeparator().charAt(0))).normalize();
        if (!target.startsWith(root)) {
            throw new ObjectStoreException(ObjectStoreFailure.INVALID_KEY, "object key escapes store root");
        }
        return target;
    }
}
