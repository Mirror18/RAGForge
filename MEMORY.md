# RAGForge Agent 记忆（Agent Memory）

> 本文件是本仓库 AI Agent 的持久化记忆，用于跨会话沉淀对本项目的理解、约定与进展，属于"init 初始化"产出。
> 使用方式：每个会话开始时先读取本文件；当有值得沉淀的新认知（项目状态变化、新约定、踩坑、关键决策）时，更新"会话记录"与"更新日志"。
> 仓库硬性规则以 [AGENTS.md](AGENTS.md) 为准；项目状态以 [PROJECT_STATUS.md](docs/08-records/PROJECT_STATUS.md) 为准；阶段划分见 [ROADMAP.md](docs/03-delivery/ROADMAP.md)。

## 1. 事实（Facts）

- RAGForge 是面向企业内部、单租户多用户场景的通用 RAG 知识助手**学习型工程**；首要目标是完整实践商业项目从产品定义、架构、研发、测试、安全、交付到运维复盘的全过程，而非快速堆出一个聊天页面。
- 当前阶段：**Phase 4 In Progress**（2026-08-15 启动）。Phase 4 入口为 Chunk Studio、索引与检索；带引用的 RAG 问答（Phase 5）尚未实现。
- 技术基线：Java 21 + Spring Boot 3.5.x + Spring AI 1.1.x（模块化单体）；独立 Java 摄取 Worker（共享领域契约，不共享运行生命周期）；Python AI Runtime 仅承载 OCR/rerank；Vue 3 + TypeScript 单一角色感知 SPA；PostgreSQL + Qdrant + RabbitMQ（Transactional Outbox）+ Valkey（Redis 兼容）+ S3-compatible 对象存储；OpenTelemetry + Prometheus + Grafana + Loki + Tempo。
- 本地模型：Ollama + `qwen3.5:9b`；预留通用 OpenAI-compatible API。
- 首批数据源：文件上传、本地目录、Git、受控网页抓取（Web connector 可在 Phase 3 末或 Phase 5 前完成）。
- 阶段推进：Phase 0 竞品基准/许可证闸门 → Phase 1 工程与领域骨架 → Phase 2 Provider/Prompt/Run 纵向切片 → Phase 3 版本化摄取流水线 → Phase 4 Chunk Studio/索引/检索 → Phase 5 带引用问答与只读 Agent → Phase 6 评估/观测/安全/恢复 → Phase 7（路线图后续）。
- 权限模型：空间级 RBAC，角色为 Platform Admin、Space Admin、Editor、Viewer。

## 2. 非协商规则（来自 AGENTS.md，必须遵守）

- 保持"模块化单体 + 独立摄取 Worker"架构，直到 ADR 证明拆分必要。
- `space_id` 是安全边界：所有触及租户内容的查询与变更都必须包含并强制 `space_id`。
- 云端数据出境按空间 opt-in，禁止从本地路由静默 failover 到云端。
- 回答必须保留文档/chunk provenance；不得以生成式自由文本实现引用。
- 不提交密钥、个人 Obsidian 内容、模型凭据、生产数据或原始客户提示词。
- 第三方源码必须先过许可证闸门并更新复用登记表；架构变更需 ADR；产品范围变更需更新 PRD/路线图/风险/追溯矩阵。
- 多 Agent 协作：一个 orchestrator 负责分解与集成；任务一分支一 worktree；分支名 `codex/<phase>-<task>-<agent>`；worker 不得直接修改主 worktree。
- 中文提交规范：`<type>(<scope>): <中文摘要>`，如 `feat(ingestion): 完成 Git 数据源增量检查点`。

## 3. 文档导航（快速定位）

- 产品：docs/01-product/（PRD、USER_STORIES）
- 架构：docs/02-architecture/（ARCHITECTURE、DOMAIN_MODEL、INGESTION_PIPELINE、RETRIEVAL_AND_CHAT、API_AND_EVENTS、adr/ 共 9 项 Accepted ADR）
- 交付：docs/03-delivery/（ROADMAP、DEFINITION_OF_DONE、MULTI_AGENT_LOOP_PROMPT、各阶段 CHECKLIST）
- 记录：docs/08-records/（PROJECT_STATUS、RISK_REGISTER、TRACEABILITY_MATRIX、阶段 implementation results 与 retrospectives）
- 质量：docs/04-quality/（TEST_STRATEGY、RAG_EVALUATION、PERFORMANCE_PLAN）
- 安全：docs/06-security-compliance/（SECURITY_BASELINE、THREAT_MODEL、DATA_EGRESS_AND_RETENTION）

## 4. 当前状态与下一步

- 已完成：Phase 0 基准（33 条问题）；Phase 1 工程/领域骨架（契约、RBAC、审计/outbox、幂等、CI）；Phase 2 Provider/Prompt/Run（本地真实 Ollama 验收通过）；Phase 3 版本化摄取（文件/本地目录/Git connector、版本化 schema、Outbox/RabbitMQ/worker 幂等、原生解析 + 真实 Tesseract OCR、Local/MinIO 对象存储）。
- Phase 4 待办核心：父子分块、引用锚点、override/NEEDS_REVIEW；embedding profile 与缓存、Qdrant index version；dense + BM25 + RRF + rerank + parent expansion；Candidate index 验证、active pointer、24 小时旧索引保留；Chunk Studio 与 Retrieval Playground。
- Phase 4 执行计划与 Checklist 已建立：`docs/08-records/phase-4/PHASE_4_EXECUTION_PLAN.md`、`docs/03-delivery/PHASE_4_CHECKLIST.md`（P4-A..H 任务所有权）。P4-B 契约已冻结：`chunking-domain.v1`、`index-version.v1`、`retrieval-profile.v1`（contract tests 39/39）。
- Phase 4 退出条件：30 问基准 Recall@10 / MRR@10 达阶段阈值；100 万 child chunk 数据量下检索目标有可复现证据；空间过滤、索引切换与回滚测试通过；人工 override 的源更新冲突不会静默覆盖。
- 必须沿续的边界：`space_id`、revision/artifact immutable、provenance、at-least-once 幂等。

