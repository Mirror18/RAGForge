package com.ragforge.server.answer.application;

import java.util.UUID;

/** Receives bounded, user-visible answer text deltas for one in-flight answer. */
public interface GenerationStreamObserver {
    UUID answerId();

    void onDelta(String delta);
}
