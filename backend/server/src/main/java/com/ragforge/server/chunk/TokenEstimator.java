package com.ragforge.server.chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic token estimation without a model dependency.
 *
 * <p>CJK characters count as one token each; ASCII runs count as one token per
 * four characters (rounded up). The estimate is stable across platforms and
 * identical for identical input, which is what chunk determinism requires.</p>
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        int asciiRun = 0;
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (Character.isWhitespace(c)) {
                tokens += ceil4(asciiRun);
                asciiRun = 0;
            } else if (isCjk(c)) {
                tokens += ceil4(asciiRun);
                asciiRun = 0;
                tokens += 1;
            } else {
                asciiRun++;
            }
        }
        tokens += ceil4(asciiRun);
        return tokens;
    }

    /** Returns the trailing substring covering at most {@code tokenCount} tokens. */
    public static String tailTokens(String text, int tokenCount) {
        if (tokenCount <= 0 || text == null || text.isEmpty()) {
            return "";
        }
        return text.substring(tailStart(text, tokenCount));
    }

    /** Returns the character offset where the trailing token window begins. */
    public static int tailStart(String text, int tokenCount) {
        if (tokenCount <= 0 || text == null || text.isEmpty()) {
            return text == null ? 0 : text.length();
        }
        List<TokenSpan> spans = tokenSpans(text);
        if (spans.isEmpty() || spans.size() <= tokenCount) {
            return firstNonWhitespace(text);
        }
        return spans.get(spans.size() - tokenCount).start();
    }

    /** Returns token-aligned character offsets for deterministic oversized splits. */
    static List<Integer> tokenEnds(String text) {
        List<Integer> ends = new ArrayList<>();
        for (TokenSpan span : tokenSpans(text)) {
            ends.add(span.end());
        }
        return List.copyOf(ends);
    }

    private record TokenSpan(int start, int end) {
    }

    private static List<TokenSpan> tokenSpans(String text) {
        List<TokenSpan> spans = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (isCjk(current)) {
                spans.add(new TokenSpan(index, index + 1));
                index++;
                continue;
            }
            int runStart = index;
            while (index < text.length()
                    && !Character.isWhitespace(text.charAt(index))
                    && !isCjk(text.charAt(index))) {
                index++;
            }
            for (int cursor = runStart; cursor < index; cursor += 4) {
                spans.add(new TokenSpan(cursor, Math.min(cursor + 4, index)));
            }
        }
        return spans;
    }

    private static int firstNonWhitespace(String text) {
        int index = 0;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int ceil4(int value) {
        return (value + 3) / 4;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }
}