## 5. 会话记录

### 2026-08-15 会话（Phase 4 启动）
- 澄清任务列表：`.github/modernize/java-upgrade/` 的 Java 21→25 + Spring Boot 4.0 升级计划是现代化工具自动生成的临时产物（gitignored、分支已删除、progress 全未执行），不是项目计划；已按用户确认排除，技术基线维持 Java 21 + Spring Boot 3.5.x（ADR-0002），未安装任何 JDK、未改任何代码。
- 按用户确认开始 Phase 4：在 main 提交执行计划与 Checklist（`23b4e6d`）；P4-B 契约（`2e68d6b`）与 P4-C 持久化（`23dbc88`，V9 migration + Chunk/Index/RetrievalProfile repositories + 状态机）在 `codex/p4-chunk-index-a1` worktree 完成并验证后，以 `8138e85` merge(p4) 合入 main。根 reactor BUILD SUCCESS（server 101/101、worker 28/28），contract 39/39、format/link/secret 门禁通过；已清理 worktree 与分支（含 Phase 3 遗留 `codex/p3-ocr-runtime-main`）。
- P4-D 进展：在 `codex/p4-chunk-engine-a1` worktree 实现 `ChunkingStrategy`（p4-default-v1：parent 1200/child 400/overlap 40）、`TokenEstimator`（确定性 CJK/ASCII 估算）、`ChunkCandidate`、`ChunkingEngine`（标题/表格/代码/列表边界感知，句子/行/列表项边界拆分，引用锚点 headingPath + 1-based lineRange，SHA-256 hash，确定性输出）与 `ChunkingEngineTest`；单元验证被中断待重跑，尚未提交合入。
- 环境注意：`python` 命令是 uv shim（缓存 ACL 报错），需 `$env:UV_CACHE_DIR=$env:TEMP\uv-cache-p4; $env:UV_PYTHON_INSTALL_DIR=$env:TEMP\uv-python-p4; uv run --no-project --python 3.12 python ...` 才能运行 Python 脚本；Docker Desktop 未运行，P4-C 数据库集成测试前需启动。

### 2026-08-15 会话（初始化）
- 探索全仓库确认：docs/config/scripts/contracts/apps/.env.example 中均无 "memory/记忆" 概念；无既有记忆文件；无 init 记忆脚本；仓库中唯一的 agent 相关文件是根目录 [AGENTS.md](AGENTS.md)。
- 按用户指示执行 init：创建本记忆文件。
- 注意：工作区存在用户本地未提交改动（README.md、apps/web/src/App.vue、apps/web/vite.config.ts、scripts/dev/README.md、scripts/dev/start-local.bat、scripts/dev/start-local.ps1），不属于本会话产出，不得改动或提交。
- 评估结论：AGENTS.md 作为 Agent 操作规范质量高（space_id 安全边界、worktree 隔离、中文提交、阶段证据闭环、DoD/质量门禁接线均覆盖）；作为商业治理载体有缺口——代码评审/CI 硬门禁、人工审批点、安全评审环节、发布/回滚、事件响应、依赖漏洞时限未接线进 Agent 执行循环。
- 已按用户确认修订 AGENTS.md，补齐 5 类缺口：① 非协商规则新增高风险动作人工审批点（ADR 接受/许可证接受/云出境开启/生产迁移/发布）；② 集成敏感文件列表加入 AGENTS.md 自身；③ 合并门禁硬性化（CI 全绿 + 评审无未解决意见 + DoD 满足）并新增安全敏感变更安全评审；④ 新增 Release and versioning（SemVer/CHANGELOG/回滚点/人工决策）与 Security incidents and dependency response（SECURITY.md 上报、24h 分诊、依赖更新节奏）章节；⑤ 新增本文件自身治理规则。新增引用文档链接已逐一验证存在。

## 6. 假设与开放问题

- 假设：用户希望记忆文件保存在仓库内并可见（未加入 .gitignore）。如希望从版本控制中排除，可把 MEMORY.md 加入 .gitignore。
- 开放：Obsidian 同步策略尚未定；项目进入稳定开发后再决定同步何种摘要。

## 更新日志

- 2026-08-15：初始化本记忆文件（首次 init）。
- 2026-08-15：澄清并排除现代化工具生成的 Java 升级计划（基线维持 Java 21 + Boot 3.5.x）；启动 Phase 4（计划/清单 + P4-B 契约已提交，门禁通过）。
- 2026-08-15：P4-B/P4-C 批次合入 main（`8138e85`，reactor 全绿）；P4-D 分块引擎在 worktree 实现，单元验证待续。
- 2026-08-15：完成 AGENTS.md 商业项目合规性评估（操作规范达标，治理接线有缺口）。
- 2026-08-15：按评估结论修订 AGENTS.md（人工审批点、硬合并门禁、安全评审、发布/版本、事件与依赖响应、文件自身治理），diff 已验证。
