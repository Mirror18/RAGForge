package com.ragforge.ingestion.connector;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class CanonicalPath {
    private static final Pattern DRIVE_PATH = Pattern.compile("^[A-Za-z]:.*");

    private CanonicalPath() { }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank() || raw.indexOf('\0') >= 0) {
            throw new ConnectorException(ConnectorFailure.PATH_INVALID, "path is empty or contains NUL");
        }
        String normalized = raw.replace('\\', '/');
        if (normalized.startsWith("/") || DRIVE_PATH.matcher(normalized).matches()) {
            throw new ConnectorException(ConnectorFailure.PATH_INVALID, "absolute path is not allowed");
        }
        String[] segments = normalized.split("/", -1);
        List<String> accepted = new ArrayList<>();
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new ConnectorException(ConnectorFailure.PATH_INVALID, "path contains an unsafe segment");
            }
            accepted.add(segment);
        }
        String result = String.join("/", accepted);
        if (result.length() > 2048) {
            throw new ConnectorException(ConnectorFailure.PATH_INVALID, "path exceeds the contract limit");
        }
        return result;
    }

    public static void require(String path) {
        if (!normalize(path).equals(path)) {
            throw new ConnectorException(ConnectorFailure.PATH_INVALID, "path is not canonical");
        }
    }
}
