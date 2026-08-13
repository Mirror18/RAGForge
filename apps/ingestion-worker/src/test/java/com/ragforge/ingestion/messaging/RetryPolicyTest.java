package com.ragforge.ingestion.messaging;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {
    private final RetryPolicy policy = new RetryPolicy(20);

    @Test
    void retryableFailureUsesBoundedExponentialBackoffThroughTwentyAttempts() {
        IntStream.rangeClosed(1, 19).forEach(attempt -> {
            RetryPolicy.Decision decision = policy.decide(attempt, FailureClass.PARSER_TIMEOUT);
            assertThat(decision.action()).isEqualTo(RetryPolicy.Action.RETRY);
            assertThat(decision.delay()).isBetween(Duration.ofSeconds(1), Duration.ofMinutes(5));
        });
        assertThat(policy.decide(20, FailureClass.PARSER_TIMEOUT).action())
                .isEqualTo(RetryPolicy.Action.DLQ);
    }

    @Test
    void permanentFailureNeverRetries() {
        assertThat(policy.decide(1, FailureClass.SECURITY_POLICY_REJECTED).action())
                .isEqualTo(RetryPolicy.Action.DLQ);
    }
}
