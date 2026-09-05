package com.ragforge.server.answer;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Test/in-process implementation that stores only hashes and provenance, never answer text. */
public final class InMemoryAnswerPersistence implements AnswerPersistencePort {
    private final ConcurrentHashMap<Key, PersistedAnswer> records = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, Answer> answers = new ConcurrentHashMap<>();

    @Override
    public Optional<PersistedAnswer> find(UUID spaceId, String idempotencyKey) {
        return Optional.ofNullable(records.get(new Key(spaceId, idempotencyKey)));
    }

    @Override
    public PersistedAnswer saveIfAbsent(PersistedAnswer record) {
        return records.computeIfAbsent(new Key(record.spaceId(), record.idempotencyKey()), ignored -> record);
    }

    @Override
    public PersistedAnswer saveIfAbsent(Answer answer) {
        PersistedAnswer record = new PersistedAnswer(answer.answerId(), answer.spaceId(), answer.runId(),
                answer.idempotencyKey(), answer.status(), hash(answer.answerText()),
                hash(answer.citations().toString()), answer.provenance());
        PersistedAnswer stored = saveIfAbsent(record);
        answers.putIfAbsent(new Key(answer.spaceId(), answer.idempotencyKey()), answer);
        return stored;
    }

    public int size() {
        return records.size();
    }

    @Override
    public Optional<Answer> findAnswerByRun(UUID spaceId, UUID runId) {
        return answers.values().stream().filter(answer -> answer.spaceId().equals(spaceId)
                && answer.runId().equals(runId)).findFirst();
    }

    private static String hash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime", exception);
        }
    }

    private record Key(UUID spaceId, String idempotencyKey) {
    }
}
