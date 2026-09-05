package com.ragforge.ingestion.messaging;

import java.time.Duration;

public class RetryPolicy {
    public enum Action { RETRY, DLQ }

    private final int maxAttempts;

    public RetryPolicy(int maxAttempts) {
        this.maxAttempts = Math.max(1, Math.min(20, maxAttempts));
    }

    public Decision decide(int deliveryAttempt, FailureClass failureClass) {
        int attempt = Math.max(1, Math.min(maxAttempts, deliveryAttempt));
        if (failureClass.retryable() && attempt < maxAttempts) {
            return new Decision(Action.RETRY, attempt, backoff(attempt));
        }
        return new Decision(Action.DLQ, attempt, Duration.ZERO);
    }

    public Duration backoff(int attempt) {
        long baseMillis = Math.min(300_000L, 1_000L * (1L << Math.min(8, Math.max(0, attempt - 1))));
        long jitterWindow = Math.max(1, baseMillis / 4);
        long deterministicJitter = Math.floorMod((long) attempt * 1103515245L + 12345L, jitterWindow + 1);
        return Duration.ofMillis(Math.min(300_000L, baseMillis + deterministicJitter));
    }

    public int maxAttempts() { return maxAttempts; }

    public record Decision(Action action, int attempt, Duration delay) { }
}
