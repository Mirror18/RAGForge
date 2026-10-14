# AGENTS.md

## Repository purpose

RAGForge is a commercial-grade RAG engineering learning project. Product and project documents are written in Simplified Chinese. Code identifiers, APIs, event names, database objects, and logs use English. Git commit subjects and bodies use Simplified Chinese while retaining English Conventional Commit type/scope prefixes.

## Non-negotiable rules

- Keep the system as four business services: `space`, `knowledge`, `answer`, and `gateway` (ADR-0014). Knowledge may run a second process of the same code for batch jobs. Do not split parser, OCR, or rerank into their own services.
- Treat knowledge-space isolation as a security boundary. Every query and mutation touching tenant content must include and enforce `space_id`.
- Cloud data egress is opt-in per space. Chat and embedding use only space-authorized OpenAI-compatible cloud APIs. Never silently fail over from a local route to a cloud route; unauthorized spaces fail closed.
- Answers must retain document/chunk provenance. Do not implement citation as generated free text.
- Never commit secrets, personal Obsidian content, model credentials, production data, or raw customer prompts.
- Do not copy third-party source before its license is accepted and the reuse register is updated.
- Prefer official dependencies over vendored source. When vendoring is necessary, pin the upstream commit SHA and preserve notices.
- Architecture changes require an ADR. Product-scope changes require updates to the living documents under `backlog/docs/` (code map, operations, risks when they exist). Do not recreate the removed `docs/00–15` novels.
- High-risk actions require explicit human approval before execution: accepting an ADR as binding, accepting a third-party license, enabling cloud egress for a space, applying a production database migration, and creating a release.

## Multi-agent execution rules

### Orchestrator responsibility

- One primary agent acts as orchestrator. It owns task decomposition, dependency ordering, branch allocation, integration, full verification, project-status updates, and phase closure.
- **Daily-entry rule (token efficiency):** The orchestrator first reads `backlog/docs/architecture/doc-1 - 当前代码.md`, `backlog/docs/operations/doc-3 - 任务与文档工作流.md`, and the task files under `backlog/tasks`. Historical phase records stay in Git history. Do not recreate the removed `docs/00–15` novels.
- **Board rule:** `backlog/tasks` is the only execution queue. Speckit output in `specs/<id>/tasks.md` is a draft until `python scripts/dev/import_speckit_tasks.py --feature-dir specs/<id>` writes or updates cards. Do not implement solely from `tasks.md`.
- **Commit rule:** After a card meets its acceptance criteria, commit that card immediately (Chinese Conventional Commit). Do not start the next card with uncommitted work from the previous one.
- Tasks are taken from the board card-by-card (never "do Phase 7" as one task). Each card has an explicit `token_limit` in its frontmatter and, when a worker ticket exists, the same limit in the ticket.
- Only parallelize concrete tasks that can be completed and tested independently. Contracts, shared schemas, migrations, dependency/BOM files, and architecture decisions are coordination points and must have one explicit owner.
- Keep at most one agent editing a given file or schema area. If ownership is unclear, serialize the work.
- Agents implement directly in this repository checkout. Prefer a dedicated feature branch per card; do not create external Git worktrees unless the user explicitly asks.
- After each batch of 1–3 successfully-merged cards, the orchestrator updates the affected backlog task status and, when the running layout changed, `backlog/docs/architecture/doc-1 - 当前代码.md`.

### Branch isolation

- Every worker task uses one dedicated branch created from a recorded base commit SHA, checked out in this repository.
- Branch names use `codex/<phase>-<task>-<agent>`, for example `codex/p1-openapi-a1`.
- Do not create or use paths under `RAGForge-worktrees/` (or other external worktrees) by default.
- A worker must receive: objective, non-goals, owned files/directories, read-only dependencies, acceptance criteria, required tests, branch, and base SHA.
- A worker may inspect the whole repository but may modify only its assigned ownership area.
- Concurrent agents must not share generated build directories, local databases, ports, Compose project names, or mutable test data; assign unique names/ports when parallel execution requires them.
- Never use force-push, `git reset --hard`, or broad checkout/restore that discards unrelated user changes. Do not delete another agent's in-progress branch.

### Parallel task boundaries

