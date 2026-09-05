package com.ragforge.ingestion.connector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceConnectorTest {
    private static final UUID SPACE = UUID.fromString("018f0f00-0000-7000-8000-000000000001");
    private static final UUID SOURCE = UUID.fromString("018f0f00-0000-7000-8000-000000000002");

    @Test
    void localDirectoryClassifiesFullAndIncrementalChangesAndKeepsDuplicateBasenamesSeparate(@TempDir Path temp)
            throws Exception {
        Path first = Files.createDirectories(temp.resolve("one"));
        Path second = Files.createDirectories(temp.resolve("two"));
        Files.writeString(first.resolve("note.md"), "same", StandardCharsets.UTF_8);
        Files.writeString(second.resolve("note.md"), "same", StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("stable.txt"), "stable", StandardCharsets.UTF_8);

        LocalDirectoryConnector connector = new LocalDirectoryConnector(SPACE, SOURCE, temp);
        SourceChangeSet initial = connector.discover(SourceCheckpoint.empty(SPACE, SOURCE),
                new DiscoveryRules(List.of("**\\*.md", "stable.txt"), List.of(), Set.of(), 1024, 20));
        assertThat(initial.changes()).extracting(SourceChange::kind)
                .containsExactly(ChangeKind.ADD, ChangeKind.ADD, ChangeKind.ADD);
        assertThat(initial.changes().stream().map(SourceChange::stableSourceObjectId).collect(Collectors.toSet()))
                .hasSize(3);
        assertThat(initial.changes()).allMatch(change -> !change.canonicalPath().contains("\\"));

        assertThatThrownBy(() -> connector.commitCheckpoint(initial,
                new CheckpointCommitResult(initial.changeSetId(), true, false, true, true, true)))
                .isInstanceOf(ConnectorException.class)
                .extracting("failure").isEqualTo(ConnectorFailure.PERSISTENCE_INCOMPLETE);
        assertThat(connector.currentCheckpoint().sourceVersion()).isEmpty();
        connector.commitCheckpoint(initial, CheckpointCommitResult.successful(initial.changeSetId()));

        Files.writeString(first.resolve("note.md"), "modified", StandardCharsets.UTF_8);
        Files.createDirectories(temp.resolve("three"));
        Files.move(second.resolve("note.md"), temp.resolve("three").resolve("note.md"));
        SourceChangeSet secondSet = connector.discover(connector.currentCheckpoint(), DiscoveryRules.defaults());
        assertThat(secondSet.changes()).extracting(SourceChange::kind)
                .containsExactlyInAnyOrder(ChangeKind.MODIFY, ChangeKind.MOVE, ChangeKind.UNCHANGED);
        SourceChange move = secondSet.changes().stream().filter(change -> change.kind() == ChangeKind.MOVE).findFirst().orElseThrow();
        assertThat(move.previousCanonicalPath()).isEqualTo("two/note.md");
        UUID movedIdentity = initial.changes().stream().filter(change -> change.canonicalPath().equals("two/note.md"))
                .findFirst().orElseThrow().stableSourceObjectId();
        assertThat(move.stableSourceObjectId()).isEqualTo(movedIdentity);
        connector.commitCheckpoint(secondSet, CheckpointCommitResult.successful(secondSet.changeSetId()));

        Files.delete(first.resolve("note.md"));
        SourceChangeSet deleted = connector.discover(connector.currentCheckpoint(), DiscoveryRules.defaults());
        assertThat(deleted.changes()).extracting(SourceChange::kind)
                .containsExactlyInAnyOrder(ChangeKind.DELETE, ChangeKind.UNCHANGED, ChangeKind.UNCHANGED);
    }

    @Test
    void fetchRejectsCrossSpaceAndStaleReferencesAndReturnsBoundedContent(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("readme.md");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        FileConnector connector = new FileConnector(SPACE, SOURCE, file);
        SourceChangeSet changeSet = connector.discover(SourceCheckpoint.empty(SPACE, SOURCE), DiscoveryRules.defaults());
        SourceChange add = changeSet.changes().get(0);
        connector.commitCheckpoint(changeSet, CheckpointCommitResult.successful(changeSet.changeSetId()));

        try (FetchedContent content = connector.fetch(add.reference(), add.sourceVersion())) {
            assertThat(content.stream().readAllBytes()).containsExactly("hello".getBytes(StandardCharsets.UTF_8));
            assertThat(content.metadata().contentHash()).isEqualTo(add.contentHash());
        }
        SourceReference foreign = new SourceReference(UUID.randomUUID(), SOURCE, add.stableSourceObjectId(),
                add.canonicalPath(), add.sourceVersion(), add.contentHash());
        assertThatThrownBy(() -> connector.fetch(foreign, add.sourceVersion()))
                .isInstanceOf(ConnectorException.class)
                .extracting("failure").isEqualTo(ConnectorFailure.CHECKPOINT_INVALID);
        assertThatThrownBy(() -> connector.fetch(add.reference(), "stale"))
                .isInstanceOf(ConnectorException.class)
                .extracting("failure").isEqualTo(ConnectorFailure.VERSION_MISMATCH);
    }

    @Test
    void canonicalPathRejectsTraversalAndExcludeWins() {
        assertThat(CanonicalPath.normalize("folder\\file.md")).isEqualTo("folder/file.md");
        assertThatThrownBy(() -> CanonicalPath.normalize("../file.md"))
                .isInstanceOf(ConnectorException.class)
                .extracting("failure").isEqualTo(ConnectorFailure.PATH_INVALID);
        assertThatThrownBy(() -> CanonicalPath.normalize("C:\\file.md"))
                .isInstanceOf(ConnectorException.class)
                .extracting("failure").isEqualTo(ConnectorFailure.PATH_INVALID);
    }

    @Test
    void gitConnectorRecordsCommitAndBranchProvenance(@TempDir Path temp) throws Exception {
        runGit(temp, "init", "-q");
        runGit(temp, "config", "user.email", "synthetic@example.invalid");
        runGit(temp, "config", "user.name", "RAGForge Synthetic");
        Files.writeString(temp.resolve("document.md"), "git fixture", StandardCharsets.UTF_8);
        runGit(temp, "add", "document.md");
        runGit(temp, "commit", "-qm", "fixture");

        GitConnector connector = new GitConnector(SPACE, SOURCE, temp);
        SourceChangeSet changes = connector.discover(SourceCheckpoint.empty(SPACE, SOURCE), DiscoveryRules.defaults());
        SourceChange add = changes.changes().get(0);
        assertThat(add.kind()).isEqualTo(ChangeKind.ADD);
        assertThat(add.sourceVersion()).matches("[0-9a-f]{40}");
        assertThat(add.provenance()).contains("git:commit=" + add.sourceVersion()).contains("branch=");
        try (FetchedContent content = connector.fetch(add.reference(), add.sourceVersion())) {
            assertThat(new String(content.stream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("git fixture");
        }
    }

    private static void runGit(Path directory, String... arguments) throws IOException, InterruptedException {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new AssertionError("synthetic git command failed: " + output);
        }
    }
}
