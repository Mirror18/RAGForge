package com.ragforge.server.answer;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Test/in-process implementation that stores only hashes and provenance, never answer text. */
public final class InMemoryAnswerPersistence implements AnswerPersistencePort {
    private final ConcurrentHashMap<Key, PersistedAnswer> records = new ConcurrentHashMap<>();

    @Override
    public Optional<PersistedAnswer> find(UUID spaceId, String idempotencyKey) {
        return Optional.ofNullable(records.get(new Key(spaceId, idempotencyKey)));
    }

    @Override
    public PersistedAnswer saveIfAbsent(PersistedAnswer record) {
        return records.computeIfAbsent(new Key(record.spaceId(), record.idempotencyKey()), ignored -> record);
    }

    public int size() {
        return records.size();
    }

    private record Key(UUID spaceId, String idempotencyKey) {
    }
}
