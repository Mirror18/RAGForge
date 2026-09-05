package com.ragforge.ingestion.connector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Performs a bounded, read-only checkout. It never pushes and never accepts a local filesystem remote. */
public final class GitRepositoryCheckout {
    private GitRepositoryCheckout() { }

    public static Path checkout(String remote, String branch) {
        if (remote == null || branch == null || remote.isBlank() || branch.isBlank()
                || !(remote.startsWith("https://") || remote.startsWith("ssh://") || remote.startsWith("git@"))) {
            throw new ConnectorException(ConnectorFailure.PATH_INVALID, "git remote or branch is invalid");
        }
        try {
            Path root = Files.createTempDirectory("ragforge-git-");
            List<String> command = new ArrayList<>(List.of("git", "clone", "--no-tags", "--depth", "1",
                    "--branch", branch, "--", remote, root.toString()));
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectErrorStream(false)
                    .start();
            byte[] error = process.getErrorStream().readAllBytes();
            if (process.waitFor() != 0) {
                delete(root);
                String message = new String(error, StandardCharsets.UTF_8).replaceAll("[\\r\\n]+", " ");
                throw new ConnectorException(ConnectorFailure.SOURCE_UNAVAILABLE,
                        "git checkout failed: " + message.substring(0, Math.min(300, message.length())));
            }
            return root;
        } catch (ConnectorException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ConnectorException(ConnectorFailure.SOURCE_UNAVAILABLE, "git is unavailable", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ConnectorException(ConnectorFailure.SOURCE_UNAVAILABLE, "git checkout interrupted", exception);
        }
    }

    private static void delete(Path path) {
        try (var stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(value -> {
                try { Files.deleteIfExists(value); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
