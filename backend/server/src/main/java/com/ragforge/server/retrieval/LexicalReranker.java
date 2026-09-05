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
                    String reason = documentTerms.isEmpty() ? "rrf-only-no-lexical-text"
                            : overlap == 0 ? "rrf-only-no-lexical-overlap" : "lexical-overlap-and-rrf";
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
        StringBuilder han = new StringBuilder();
        for (int offset = 0; offset < lower.length();) {
            int codePoint = lower.codePointAt(offset);
            boolean isHan = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
            if (isHan) {
                if (!ascii.isEmpty()) {
                    result.add(ascii.toString());
                    ascii.setLength(0);
                }
                han.appendCodePoint(codePoint);
            } else if (Character.isLetterOrDigit(codePoint)) {
                addHanNgrams(result, han);
                ascii.appendCodePoint(codePoint);
            } else {
                if (!ascii.isEmpty()) {
                    result.add(ascii.toString());
                    ascii.setLength(0);
                }
                addHanNgrams(result, han);
            }
            offset += Character.charCount(codePoint);
        }
        addHanNgrams(result, han);
        if (!ascii.isEmpty()) {
            result.add(ascii.toString());
        }
        return result;
    }

    private static void addHanNgrams(Set<String> result, StringBuilder han) {
        if (han.isEmpty()) {
            return;
        }
        String value = han.toString();
        for (int length = 2; length <= 4; length++) {
            for (int start = 0; start + length <= value.length(); start++) {
                result.add(value.substring(start, start + length));
            }
        }
        han.setLength(0);
    }
}
