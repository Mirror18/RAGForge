package com.ragforge.server.retrieval;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

/** Offline-safe deterministic reranker used until a space-approved model route is wired. */
@Component
public final class LexicalReranker implements Reranker {
    @Override
    public List<Result> rerank(String normalizedQuery, List<RrfMerger.MergedCandidate> candidates, int limit) {
        if (normalizedQuery == null || normalizedQuery.isBlank() || candidates == null || limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("rerank request is invalid");
        }
        Set<String> queryTerms = terms(normalizedQuery);
        return candidates.stream()
                .map(candidate -> {
                    Set<String> documentTerms = terms(candidate.searchableText());
                    long overlap = queryTerms.stream().filter(documentTerms::contains).count();
                    double lexical = queryTerms.isEmpty() ? 0.0 : (double) overlap / queryTerms.size();
                    double score = candidate.rrfScore() * 0.35 + lexical * 0.65;
                    String reason = documentTerms.isEmpty() ? "rrf-only-no-lexical-text" : "lexical-overlap-and-rrf";
                    return new Result(candidate, score, reason);
                })
                .sorted(Comparator.comparingDouble(Result::score).reversed()
                        .thenComparing(result -> result.candidate().childChunkId().toString()))
                .limit(limit)
                .toList();
    }

    private static Set<String> terms(String text) {
        Set<String> result = new HashSet<>();
        if (text == null) {
            return result;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        StringBuilder ascii = new StringBuilder();
        for (int offset = 0; offset < lower.length();) {
            int codePoint = lower.codePointAt(offset);
            boolean han = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
            if (han) {
                if (!ascii.isEmpty()) {
                    result.add(ascii.toString());
                    ascii.setLength(0);
                }
                result.add(new String(Character.toChars(codePoint)));
            } else if (Character.isLetterOrDigit(codePoint)) {
                ascii.appendCodePoint(codePoint);
            } else if (!ascii.isEmpty()) {
                result.add(ascii.toString());
                ascii.setLength(0);
            }
            offset += Character.charCount(codePoint);
        }
        if (!ascii.isEmpty()) {
            result.add(ascii.toString());
        }
        return result;
    }
}
