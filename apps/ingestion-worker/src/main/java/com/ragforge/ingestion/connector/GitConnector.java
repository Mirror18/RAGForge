package com.ragforge.ingestion.connector;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class GitConnector extends AbstractReadOnlyConnector {
    private final Path repository;

    public GitConnector(UUID spaceId, UUID sourceId, Path repository) {
        super(spaceId, sourceId);
        this.repository = repository.toAbsolutePath().normalize();
        if (!Files.isDirectory(this.repository) || Files.isSymbolicLink(this.repository)) {
            throw new ConnectorException(ConnectorFailure.SOURCE_UNAVAILABLE, "git repository is unavailable or symbolic");
        }
        GitCommand.run(this.repository, List.of("rev-parse", "--is-inside-work-tree"));
    }

    @Override
    protected List<ScannedObject> scan(DiscoveryRules rules) {
        String commit = head();
        String provenance = "git:commit=" + commit + ";branch=" + branch();
        byte[] tree = GitCommand.run(repository, List.of("ls-tree", "-r", "-z", "--full-tree", commit));
        List<ScannedObject> result = new ArrayList<>();
        for (byte[] record : splitNull(tree)) {
            String line = new String(record, StandardCharsets.UTF_8);
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                throw new ConnectorException(ConnectorFailure.SOURCE_UNAVAILABLE, "git tree record is invalid");
            }
            String header = line.substring(0, tab);
            String path = CanonicalPath.normalize(line.substring(tab + 1));
            String[] fields = header.split(" ");
            if (fields.length != 3 || !fields[1].equals("blob")) {
                continue;
            }
            if (fields[0].equals("120000")) {
                throw new ConnectorException(ConnectorFailure.PATH_INVALID, "git symbolic links are not accepted");
            }
            if (!GlobRules.included(path, rules) || !GlobRules.supported(path, rules)) {
                continue;
            }
            byte[] content = GitCommand.run(repository, List.of("show", commit + ":" + path));
            if (content.length > rules.maxObjectBytes()) {
                throw new ConnectorException(ConnectorFailure.CONTENT_TOO_LARGE, "git object exceeds configured limit");
            }
            result.add(new ScannedObject(path, content, content.length, mediaType(path), Instant.EPOCH, provenance,
                    ConnectorIdentity.stableObjectId(spaceId, sourceId, path)));
        }
        result.sort(Comparator.comparing(ScannedObject::canonicalPath));
        return result;
    }

    @Override
    protected String sourceVersionFor(List<ScannedObject> ignored) {
        return head();
    }

    @Override
    protected FetchedContent fetchCurrent(SourceReference sourceRef, String expectedVersion, long maxObjectBytes)
            throws IOException {
        String commit = head();
        if (!expectedVersion.equals(commit)) {
            throw new ConnectorException(ConnectorFailure.VERSION_MISMATCH, "git HEAD changed after discovery");
        }
        List<ScannedObject> current = scan(DiscoveryRules.defaults());
        ScannedObject object = current.stream().filter(value -> value.canonicalPath().equals(sourceRef.canonicalPath()))
                .findFirst().orElseThrow(() -> new ConnectorException(ConnectorFailure.OBJECT_NOT_FOUND, "git object not found"));
        String hash = ConnectorIdentity.sha256(object.content());
        if (!sourceRef.contentHash().equals(hash)) {
            throw new ConnectorException(ConnectorFailure.VERSION_MISMATCH, "git object changed after discovery");
        }
        return new FetchedContent(new ByteArrayInputStream(object.content()),
                new SourceMetadata(object.mediaType(), object.byteLength(), object.lastModified(), commit, hash,
                        object.provenance()), maxObjectBytes);
    }

    private String head() {
        return new String(GitCommand.run(repository, List.of("rev-parse", "HEAD")), StandardCharsets.UTF_8).trim();
    }

    private String branch() {
        String value = new String(GitCommand.run(repository, List.of("symbolic-ref", "--short", "-q", "HEAD")),
                StandardCharsets.UTF_8).trim();
        return value.isBlank() ? "DETACHED" : value;
    }

    private static List<byte[]> splitNull(byte[] bytes) {
        List<byte[]> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                if (i > start) {
                    result.add(java.util.Arrays.copyOfRange(bytes, start, i));
                }
                start = i + 1;
            }
        }
        if (start < bytes.length) {
            result.add(java.util.Arrays.copyOfRange(bytes, start, bytes.length));
        }
        return result;
    }

    private static String mediaType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return "application/octet-stream";
    }
}
