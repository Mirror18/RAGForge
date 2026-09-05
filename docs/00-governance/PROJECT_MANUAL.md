# RAGForge 项目手册

本手册是面向人类开发者和 AI Agent 的稳定总览：说明项目要做什么、代码在哪里、如何启动、文档如何阅读，以及如何找到下一项任务。它不复制阶段状态、任务验收或 Git 提交细节；这些内容仍由状态卡、任务板和 Git history 分别负责。

## 1. 项目目标

RAGForge 是面向企业内部多用户知识空间的商业级 RAG 学习工程，覆盖数据接入、解析、分块、索引、检索、生成、结构化引用、安全、评估、部署和复盘。核心安全边界是 `space_id`：所有租户内容读写必须按空间授权；云端出境必须由空间显式 opt-in；回答必须保留可定位的 document/chunk provenance。

## 2. 先知道当前下一步

当前阶段和阻塞只看[状态卡](../08-records/AGENT_STATE_CARD.md)，可执行任务只看[任务板](../08-records/TASK_BOARD.md)。当前主线仍是 Phase 7 `p2-execution`；若 Ubuntu 24.04 独立环境仍不可用，`P7D-03` 保持阻塞，不以 Windows 共享环境替代部署验收。

任务达到的效果在任务板的人类任务总览中说明；某次任务具体改了哪些文件、跑了什么命令、有哪些风险和后续动作，在对应 commit body 中查看：

```powershell
git log --all --oneline --grep='P7C-01'
git show --format=fuller <完成SHA>
```

## 3. 代码和验证资产怎么放

```text
frontend/
└── ragforge-web/       # Vue SPA、前端单元/E2E 测试、构建配置、应用 README

backend/
├── server/             # 模块化单体：API、业务、权限、检索、回答、迁移
├── ingestion-worker/   # 独立异步摄取 Worker
└── ai-runtime/         # OCR/rerank 窄职责 Python 运行时

contracts/              # 跨应用 OpenAPI、事件和 schema 的唯一契约源
tests/                  # 跨应用/契约/集成/E2E/评估/性能/安全测试
fixtures/               # 公开或合成的文档、评估和安全样本
scripts/                # 仓库级开发、CI、评估和运维入口
config/                 # 可提交配置模板；config/private/ 仅存本机私密配置
deploy/                 # Compose、Dockerfile、观测和部署资产
docs/                   # 产品、架构、交付、质量、安全、运维和记录
libs/                   # 受约束的跨语言复用库
```

应用内的单元测试跟随应用：例如 Server 测试在 `backend/server/src/test/`，Web 测试在 `frontend/ragforge-web/tests/`。只有跨进程或需要独立运行器的测试才进入根目录 `tests/`；因此 `contracts/`、`tests/`、`fixtures/`、`scripts/` 保持独立并不是重复目录，而是仓库级共享边界。

## 4. 启动顺序

### 4.1 推荐的一键本地闭环

前置：Docker Desktop、Java 21、Maven、Node.js、Python，以及真实本地模型验收所需的 Ollama。

```powershell
.\scripts\dev\start-local.bat
```

脚本会按隔离项目名启动 PostgreSQL、Qdrant、RabbitMQ、Valkey、MinIO，再启动 Server、Worker 和 Web。日志放在被忽略的 `tmp/local-run/`；不需要 Web 时可用 `-SkipWeb`。

### 4.2 分进程启动

```powershell
python scripts/dev/core.py up
python scripts/dev/core.py health
mvn -pl backend/server spring-boot:run
$env:RAGFORGE_INGESTION_ENABLED = "true"
mvn -pl backend/ingestion-worker spring-boot:run
npm --prefix frontend/ragforge-web ci
npm --prefix frontend/ragforge-web run dev
```

AI Runtime 是可选服务：

```powershell
$env:RAGFORGE_AI_RUNTIME_SERVE = "true"
uv run --project backend/ai-runtime ragforge-ai-runtime
```

详细环境变量和单项验证命令分别见 [`backend/README.md`](../../backend/README.md)、[`frontend/README.md`](../../frontend/README.md)、[`config/private/README.md`](../../config/private/README.md) 和 [`scripts/dev/README.md`](../../scripts/dev/README.md)。

