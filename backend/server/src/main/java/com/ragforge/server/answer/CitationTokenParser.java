package com.ragforge.server.answer;

import com.ragforge.server.retrieval.CitationValidator;
import com.ragforge.server.retrieval.EvidenceBundle;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Parses only the raw UUID evidence token; all other citation syntax is rejected. */
public final class CitationTokenParser {
    private static final Pattern UUID_V7 = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    public List<UUID> parse(Collection<String> rawTokens, EvidenceBundle bundle, UUID requestedSpaceId) {
        Objects.requireNonNull(rawTokens, "rawTokens");
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(requestedSpaceId, "requestedSpaceId");
        Set<UUID> parsed = new LinkedHashSet<>();
        for (String rawToken : rawTokens) {
            UUID token = parseToken(rawToken);
            if (!parsed.add(token)) {
                throw new CitationTokenException("Duplicate citation token");
            }
        }
        if (parsed.isEmpty()) {
            throw new CitationTokenException("A claim must contain at least one citation token");
        }
        try {
            CitationValidator.requireBundleCitations(bundle, requestedSpaceId, parsed);
        } catch (IllegalArgumentException exception) {
            throw new CitationTokenException(exception.getMessage(), exception);
        }
        return List.copyOf(parsed);
    }

    public UUID parseToken(String rawToken) {
        if (rawToken == null || !UUID_V7.matcher(rawToken).matches()) {
            throw new CitationTokenException("Citation token must be an evidence UUIDv7");
        }
        try {
            return UUID.fromString(rawToken);
        } catch (IllegalArgumentException exception) {
            throw new CitationTokenException("Citation token is malformed", exception);
        }
    }

    public static final class CitationTokenException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public CitationTokenException(String message) {
            super(message);
        }

        public CitationTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
