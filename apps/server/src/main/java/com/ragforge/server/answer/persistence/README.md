# Phase 5 answer history persistence

`JdbcAnswerPersistence` is the durable implementation of `AnswerPersistencePort`.

- `V12__phase5_answer_history.sql` is append-only for answer, claim, citation,
  abstention, and replay-event rows. Updates are rejected by database triggers.
- Every parent and child row carries `space_id` and `run_id`; reads always bind
  both to the caller-provided scope. A foreign-space lookup behaves as missing.
- Completed answer text is retained only in `rag_answers.answer_text` until the
  configured retention deadline (the default implementation policy is 30 days).
  Refusal and cancellation rows retain only the structured reason/message.
- Citation preview returns evidence/chunk/revision identifiers, opaque content
  references, hashes, and safe anchors. It never returns URL, filename, quote,
  document text, prompt, or provider body.
- `ragforge_purge_expired_answers(now)` deletes expired complete aggregates with
  cascading children. There is no update-based redaction path for immutable rows.
- The legacy `saveIfAbsent(PersistedAnswer)` method remains for redacted refusal
  records. A completed answer must use `saveIfAbsent(Answer)`; otherwise the
  implementation fails explicitly instead of persisting an incomplete history.

The API/SSE owner should use `findAnswerByRun`, `findCitationPreview`, and
`replayEvents` only after re-authorizing the requested `space_id`.
