package com.ragforge.ingestion.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the canonical synthetic manifest through the real connector on every OS. */
class CrossPlatformConnectorManifestTest {
    private static final UUID SPACE = UUID.fromString("018f0f00-0000-7000-8000-000000000051");
    private static final UUID SOURCE = UUID.fromString("018f0f00-0000-7000-8000-000000000052");

    @Test
    void sameLogicalManifestHasStableIdsHashesAndFiveChangeKinds(@TempDir Path temp) throws Exception {
        Path fixturePath = Path.of("tests", "fixtures", "phase3", "connector-manifest.json");
        if (!Files.isRegularFile(fixturePath)) {
            fixturePath = Path.of("..", "..", "tests", "fixtures", "phase3", "connector-manifest.json").normalize();
        }
        JsonNode fixture = new ObjectMapper().readTree(fixturePath.toFile());
        LocalDirectoryConnector connector = new LocalDirectoryConnector(SPACE, SOURCE, temp);
        writeObjects(temp, fixture.get("baseline"));

        SourceChangeSet initial = connector.discover(SourceCheckpoint.empty(SPACE, SOURCE), DiscoveryRules.defaults());
        assertThat(initial.sourceVersion()).isEqualTo(fixture.get("baselineSourceVersion").asText());
        assertThat(initial.changes()).extracting(SourceChange::kind)
                .containsOnly(ChangeKind.ADD);
        assertThat(initial.changes()).extracting(SourceChange::stableSourceObjectId)
                .containsExactlyInAnyOrderElementsOf(fixture.get("baselineStableObjectIds").valueStream()
                        .map(JsonNode::asText).map(UUID::fromString).toList());
        connector.commitCheckpoint(initial, CheckpointCommitResult.successful(initial.changeSetId()));

        Files.delete(temp.resolve("delete.txt"));
        Files.writeString(temp.resolve("modify.txt"), "modified", StandardCharsets.UTF_8);
        Files.move(temp.resolve("docs/two.md"), temp.resolve("docs/renamed.md"));
        Files.writeString(temp.resolve("added.md"), "added", StandardCharsets.UTF_8);
        SourceChangeSet current = connector.discover(connector.currentCheckpoint(), DiscoveryRules.defaults());
        Map<String, ChangeKind> kinds = current.changes().stream()
                .collect(Collectors.toMap(SourceChange::canonicalPath, SourceChange::kind));
        assertThat(kinds).containsEntry("docs/one.md", ChangeKind.UNCHANGED)
                .containsEntry("docs/renamed.md", ChangeKind.MOVE)
                .containsEntry("modify.txt", ChangeKind.MODIFY)
                .containsEntry("added.md", ChangeKind.ADD)
                .containsEntry("delete.txt", ChangeKind.DELETE);
        SourceChange move = current.changes().stream().filter(change -> change.kind() == ChangeKind.MOVE).findFirst().orElseThrow();
        assertThat(move.stableSourceObjectId()).isEqualTo(initial.changes().stream()
                .filter(change -> change.canonicalPath().equals("docs/two.md")).findFirst().orElseThrow()
                .stableSourceObjectId());
    }

    private static void writeObjects(Path root, JsonNode objects) throws Exception {
        for (JsonNode object : objects) {
            Path path = root.resolve(object.get("path").asText().replace('/', root.getFileSystem().getSeparator().charAt(0)));
            Files.createDirectories(path.getParent() == null ? root : path.getParent());
            Files.writeString(path, object.get("content").asText(), StandardCharsets.UTF_8);
        }
    }
}
