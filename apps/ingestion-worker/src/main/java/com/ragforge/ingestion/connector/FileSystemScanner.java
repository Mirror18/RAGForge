package com.ragforge.ingestion.connector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

final class FileSystemScanner {
    private FileSystemScanner() { }

    static List<AbstractReadOnlyConnector.ScannedObject> scan(
            Path root, boolean singleFile, UUID spaceId, UUID sourceId, DiscoveryRules rules) {
        if (!Files.exists(root) || Files.isSymbolicLink(root)) {
            throw new ConnectorException(ConnectorFailure.SOURCE_UNAVAILABLE, "source root is unavailable or symbolic");
        }
        try {
            List<Path> paths = new ArrayList<>();
            if (singleFile) {
                if (!Files.isRegularFile(root)) {
                    throw new ConnectorException(ConnectorFailure.OBJECT_NOT_FOUND, "source file is not regular");
                }
                paths.add(root);
            } else {
                try (Stream<Path> stream = Files.walk(root)) {
                    stream.filter(path -> !path.equals(root)).forEach(path -> {
                        if (Files.isSymbolicLink(path)) {
                            throw new ConnectorException(ConnectorFailure.PATH_INVALID, "symbolic links are not accepted");
                        }
                        if (Files.isRegularFile(path)) {
                            paths.add(path);
                        }
                    });
                }
            }
            paths.sort(Comparator.comparing(path -> canonical(root, path, singleFile)));
            List<AbstractReadOnlyConnector.ScannedObject> result = new ArrayList<>();
            for (Path path : paths) {
                String canonicalPath = canonical(root, path, singleFile);
                if (!GlobRules.included(canonicalPath, rules) || !GlobRules.supported(canonicalPath, rules)) {
                    continue;
                }
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (attributes.size() > rules.maxObjectBytes()) {
                    throw new ConnectorException(ConnectorFailure.CONTENT_TOO_LARGE, "source object exceeds configured limit");
                }
                byte[] content = Files.readAllBytes(path);
                result.add(new AbstractReadOnlyConnector.ScannedObject(
                        canonicalPath, content, attributes.size(), mediaType(path),
                        Instant.ofEpochMilli(attributes.lastModifiedTime().toMillis()),
                        "local", ConnectorIdentity.stableObjectId(spaceId, sourceId, canonicalPath)));
            }
            return result;
        } catch (ConnectorException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ConnectorException(ConnectorFailure.SOURCE_UNAVAILABLE, "source could not be read", exception);
        }
    }

    static Path resolve(Path root, String canonicalPath, boolean singleFile) {
        CanonicalPath.require(canonicalPath);
        Path candidate = singleFile ? root : root.resolve(canonicalPath.replace('/', root.getFileSystem().getSeparator().charAt(0)));
        Path normalized = candidate.toAbsolutePath().normalize();
        Path absoluteRoot = root.toAbsolutePath().normalize();
        if ((!singleFile && !normalized.startsWith(absoluteRoot)) || (singleFile && !normalized.equals(absoluteRoot))) {
            throw new ConnectorException(ConnectorFailure.PATH_INVALID, "path escapes source root");
        }
        return normalized;
    }

    private static String canonical(Path root, Path path, boolean singleFile) {
        return singleFile ? CanonicalPath.normalize(path.getFileName().toString())
                : CanonicalPath.normalize(root.relativize(path).toString());
    }

    private static String mediaType(Path path) {
        String lower = path.getFileName().toString().toLowerCase();
        return switch (lower.substring(lower.lastIndexOf('.') + 1)) {
            case "md", "markdown" -> "text/markdown";
            case "txt", "text" -> "text/plain";
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> {
                try {
                    yield Files.probeContentType(path) == null ? "application/octet-stream" : Files.probeContentType(path);
                } catch (IOException ignored) {
                    yield "application/octet-stream";
                }
            }
        };
    }
}
