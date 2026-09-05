package com.ragforge.ingestion.connector;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public final class FileConnector extends AbstractReadOnlyConnector {
    private final Path file;

    public FileConnector(UUID spaceId, UUID sourceId, Path file) {
        super(spaceId, sourceId);
        this.file = file.toAbsolutePath().normalize();
        if (!java.nio.file.Files.isRegularFile(this.file) || java.nio.file.Files.isSymbolicLink(this.file)) {
            throw new ConnectorException(ConnectorFailure.SOURCE_UNAVAILABLE, "file source is unavailable or symbolic");
        }
    }

    @Override
    protected List<ScannedObject> scan(DiscoveryRules rules) {
        return FileSystemScanner.scan(file, true, spaceId, sourceId, rules);
    }

    @Override
    protected FetchedContent fetchCurrent(SourceReference sourceRef, String expectedVersion, long maxObjectBytes)
            throws IOException {
        List<ScannedObject> current = scan(DiscoveryRules.defaults());
        String currentVersion = sourceVersionFor(current);
        if (!expectedVersion.equals(currentVersion)) {
            throw new ConnectorException(ConnectorFailure.VERSION_MISMATCH, "file changed after discovery");
        }
        ScannedObject object = current.stream().filter(value -> value.canonicalPath().equals(sourceRef.canonicalPath()))
                .findFirst().orElseThrow(() -> new ConnectorException(ConnectorFailure.OBJECT_NOT_FOUND, "source object not found"));
        if (!sourceRef.contentHash().equals(ConnectorIdentity.sha256(object.content()))) {
            throw new ConnectorException(ConnectorFailure.VERSION_MISMATCH, "file changed after discovery");
        }
        return new FetchedContent(new ByteArrayInputStream(object.content()),
                new SourceMetadata(object.mediaType(), object.byteLength(), object.lastModified(), currentVersion,
                        ConnectorIdentity.sha256(object.content()), object.provenance()), maxObjectBytes);
    }
}