- Prefer parallel work by bounded ownership such as `contracts`, one application module, one test suite, or one documentation area.
- API/event contracts are defined before provider and consumer implementations. When both sides run in parallel, they use the same committed contract baseline and contract tests.
- Database migrations are append-only and have a single sequence owner per batch. Agents must not independently invent colliding migration versions.
- Root build files, dependency locks/BOMs, Compose files, shared libraries, ADRs, `AGENTS.md`, and `backlog/docs/` are integration-sensitive. Assign one owner or leave final edits to the orchestrator.
- If an agent discovers a required change outside its ownership, it reports the exact proposed change to the orchestrator instead of editing it silently.

### Worker completion contract

Before reporting completion, each worker must:

1. Re-read only its acceptance ticket (`.agents/tickets/<CARD_ID>-<agent>.yaml`). Do **not** re-read AGENTS.md, the project audit record, the delivery document, ADRs, or the whole contracts/ directory unless the ticket explicitly lists those paths in `read_only`.
2. Modify only files inside the ticket's `ownership` whitelist. If a required change falls outside the whitelist, the worker proposes the exact delta to the orchestrator instead of editing silently.
3. Read only files listed in the ticket's `read_only`. Do not run broad `Grep` / `SearchCodebase` / `Glob` outside the ticket scope to discover context. Ask the orchestrator to extend the `read_only` list if genuinely needed.
4. Implement the smallest vertical slice including tests, failure paths, permission/space isolation, observability, and any documentation owned by this card.
5. Run exactly the commands listed in `tests.must_run` of the ticket; additionally run any format/link/secret/architecture gates that are standard for the ownership area.
6. For any command producing more than ~50 lines of output, store raw output at `tests.evidence_file` from the ticket and keep only a structured summary in the report (total / passed / failed / failed_cases / duration_ms). Never paste the complete stdout/stderr.
7. Confirm `git status` has no unrelated or untracked artifacts.
8. Commit the completed task stage on its own branch using a Chinese Conventional Commit.
9. Report with the exact YAML/JSON schema from `report_schema` in the ticket, including `budget.token_used`. If `token_used > 1.2 × token_limit`, report status `BLOCKED` with a short `overrun_reason`; do not keep implementing to "just finish".

A worker must not claim completion with uncommitted changes, failing required tests, unresolved conflicts, placeholder behavior, or fabricated verification. If blocked, it reports evidence and leaves the branch recoverable; it does not create a false “completed” commit.

## Agent token-efficiency protocol

This section is a hard gate for all agents. The goal is to keep the per-card effective/total token ratio well above 40% (before the protocol the measured ratio was ~20%, with ~60–80% waste on duplicated reads).

### E1. Three-agent role separation

Do not mix these contexts inside one agent invocation:

- **Orchestrator**: reads only the compressed state card, task board, and the tickets under creation. Never reads whole-module source code or long governance documents. Owns dispatch, merge order, state-card updates, and phase-closure governance commits.
- **Audit Agent**: a one-shot high-context role. Invoked only (a) at phase entry, (b) at suspected state-card drift, or (c) at release preparation. It reads `backlog/docs/architecture/doc-1 - 当前代码.md`, the open cards, checklists, and wide code facts. Product: an updated code-map document + updated board + delta plan. After execution, its context is discarded; subsequent workers do not inherit its full-context prompt.
- **Worker Agent**: owns exactly one ticket. Reads exactly the files in `scope.read_only` of that ticket. Exactly one card per invocation. After pass/fail/blocked report, the feature branch remains for integration, but the worker session ends.

When a user prompt says "keep working on Phase 7" (or similar scope), route as: **1 Orchestrator × (2–3 parallel Workers max)**. Never a single big agent doing everything inside one context window.

### E2. Precise-context feeding (no wholesale reads)

The orchestrator writes every ticket's `scope.read_only` as an allow-list of exact file paths (and, when tooling supports it, exact line ranges). The worker never falls back to "open backend/server/ingestion and read all controllers". General rules:

- Living documents are `backlog/docs/architecture/doc-1 - 当前代码.md` and `backlog/docs/operations/doc-2 - 运行与安全.md`. A worker reads only the sections named by its ticket.
- Contracts: read only the domain-specific schema files and OpenAPI YAML touched by the card. Do not pre-read `contracts/README.md` unless the ticket says so.
- ADRs: read only the numbered ADR the card explicitly references.
- Evidence JSON under `tests/evidence/*.json`: if the ticket only needs to confirm a gate, read the top-level `passed`/`summary` fields only; the detail array should not enter the context.

### E3. Output summary gate

