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
- The orchestrator reads `docs/08-records/PROJECT_STATUS.md`, the active phase in `docs/03-delivery/ROADMAP.md`, and its checklist before assigning work.
- Only parallelize concrete tasks that can be completed and tested independently. Contracts, shared schemas, migrations, dependency/BOM files, and architecture decisions are coordination points and must have one explicit owner.
- Keep at most one agent editing a given file or schema area. If ownership is unclear, serialize the work.
- The orchestrator keeps the primary worktree on `main`. Worker agents never implement features directly in the primary worktree.

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

1. Re-read its acceptance criteria and inspect the complete diff.
2. Run the smallest sufficient formatting, unit, contract, integration, security, or evaluation checks for its scope.
3. Update owned documentation and tests together with behavior changes.
4. Confirm `git status` contains no unrelated or untracked artifacts.
5. Commit the completed task stage on its own branch using a Chinese Conventional Commit.
6. Report branch, worktree, base SHA, commit SHA, changed files, tests/results, risks, and any integration notes.

A worker must not claim completion with uncommitted changes, failing required tests, unresolved conflicts, placeholder behavior, or fabricated verification. If blocked, it reports evidence and leaves the branch recoverable; it does not create a false “completed” commit.

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
