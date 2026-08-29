# AGENTS.md

## Repository purpose

RAGForge is a commercial-grade RAG engineering learning project. Product and project documents are written in Simplified Chinese. Code identifiers, APIs, event names, database objects, and logs use English. Git commit subjects and bodies use Simplified Chinese while retaining English Conventional Commit type/scope prefixes.

## Non-negotiable rules

- Keep the system a modular monolith plus a separately deployable ingestion worker until an ADR proves a split is necessary.
- Treat knowledge-space isolation as a security boundary. Every query and mutation touching tenant content must include and enforce `space_id`.
- Cloud data egress is opt-in per space. Never silently fail over from a local route to a cloud route.
- Answers must retain document/chunk provenance. Do not implement citation as generated free text.
- Never commit secrets, personal Obsidian content, model credentials, production data, or raw customer prompts.
- Do not copy third-party source before its license is accepted and the reuse register is updated.
- Prefer official dependencies over vendored source. When vendoring is necessary, pin the upstream commit SHA and preserve notices.
- Architecture changes require an ADR. Product-scope changes require updates to the PRD, roadmap, risks, and traceability matrix.
- High-risk actions require explicit human approval before execution: accepting an ADR as binding, accepting a third-party license, enabling cloud egress for a space, applying a production database migration, and creating a release.

## Multi-agent execution rules

### Orchestrator responsibility

- One primary agent acts as orchestrator. It owns task decomposition, dependency ordering, worktree allocation, integration, full verification, project-status updates, and phase closure.
- **Daily-entry rule (token efficiency):** The orchestrator first reads `docs/08-records/AGENT_STATE_CARD.md` (the compressed state card, ~1k tokens) and `docs/08-records/TASK_BOARD.md` (the budgeted task board). It only opens `PROJECT_STATUS.md`, `ROADMAP.md`, and phase checklists when doing audit, phase closure, or when the state card conflicts with code facts. Under no circumstances should the orchestrator re-read these long governance documents every round.
- Tasks are taken from the board card-by-card (never "do Phase 7" as one task). Each card has an explicit token budget recorded in the board and mirrored in the per-worker ticket.
- Only parallelize concrete tasks that can be completed and tested independently. Contracts, shared schemas, migrations, dependency/BOM files, and architecture decisions are coordination points and must have one explicit owner.
- Keep at most one agent editing a given file or schema area. If ownership is unclear, serialize the work.
- The orchestrator keeps the primary worktree on `main`. Worker agents never implement features directly in the primary worktree.
- After each batch of 1–3 successfully-merged cards, the orchestrator updates `docs/08-records/AGENT_STATE_CARD.md` §1 (baseline SHA) and §6 (dispatch table: status / actual tokens / commit SHA / notes). Other sections of the state card are edited only during audit-grade corrections.

### Branch and worktree isolation

- Every worker task uses one dedicated branch and one dedicated Git worktree created from a recorded base commit SHA.
- Branch names use `codex/<phase>-<task>-<agent>`, for example `codex/p1-openapi-a1`.
- Worktrees live outside the repository, preferably under `D:\project\learning\RAGForge-worktrees\<branch-slug>` on this machine.
- Before creating parallel worktrees, the primary repository must have an initial commit and a clean `main` worktree.
- A worker must receive: objective, non-goals, owned files/directories, read-only dependencies, acceptance criteria, required tests, worktree path, branch, and base SHA.
- A worker may inspect the whole repository but may modify only its assigned ownership area. It must not switch branches in another agent's worktree.
- Do not share generated build directories, local databases, ports, Compose project names, or mutable test data between concurrent worktrees. Assign unique names/ports when concurrent execution requires them.
- Never use force-push, `git reset --hard`, broad checkout/restore, or delete another agent's branch/worktree. Preserve unrelated user changes.

### Parallel task boundaries