Any intermediate artifact longer than 50 lines goes into a file under `tests/evidence/`, and only a summary enters a response. Applies to:

- `mvn test` / `npm test` / `pytest` logs
- Docker Compose logs, raw CI job output
- evidence JSON produced by the card
- diff reviews (the orchestrator inspects a list of changed file paths first, then deep-reads only suspicious ones; never diffs full 30+ files at once into context)

### E4. Budget enforcement

- Every card has a `token_limit` recorded on its backlog task.
- The same limit is mirrored into the worker ticket and consumed by `report_schema.budget.token_used` self-report.
- Hard rule: if a worker reports `token_used > 1.2 × token_limit` and acceptance is still `status != PASS`, the orchestrator must stop, inspect why, and either (a) split the card into smaller tickets with their own budgets or (b) explicitly approve an overrun, recording it on the card and in `backlog/docs/architecture/doc-1 - 当前代码.md` notes.
- Soft rule: if an orchestrator burns more than 1.2 × the sum of open P0+P1+P2 `token_limit` values on the board without finishing those cards, it stops, requests a human audit, and a fresh Audit Agent reconciles the drift.

### E5. Single source of truth (no duplicated state)

- **Project state**: `backlog/docs/architecture/doc-1 - 当前代码.md` describes the running tree. Git history holds older phase audits. If a document and the code disagree, the code wins and the backlog document is corrected in a dedicated integration commit.
- **Task definition**: `backlog/tasks` is the board. Speckit `tasks.md` is imported, not copied by hand into a second board. Worker tickets under `.agents/tickets/` are the per-invocation contracts. Do not duplicate card acceptance criteria into ad-hoc chat messages; always reference the board + ticket.
- **Memory / lessons learned**: do not store current phase, next task, or completed SHA outside the backlog task and Git commit. Historical notes that used to live in `docs/13` stay in Git history.

### E6. Stopping conditions (mandatory)

An agent halts and reports to the human if and only if any of the following apply (otherwise, it keeps executing the board in order):

1. A high-risk action listed in Non-negotiable rules needs explicit human approval.
2. `backlog/docs/architecture/doc-1 - 当前代码.md` vs. the open board vs. code facts cannot be reconciled without guessing.
3. A worker needs to modify outside its ownership and the orchestrator is absent / unavailable.
4. A card crosses its 1.2× token budget (see E4).
5. An external credential, hardware, or environment (e.g. standalone Ubuntu, signed release key, authorized outbound API) is required to continue and is not available.
6. A downstream ticket's dependencies are declared satisfied but are actually broken in the current main SHA — stop instead of "trying to work around" a known defect.

### Chinese commit convention

