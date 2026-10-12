# RAGForge Constitution

## Core Principles

### I. Space Isolation Is a Security Boundary

Every query and mutation that touches tenant content must include and enforce `space_id`. Cross-space reads, writes, cache keys, vector filters, and object keys are defects, not product features. Front-end navigation never substitutes for server authorization.

### II. Provenance Over Generated Citations

Answers must retain document and chunk provenance from a structured Evidence Bundle. Citation is not free text invented by a model. Historical answers, previews, and replays re-check current authorization; deleted or revoked content must not reappear through cache or snapshot.

### III. Cloud Egress Is Explicit

Sending space content to a cloud model requires an active space authorization. There is no silent failover from a failed local route to a cloud route. After ADR-0014, the product path is cloud-only OpenAI-compatible APIs; missing authorization fails closed.

### IV. Business Services, Not Technical Microservices

Deployable processes follow complete business loops: space, knowledge, answer, and gateway. Parser, chunking, embedding, OCR, rerank, and audit stay inside the business that owns them. Nacos holds registration and process configuration only. It does not decide permission, egress, or citation rules.

### V. The Page Is the Acceptance Surface

A user must complete the product loop in the Web UI: sign in, space, cloud model, sources, index, cited answer, administration. A capability that exists only as an API or a hidden professional tool is unfinished. Visual language stays one set of tokens.

## Product Constraints

- Single-tenant deployment, many knowledge spaces, space-level RBAC. No document ACL in this constitution.
- Contracts in `contracts/` are the public API source. Java packages do not invent a second public surface.
- Database migrations are append-only. Shared schemas, BOM files, and ADRs have one owner per change.
- Secrets, personal Obsidian content, production data, and raw customer prompts stay out of Git.

## Delivery Workflow

1. Specify behavior in `specs/` before changing architecture or public contracts.
2. Track committed work in `backlog/`. Speckit `tasks.md` is a draft; import it with `python scripts/dev/import_speckit_tasks.py` before implementation. Do not copy the same task into a second board.
3. After each card meets acceptance, commit it immediately. Do not batch unrelated cards.
4. Architecture changes require an ADR. ADR-0014 is Accepted; the running processes remain `backend/server`, `backend/ingestion-worker`, `frontend/ragforge-web`, and `backend/ai-runtime` until the Maven split cards land.
5. Retrieval, prompt, chunking, parser, embedding, or rerank changes require an offline evaluation with a recorded configuration version.
6. High-risk actions need explicit human approval: accepting an ADR, accepting a third-party license, enabling cloud egress for a space, applying a production migration, and cutting a release.

## Governance

This constitution is the speckit evaluation baseline. The running tree is described in `backlog/docs/architecture/doc-1 - 当前代码.md`. `backlog/` is the execution board. Amendments require a dated constitution change and, if they alter architecture, a new or superseding ADR.

**Version**: 1.1.0 | **Ratified**: 2026-10-12 | **Last Amended**: 2026-10-12
