package com.ragforge.ingestion.connector;

import com.ragforge.ingestion.objectstore.LocalObjectStore;
import com.ragforge.ingestion.objectstore.ObjectStoreException;
import com.ragforge.ingestion.objectstore.ObjectStoreLimits;
import com.ragforge.ingestion.parser.NativeDocumentParser;
import com.ragforge.ingestion.parser.ParseRequest;
import com.ragforge.ingestion.parser.ParseStatus;
import com.ragforge.ingestion.parser.ParsedDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies that parser/OCR and object failures cannot advance a source checkpoint. */
class CheckpointFailureBoundaryTest {
    private static final UUID SPACE = UUID.fromString("018f0f00-0000-7000-8000-000000000041");
    private static final UUID SOURCE = UUID.fromString("018f0f00-0000-7000-8000-000000000042");
    private static final UUID REVISION = UUID.fromString("018f0f00-0000-7000-8000-000000000043");
    private static final UUID ARTIFACT = UUID.fromString("018f0f00-0000-7000-8000-000000000044");

    @Test
    void parserOcrFailureLeavesCheckpointAtLastSuccessfulVersion(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("image.pdf");
        Files.write(file, blankPdf());
        LocalDirectoryConnector connector = new LocalDirectoryConnector(SPACE, SOURCE, temp);
        SourceChangeSet initial = connector.discover(SourceCheckpoint.empty(SPACE, SOURCE), DiscoveryRules.defaults());
        connector.commitCheckpoint(initial, CheckpointCommitResult.successful(initial.changeSetId()));
        String previousVersion = connector.currentCheckpoint().sourceVersion();

        ParsedDocument parsed = new NativeDocumentParser().parse(
                new ParseRequest(SPACE, REVISION, ARTIFACT, "application/pdf", Files.readAllBytes(file), 1, "1.0.0"),
                request -> { throw new com.ragforge.ingestion.parser.OcrException("OCR_TIMEOUT"); });
        assertThat(parsed.report().status()).isEqualTo(ParseStatus.OCR_UNAVAILABLE);
        assertThatThrownBy(() -> connector.commitCheckpoint(
                connector.discover(connector.currentCheckpoint(), DiscoveryRules.defaults()),
                new CheckpointCommitResult(initial.changeSetId(), false, false, false, false, false)))
                .isInstanceOf(ConnectorException.class);
        assertThat(connector.currentCheckpoint().sourceVersion()).isEqualTo(previousVersion);
    }

    @Test
    void objectStoreFailureLeavesCheckpointAtLastSuccessfulVersion(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("oversized.txt");
        Files.writeString(file, "object exceeds configured limit", StandardCharsets.UTF_8);
        LocalDirectoryConnector connector = new LocalDirectoryConnector(SPACE, SOURCE, temp);
        SourceChangeSet initial = connector.discover(SourceCheckpoint.empty(SPACE, SOURCE), DiscoveryRules.defaults());
        connector.commitCheckpoint(initial, CheckpointCommitResult.successful(initial.changeSetId()));
        String previousVersion = connector.currentCheckpoint().sourceVersion();
        byte[] bytes = Files.readAllBytes(file);
        var key = new com.ragforge.ingestion.objectstore.ObjectKey(SPACE, SOURCE, REVISION, ARTIFACT,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        LocalObjectStore store = new LocalObjectStore(temp.resolve("objects"),
                new ObjectStoreLimits(4, Set.of("text/plain")));
        assertThatThrownBy(() -> store.put(key, "text/plain", bytes))
                .isInstanceOf(ObjectStoreException.class);
        SourceChangeSet failed = connector.discover(connector.currentCheckpoint(), DiscoveryRules.defaults());
        assertThatThrownBy(() -> connector.commitCheckpoint(failed,
                new CheckpointCommitResult(failed.changeSetId(), false, false, false, false, false)))
                .isInstanceOf(ConnectorException.class);
        assertThat(connector.currentCheckpoint().sourceVersion()).isEqualTo(previousVersion);
    }

    @Test
    void parserFailureLeavesCheckpointAtLastSuccessfulVersion(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("invalid.txt");
        Files.write(file, new byte[]{(byte) 0xc3, (byte) 0x28});
        LocalDirectoryConnector connector = new LocalDirectoryConnector(SPACE, SOURCE, temp);
        SourceChangeSet initial = connector.discover(SourceCheckpoint.empty(SPACE, SOURCE), DiscoveryRules.defaults());
        connector.commitCheckpoint(initial, CheckpointCommitResult.successful(initial.changeSetId()));
        String previousVersion = connector.currentCheckpoint().sourceVersion();
        ParsedDocument parsed = new NativeDocumentParser().parse(
                new ParseRequest(SPACE, REVISION, ARTIFACT, "text/plain", Files.readAllBytes(file), 1, "1.0.0"), null);
        assertThat(parsed.report().status()).isEqualTo(ParseStatus.FAILED);
        SourceChangeSet failed = connector.discover(connector.currentCheckpoint(), DiscoveryRules.defaults());
        assertThatThrownBy(() -> connector.commitCheckpoint(failed,
                new CheckpointCommitResult(failed.changeSetId(), false, false, false, false, false)))
                .isInstanceOf(ConnectorException.class);
        assertThat(connector.currentCheckpoint().sourceVersion()).isEqualTo(previousVersion);
    }

    private static byte[] blankPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }
}
