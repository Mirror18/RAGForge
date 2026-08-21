package com.ragforge.server.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

/**
 * Deterministic BM25 port used by the modular monolith until a durable lexical
 * index is selected. It is explicitly scoped by space and candidate index.
 */
@Component
public final class InMemoryBm25CandidateStore implements Bm25CandidateStore {
    private final ConcurrentMap<Scope, ConcurrentMap<UUID, Document>> documents = new ConcurrentHashMap<>();

    @Override
    public void upsert(Document document) {
        documents.computeIfAbsent(new Scope(document.spaceId(), document.indexVersionId()), ignored -> new ConcurrentHashMap<>())
                .put(document.childChunkId(), document);
    }

    @Override
    public List<RetrievalCandidate> search(UUID spaceId, UUID indexVersionId, String query, int limit) {
        requireScope(spaceId, indexVersionId);
        if (query == null || query.isBlank() || limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("BM25 query and limit are invalid");
        }
        List<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        List<DocumentStats> stats = documents.getOrDefault(new Scope(spaceId, indexVersionId), new ConcurrentHashMap<>())
                .values().stream().map(document -> new DocumentStats(document, terms(document.text()))).toList();
        if (stats.isEmpty()) {
            return List.of();
        }
        double averageLength = stats.stream().mapToInt(value -> value.terms().size()).average().orElse(1.0);
        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (DocumentStats stat : stats) {
            Map<String, Integer> frequencies = frequencies(stat.terms());
            double score = 0.0;
            for (String queryTerm : queryTerms) {
                int termFrequency = frequencies.getOrDefault(queryTerm, 0);
                if (termFrequency == 0) {
                    continue;
                }
                long documentFrequency = stats.stream().filter(other -> other.terms().contains(queryTerm)).count();
                double inverseDocumentFrequency = Math.log(1.0
                        + (stats.size() - documentFrequency + 0.5) / (documentFrequency + 0.5));
                double lengthNorm = 1.2 * (1.0 - 0.75 + 0.75 * stat.terms().size() / averageLength);
                score += inverseDocumentFrequency * (termFrequency * 2.2) / (termFrequency + lengthNorm);
            }
            if (score > 0.0) {
                Document document = stat.document();
                candidates.add(new RetrievalCandidate(document.spaceId(), document.indexVersionId(),
                        document.childChunkId(), document.documentRevisionId(), document.parentChunkId(),
                        document.contentRef(), document.textHash(), score, RetrievalCandidate.Source.BM25,
                        document.text()));
            }
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble(RetrievalCandidate::sourceScore).reversed()
                        .thenComparing(candidate -> candidate.childChunkId().toString()))
                .limit(limit)
                .toList();
    }

    private static List<String> terms(String text) {
        List<String> result = new ArrayList<>();
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        StringBuilder token = new StringBuilder();
        for (int offset = 0; offset < lower.length();) {
            int codePoint = lower.codePointAt(offset);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                flushToken(result, token);
                result.add(new String(Character.toChars(codePoint)));
            } else if (Character.isLetterOrDigit(codePoint)) {
                token.appendCodePoint(codePoint);
            } else {
                flushToken(result, token);
            }
            offset += Character.charCount(codePoint);
        }
        flushToken(result, token);
        return result;
    }

    private static void flushToken(List<String> result, StringBuilder token) {
        if (!token.isEmpty()) {
            result.add(token.toString());
            token.setLength(0);
        }
    }

    private static Map<String, Integer> frequencies(List<String> terms) {
        Map<String, Integer> result = new HashMap<>();
        for (String term : terms) {
            result.merge(term, 1, Integer::sum);
        }
        return result;
    }

    private static void requireScope(UUID spaceId, UUID indexVersionId) {
        if (spaceId == null || indexVersionId == null) {
            throw new IllegalArgumentException("spaceId and indexVersionId are required for BM25 access");
        }
    }

    private record Scope(UUID spaceId, UUID indexVersionId) {
    }

    private record DocumentStats(Document document, List<String> terms) {
    }
}
