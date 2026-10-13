package com.ragforge.server.answer.domain;

/** Raised when a producer attempts to append answer content after cancellation. */
public final class RunCancelledException extends IllegalStateException {
    public RunCancelledException(java.util.UUID runId) {
        super("Run is cancelled: " + runId);
    }
}
