package com.ragforge.server.answer.application;

import com.ragforge.server.knowledge.application.CitationValidator;
import com.ragforge.server.knowledge.domain.EvidenceBundle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import com.ragforge.server.answer.domain.Citation;

/**
 * Parses claim citation tokens against the current Evidence Bundle.
 *
 * <p>Generation JSON accepts two documented equivalent shapes (RF-21):
 * <ul>
 *   <li>{@code "citation_tokens": ["&lt;uuidv7&gt;", ...]}</li>
 *   <li>{@code "evidence_id": "&lt;uuidv7&gt;"} when {@code citation_tokens} is absent</li>
 * </ul>
 * Free-text markers, empty claims, and IDs outside the bundle are rejected as
 * {@code citation_invalid} upstream; this parser never invents provenance fields.
 */
public final class CitationTokenParser {
    private static final Pattern UUID_V7 = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    /**
     * Normalize the two accepted generation claim shapes into raw token strings.
     * When both fields are present they must describe the same single evidence id.
     */
    public List<String> normalizeClaimCitationTokens(Collection<String> citationTokens, String evidenceId) {
        List<String> tokens = new ArrayList<>();
        if (citationTokens != null) {
            for (String token : citationTokens) {
                if (token != null && !token.isBlank()) {
                    tokens.add(token.trim());
                }
            }
        }
        boolean hasTokens = !tokens.isEmpty();
        boolean hasEvidenceId = evidenceId != null && !evidenceId.isBlank();
        if (hasTokens && hasEvidenceId) {
            String normalizedEvidenceId = evidenceId.trim();
            if (tokens.size() != 1 || !tokens.getFirst().equals(normalizedEvidenceId)) {
                throw new CitationTokenException(
                        "citation_tokens and evidence_id must refer to the same evidence id");
            }
            return List.copyOf(tokens);
        }
        if (hasTokens) {
            return List.copyOf(tokens);
        }
        if (hasEvidenceId) {
            return List.of(evidenceId.trim());
        }
        throw new CitationTokenException("A claim must contain citation_tokens or evidence_id");
    }

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