- Every completed task stage must be committed. A stage is an acceptance-complete slice, not an arbitrary time interval.
- Format: `<type>(<scope>): <中文摘要>`; the type and optional scope remain English Conventional Commit identifiers.
- Allowed common types: `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `build`, `ci`, `chore`, `revert`.
- Examples:
  - `feat(ingestion): 完成 Git 数据源增量检查点`
  - `test(security): 增加跨空间访问隔离用例`
  - `docs(phase-1): 更新阶段验收证据与风险`
- Commit body, when needed, is also written in Chinese and explains motivation, important trade-offs, verification, and migration/rollback impact.
- Do not combine unrelated stages in one commit. Do not use vague messages such as “更新代码”, “处理问题”, `wip`, or “final”.
- Before committing, inspect `git diff --cached`; the commit may include only the assigned task and required generated artifacts.

### Integration and phase closure

- The orchestrator reviews every worker commit and its verification evidence before integration.
- A commit may be merged only when all relevant CI jobs are green, the orchestrator review has no unresolved comments, and the card's acceptance checks pass.
- Security-sensitive changes (authentication, authorization, data egress, prompt injection, SSRF, secrets handling) require a security review pass before merge; see `backlog/docs/operations/doc-2 - 运行与安全.md`.
- Integrate one branch at a time in dependency order. Prefer a non-fast-forward merge so worker commits remain traceable; the merge commit message must also be Chinese, for example `merge(p1): 合并 OpenAPI 契约任务`.
- Do not resolve conflicts with blanket `ours`/`theirs`. Reconcile against the accepted contract and rerun all affected tests.
- After each integration batch, run repository-level tests and check architecture, contracts, migrations, security boundaries, licenses, and documentation links as applicable.
- After a milestone meets its exit criteria, update the affected documents under `backlog/docs/`, mark the milestone cards Done, and create a Chinese closure commit such as `docs(m-1): 完成可引用回答产品闭环验收`.
- Delete a worker feature branch only after it is integrated into `main` and the integration is verified. Never delete a branch that still has unmerged useful commits.
- Stop the execution loop only when the current requested phase is genuinely complete or a material decision/credential/external dependency requires user input. Running out of easy tasks is not completion.

## Release and versioning

- Releases follow Semantic Versioning and must record an entry in `CHANGELOG.md`. Never cut a release without an explicit human decision on the version number, changelog content, and rollback point.
- Each release must reference the exact commit SHA, the deployment artifact/SBOM, and the rollback procedure in `backlog/docs/operations/doc-2 - 运行与安全.md`.
- Before a release, verify the running layout in `backlog/docs/architecture/doc-1 - 当前代码.md`, structured evidence under `tests/evidence/`, and the Git commits that implemented the release candidate.

## Security incidents and dependency response

- Report suspected vulnerabilities through `.github/SECURITY.md` and `backlog/docs/operations/doc-2 - 运行与安全.md`. Do not disclose a confirmed vulnerability in a public commit before coordinated disclosure.
- Triage critical/high vulnerabilities promptly (target: initial triage within 24 hours of confirmation), record the decision on the backlog board, and land a fix or documented mitigation before the next release.
- Dependency updates run on a maintained cadence (for example Dependabot or Renovate). The BOM owner reviews each update for license, vulnerability, and maintenance health before merging; license identifiers stay in `backlog/docs/operations/doc-2 - 运行与安全.md`.

## Directory ownership

- `backend/server`: the running synchronous API. Identity, space, provider, ingestion, chunk, index, retrieval, answer, and run live in its Java packages.
- `backend/ingestion-worker`: the running async ingestion process (connector, parser, object store, messaging, pipeline).
- `backend/ai-runtime`: the Python rerank process required by the architecture check.
- `frontend/ragforge-web`: Vue SPA. Product pages live under `src/views/{access,space,knowledge,answer,admin}`.
- `backend/contracts`: source of truth for public API and event shapes (OpenAPI and JSON schemas).
- `specs/` and `backlog/`: speckit specs, the task board, and the code-based documents under `backlog/docs/`.
- `tests`: cross-application and acceptance tests; unit tests stay with their modules.
- `tests/evidence/`: structured gate output and the business-demo screenshots. Living prose is in `backlog/docs/`. Historical phase plans stay in Git history. Worker YAML contracts live under `.agents/tickets/`.
- ADR-0014 still names four future business processes (space, knowledge, answer, gateway). Those directories are not created until the Maven split lands. New work stays inside `backend/server` and `backend/ingestion-worker`.

## Quality gates

Before merging a change, run the relevant formatting, unit, architecture, contract, integration, security, and evaluation tests. Changes to retrieval, prompts, chunking, parsers, embedding, or reranking must include an offline evaluation comparison and record the tested configuration version.

## Local run refresh

After a completed change that affects the running stack, replace the local artifacts and restart the backend. Local Server and Worker run the isolated JARs under `backend/server/target-ragforge/` and `backend/ingestion-worker/target-ragforge/`, not IDE `target/classes`. From the repository root run [`scripts/dev/restart-local.bat`](scripts/dev/restart-local.bat): stop the PIDs in `tmp/local-run/`, rebuild with Java 21 and `-Pragforge-isolated-output`, then start Server on `25082` and Worker. Wait until `http://127.0.0.1:25082/actuator/health` returns 200. Leave Docker Compose project `ragforge-p1` running. Frontend-only edits do not require a Java restart when Vite is already serving `http://127.0.0.1:25174`. Clear `JAVA_TOOL_OPTIONS` during Maven so PowerShell `$ErrorActionPreference = Stop` does not abort on stderr.

## Documentation rules

- Use relative Markdown links for repository files and direct HTTPS links for external sources.
- State facts, assumptions, decisions, and open questions separately.
- Give every reusable artifact a version or immutable identifier.
- Update `updateTime`-style Obsidian metadata only after project records are intentionally copied into the vault; this repository itself does not use Obsidian frontmatter.

## Governance of this file

- `AGENTS.md` is integration-sensitive: assign a single owner for changes and require human-confirmed review of rule changes before committing. Never fold edits to this file into unrelated task commits.
- Keep every rule actionable and verifiable; when a rule references a repository document, use a relative Markdown link.