## 5. 文档应该怎么读和生成

文档按 `docs/00-governance` → `01-product` → `02-architecture` → `03-delivery` → `04-quality` → `05-operations` → `06-security-compliance` → `07-research` → `08-records` 的顺序组织。`docs/README.md` 是索引，本手册是跨分区总览；分区内只维护该分区的权威事实。

写新内容时遵循三条规则：

1. 产品范围先写 PRD；架构变化先写 ADR；阶段状态、风险、追溯和证据写入 `docs/08-records/`。
2. 任务目标和完成效果写任务板；执行细节、改动文件、测试命令、风险、回滚和下一步写 Git commit body，不复制成流水账。
3. 可复用的目录说明只在对应 README 写一次；根 README 只做入口链接。所有仓库文件使用相对 Markdown 链接，外部资料使用直接 HTTPS 链接。

## 6. 私密配置规则

`config/private/` 是本机敏感配置的存放位置，`.gitignore` 保证其中真实文件不提交；`config/` 中只保留可评审模板。禁止提交 Provider key、密码、私有仓库凭据、个人 Obsidian 内容、生产数据、raw prompt、模型凭据和构建产物。共享环境改用 CI secret 或 secret manager 注入。

## 7. 实际内容审计结论

本次整理依据工作树中的真实文件，而不是只依据目录名或 README：

| 区域 | 实际内容规模 | 事实 |
|---|---:|---|
| `backend/server/src/main/java` | 222 个 Java 文件、18 个 Controller | 已有身份、空间、来源、摄取、Provider、Prompt、检索、回答、审计、运维和 Studio 实现边界 |
| `backend/ingestion-worker/src/main/java` | 70 个 Java 文件 | 已有 connector、消息、对象存储、解析/OCR、pipeline 和 Git sync 实现 |
| `backend/ai-runtime/src/ragforge_ai_runtime` | 2 个 Python 源文件、1 个测试文件 | 已有 bounded RERANK loopback seam 和 `LOCAL_ONLY` readiness 输出 |
| `frontend/ragforge-web/src` | 18 个 TypeScript/Vue 文件 | 已有登录、空间、来源、任务、索引、回答、Studio、管理和路由页面 |
| `contracts/` / `tests/` / `fixtures/` / `scripts/` | 29 / 143 / 42 / 52 个文件 | 都有跨应用共享或仓库级职责，不是应用目录的空壳副本 |
| `docs/` | 89 个 Markdown 文件 | 包含产品、架构、路线图、质量、运维、安全、研究和阶段证据；不是只有介绍文档 |

空内容审计只发现 `docs/08-records/tickets/.gitkeep` 这一枚零字节占位文件，已移除。4 个最短的 ADR 虽然只有约 20 行，但都实际包含 Context、Decision 和 Consequences，不能因为短就删除。`deploy/kubernetes/README.md`、`libs/*/README.md`、`third_party/README.md` 和 `licenses/README.md` 是明确的保留/禁止边界，也不是已实现功能。

因此本次聚合遵循“真实实现归属 + 文档权威顺序”：前后端源码归拢，应用内测试跟随应用；跨应用契约、验收数据和仓库自动化保持独立；设计文档明确区分“已由代码确认”和“计划/待实现”；任务执行细节继续由 Git commit body 保存。

## 8. AI Agent 每轮执行路径

1. 读 `docs/08-records/AGENT_STATE_CARD.md`，确认阶段、基线、阻塞和下一步。
2. 读 `docs/08-records/TASK_BOARD.md`，选择一张依赖满足、ownership 明确、预算明确的卡片。
3. Worker 只读票据允许的文件并在独立 worktree 工作；Orchestrator 负责集成、门禁、状态卡回写。
4. 完成后检查 `git diff --check`、相关测试、文档链接、架构边界和敏感信息扫描，再用中文 Conventional Commit 提交。
5. 回到 Git history 复核真实完成内容，不把聊天内容当作项目记录。

硬规则以根目录 [`AGENTS.md`](../../AGENTS.md) 为准；日常状态以[状态卡](../08-records/AGENT_STATE_CARD.md)为准；审计级状态以 [`PROJECT_STATUS.md`](../08-records/PROJECT_STATUS.md) 为准。