- Prefer parallel work by bounded ownership such as `contracts`, one application module, one test suite, or one documentation area.
- API/event contracts are defined before provider and consumer implementations. When both sides run in parallel, they use the same committed contract baseline and contract tests.
- Database migrations are append-only and have a single sequence owner per batch. Agents must not independently invent colliding migration versions.
- Root build files, dependency locks/BOMs, Compose files, shared libraries, ADRs, `AGENTS.md`, `PROJECT_STATUS.md`, `RISK_REGISTER.md`, and `TRACEABILITY_MATRIX.md` are integration-sensitive. Assign one owner or leave final edits to the orchestrator.
- If an agent discovers a required change outside its ownership, it reports the exact proposed change to the orchestrator instead of editing it silently.

### Worker completion contract

Before reporting completion, each worker must:

1. Re-read only its acceptance ticket (`docs/08-records/tickets/<CARD_ID>-<agent>.yaml`). Do **not** re-read AGENTS.md, PROJECT_STATUS, ROADMAP, ADRs, or the whole contracts/ directory unless the ticket explicitly lists those paths in `read_only`.
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
- **Audit Agent**: a one-shot high-context role. Invoked only (a) at phase entry, (b) at suspected state-card drift, or (c) at release preparation. It reads PROJECT_STATUS, checklists, execution plans, and wide code facts. Product: an updated state card + updated board + delta plan. After execution, its context is discarded; subsequent workers do not inherit its full-context prompt.
- **Worker Agent**: owns exactly one ticket. Reads exactly the files in `scope.read_only` of that ticket. Exactly one card per invocation. After pass/fail/blocked report, its worktree remains, but the worker session ends.

When a user prompt says "keep working on Phase 7" (or similar scope), route as: **1 Orchestrator × (2–3 parallel Workers max)**. Never a single big agent doing everything inside one context window.

### E2. Precise-context feeding (no wholesale reads)

The orchestrator writes every ticket's `scope.read_only` as an allow-list of exact file paths (and, when tooling supports it, exact line ranges). The worker never falls back to "open apps/server/ingestion and read all controllers". General rules:

- Long governance documents (`PROJECT_STATUS.md`, `ROADMAP.md`, `DEFINITION_OF_DONE.md`, `TEST_STRATEGY.md`, `SECURITY_BASELINE.md`, `THREAT_MODEL.md`): a worker may read at most one or two named subsections, never the full file.
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

- Every card has a `token_limit` recorded in `docs/08-records/TASK_BOARD.md`.
- The same limit is mirrored into the worker ticket and consumed by `report_schema.budget.token_used` self-report.
- Hard rule: if a worker reports `token_used > 1.2 × token_limit` and acceptance is still `status != PASS`, the orchestrator must stop, inspect why, and either (a) split the card into smaller tickets with their own budgets or (b) explicitly approve an overrun, recording it in state-card §6 `notes` and TASK_BOARD.md remarks.
- Soft rule: if an orchestrator burns > `TASK_BOARD` total P0+P1+P2 × 1.2 in a single phase without finishing the phase checklists, it stops, requests a human audit, and a fresh Audit Agent reconciles the drift.

### E5. Single source of truth (no duplicated state)

- **Project state**: `docs/08-records/AGENT_STATE_CARD.md` is the daily single source. `docs/08-records/PROJECT_STATUS.md` is the authoritative audit record only for phase-closure / release-decision / security-incident scenarios. If they conflict, PROJECT_STATUS wins, and the state card is corrected in a dedicated integration commit (never patched inside a worker task).
- **Task definition**: `TASK_BOARD.md` is the board. `AGENT_STATE_CARD.md` §6 dispatch table is the execution snapshot. Worker tickets are the per-invocation contracts. Do not duplicate card acceptance criteria into ad-hoc chat messages; always reference the board + ticket.
- **Memory / lessons learned**: `MEMORY.md` stores only session-wise engineering lessons, not project state. If an agent writes "current phase / next task / completed SHA" anywhere other than the state card, that write must be rejected by the orchestrator at integration review.

### E6. Stopping conditions (mandatory)

An agent halts and reports to the human if and only if any of the following apply (otherwise, it keeps executing the board in order):

