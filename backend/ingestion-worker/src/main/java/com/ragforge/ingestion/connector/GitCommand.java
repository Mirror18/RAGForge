package com.ragforge.ingestion.connector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

final class GitCommand {
    private GitCommand() { }

    static byte[] run(Path repository, List<String> arguments) {
        try {
            List<String> command = new java.util.ArrayList<>();
            command.add("git");
            command.add("-C");
            command.add(repository.toString());
            command.addAll(arguments);
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            byte[] output = process.getInputStream().readAllBytes();
            byte[] error = process.getErrorStream().readAllBytes();
            int exit = process.waitFor();
            if (exit != 0) {
                String safeError = new String(error, StandardCharsets.UTF_8).replaceAll("[\\r\\n]+", " ");
                throw new ConnectorException(ConnectorFailure.SOURCE_UNAVAILABLE,
                        "git command failed: " + safeError.substring(0, Math.min(300, safeError.length())));
            }
            return output;
        } catch (ConnectorException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ConnectorException(ConnectorFailure.SOURCE_UNAVAILABLE, "git is unavailable", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ConnectorException(ConnectorFailure.SOURCE_UNAVAILABLE, "git command interrupted", exception);
        }
    }
}