1. A high-risk action listed in Non-negotiable rules needs explicit human approval.
2. The state card vs. PROJECT_STATUS vs. code facts cannot be reconciled without guessing.
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
- A commit may be merged only when all relevant CI jobs are green, the orchestrator review has no unresolved comments, and the Definition of Done in `docs/03-delivery/DEFINITION_OF_DONE.md` is satisfied.
- Security-sensitive changes (authentication, authorization, data egress, prompt injection, SSRF, secrets handling) require a security review pass before merge; see `docs/06-security-compliance/SECURITY_BASELINE.md` and `docs/06-security-compliance/THREAT_MODEL.md`.
- Integrate one branch at a time in dependency order. Prefer a non-fast-forward merge so worker commits remain traceable; the merge commit message must also be Chinese, for example `merge(p1): 合并 OpenAPI 契约任务`.
- Do not resolve conflicts with blanket `ours`/`theirs`. Reconcile against the accepted contract and rerun all affected tests.
- After each integration batch, run repository-level tests and check architecture, contracts, migrations, security boundaries, licenses, and documentation links as applicable.
- After a phase meets every roadmap exit criterion, update `PROJECT_STATUS.md`, `RISK_REGISTER.md`, `TRACEABILITY_MATRIX.md`, and the phase retrospective, then create a Chinese phase-closure commit such as `docs(phase-1): 完成工程与领域骨架阶段验收`.
- Remove a worker worktree and delete its local branch only after the branch is integrated, the worktree is clean, and the integration is verified. Never remove a worktree containing uncommitted work.
- Stop the execution loop only when the current requested phase is genuinely complete or a material decision/credential/external dependency requires user input. Running out of easy tasks is not completion.

## Release and versioning

- Releases follow Semantic Versioning and must record an entry in `CHANGELOG.md`. Never cut a release without an explicit human decision on the version number, changelog content, and rollback point.
- Each release must reference the exact commit SHA, the deployment artifact/SBOM, and the rollback procedure; see `docs/05-operations/DEPLOYMENT.md` and the Main/Release pipeline in `docs/03-delivery/DEVELOPMENT_WORKFLOW.md`.
- Before a release, verify that the phase exit-criteria evidence is committed under `docs/08-records/`.

## Security incidents and dependency response

- Report suspected vulnerabilities through `SECURITY.md`. Do not disclose a confirmed vulnerability in a public commit before coordinated disclosure.
- Triage critical/high vulnerabilities promptly (target: initial triage within 24 hours of confirmation), record the decision in `RISK_REGISTER.md`, and land a fix or documented mitigation before the next release.
- Dependency updates run on a maintained cadence (for example Dependabot or Renovate). The BOM owner reviews each update for license, vulnerability, and maintenance health before merging; see `docs/07-research/UPSTREAM_REUSE_REGISTER.md`.

## Directory ownership

- `apps/server`: synchronous API and application orchestration.
- `apps/ingestion-worker`: asynchronous source synchronization and indexing jobs.
- `apps/web`: Vue SPA.
- `apps/ai-runtime`: OCR and rerank runtime only; it is not a second business backend.
- `contracts`: source of truth for public API and event contracts.
- `tests`: cross-application and acceptance tests; unit tests stay with their modules.
- `docs/08-records`: dated decision evidence, reviews, risks, and retrospectives.

## Quality gates

Before merging a change, run the relevant formatting, unit, architecture, contract, integration, security, and evaluation tests. Changes to retrieval, prompts, chunking, parsers, embedding, or reranking must include an offline evaluation comparison and record the tested configuration version.

## Documentation rules

- Use relative Markdown links for repository files and direct HTTPS links for external sources.
- State facts, assumptions, decisions, and open questions separately.
- Give every reusable artifact a version or immutable identifier.
- Update `updateTime`-style Obsidian metadata only after project records are intentionally copied into the vault; this repository itself does not use Obsidian frontmatter.

## Governance of this file

- `AGENTS.md` is integration-sensitive: assign a single owner for changes and require human-confirmed review of rule changes before committing. Never fold edits to this file into unrelated task commits.
- Keep every rule actionable and verifiable; when a rule references a repository document, use a relative Markdown link.
