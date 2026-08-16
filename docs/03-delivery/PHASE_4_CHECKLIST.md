# Phase 4 Chunk Studio、索引与检索 Checklist

状态：`phase4-in-progress`（2026-08-15）。执行计划与所有权见 [PHASE_4_EXECUTION_PLAN.md](../08-records/phase-4/PHASE_4_EXECUTION_PLAN.md)。技术基线维持 Java 21 + Spring Boot 3.5.x（[ADR-0002](../02-architecture/adr/0002-java-ai-version-baseline.md)）。

进度（2026-08-15）：P4-B 领域契约与 P4-C 持久化批次已合入 `main`（merge `8138e85`）；根 reactor `mvn test` BUILD SUCCESS（server 101/101、worker 28/28），contract 39/39、format/link/secret 门禁通过。P4-D 分块引擎已在 worktree `codex/p4-chunk-engine-a1` 实现（`ChunkingStrategy`/`TokenEstimator`/`ChunkingEngine`/`ChunkingEngineTest`），单元验证待续。

## 一、契约与领域门禁

- [ ] P4-CONTRACT-01 父子分块契约固定 parent 1000–1500 tokens、child 300–500 tokens、适度 overlap，child 保存 parent ID、文档位置、标题路径、页码/工作表/幻灯片和字符/token 范围；表格、代码、列表和标题边界使用专用策略，不能只按字符硬切。
  - 进度：schema/契约测试已冻结（contract 39/39，`chunking-domain.v1.schema.json` + fixtures）；`ChunkingEngine`/`ChunkingEngineTest` 已在 P4-D worktree 实现，确定性/边界锚点验证通过后勾选。
  - 验收条件：chunk 域 schema 可被 contract test 解析；分块确定性、边界锚点可验证；所有内容实体强制 `space_id`、稳定 ID、版本和 provenance。
  - 证据：`contracts/ingestion/chunking-domain.v1.schema.json`（或同级目录）、chunking contract test、`ChunkingEngineTest`。
  - 验收命令：`python scripts/ci/contract_test.py`；`python -m unittest discover -s tests/contract -p "test_phase4_*.py" -v`；对应 Maven unit tests。
  - 环境前置：合成 Markdown/PDF/DOCX/表格/代码 fixture；不需要 embedding 模型。

- [x] P4-CONTRACT-02 ChunkOverride 状态机固定 `NONE -> ACTIVE -> NEEDS_REVIEW -> ACTIVE` 或 `-> DISCARDED`；源 revision 更新后旧 override 转 `NEEDS_REVIEW`，不得自动应用到新文本。
  - 验收条件：override 记录可审计（创建者、理由、版本、空间）；冲突时既不静默覆盖也不静默丢弃。
  - 证据：`chunking-domain.v1` 状态机契约、V9 `chunk_overrides` CHECK、`ChunkOverrideTransitions` 纯逻辑 + `ChunkRepository` 版本追加；集成测试覆盖 ACTIVE->NEEDS_REVIEW->ACTIVE/DISCARDED、跨 revision 强制 NEEDS_REVIEW、DISCARDED 不可回退（`Phase4ChunkIndexPersistenceIntegrationTest`，随 `8138e85` 合入）。

- [x] P4-CONTRACT-03 IndexVersion 生命周期固定 `BUILDING -> VALIDATING -> READY -> ACTIVE -> RETIRED` 与 `FAILED`；新索引写入隔离 candidate 并验证，发布只切换 PostgreSQL active pointer；旧索引至少保留 24 小时。
  - 验收条件：VALIDATING 检查文档数、chunk 数、向量维度、孤儿关系、抽样检索和空间过滤；失败不污染 ACTIVE。
  - 证据：`index-version.v1` 契约、V9 `index_versions` CHECK（ACTIVE 必须校验通过、activated/retention 约束）、`IndexStateTransitions` + `IndexRepository`（candidate→VALIDATING→READY→ACTIVE 原子指针切换、FAILED、单行 `active_index_pointers`、24h 保留断言）；Qdrant 侧校验随 P4-E。

- [x] P4-CONTRACT-04 RetrievalProfileVersion 不可变，固定 dense/BM25 的 top-k、RRF、rerank、parent expansion 与过滤器参数；默认值不是硬编码真理。
  - 验收条件：profile 变更产生新版本；运行引用确切 profile/index 版本；A/B 对比可复现。
  - 证据：`retrieval-profile.v1` 契约、V9 `retrieval_profiles` 不可变触发器 + 参数 CHECK、`RetrievalProfileRepository` 版本追加与单行 `active_profile_pointers`、Java 侧边界校验；评估对比随 P4-F/P4-H。

- [ ] P4-CONTRACT-05 空间过滤强制：检索、索引切换、chunk 查询和 override 缺少 `space_id` 时直接失败，不提供“全局默认”；跨空间访问不泄漏。
  - 进度：V9 全表 `space_id` 强制 + 组合 FK 拒绝跨空间引用已有集成测试；检索层缺少 `space_id` 直接失败属 P4-F，完成后再勾选。
  - 验收条件：跨空间检索返回空或拒绝；索引 collection/key 不可伪造跨空间。
  - 证据：security tests、空间过滤集成测试。

- [ ] P4-CONTRACT-06 embedding cache key 幂等：至少包含 normalized text hash、model profile version 和维度；内容 hash 相同且配置未变时允许复用 embedding。
  - 验收条件：cache 命中不重复计费/调用；key 变更使旧缓存失效而不是错配。
  - 证据：cache key 单元测试、embedding 幂等集成测试。

## 二、ROADMAP 阶段退出条件

- [ ] P4-EXIT-01 30 问基准 `Recall@10` 和 `MRR@10` 达成阶段阈值（>= 0.90 / >= 0.75，见 [RAG_EVALUATION](../04-quality/RAG_EVALUATION.md) §4.1）。
  - 量化门槛：30 问固定切片、版本化 dataset/index/profile 配置；`Recall@10 >= 0.90` 且 `MRR@10 >= 0.75`。
  - 证据：`tests/evidence/phase4-retrieval-benchmark.json`（含数据集版本、代码 commit、index/profile 版本、聚合与按类别切片）。

- [ ] P4-EXIT-02 100 万 child chunk 数据量下检索目标有可复现证据。
  - 量化门槛：100 万 child chunk 规模下 p95 检索时延与召回目标可复现；报告机器、数据生成 seed、Qdrant 配置和索引版本。
  - 证据：规模测试脚本与报告（合成数据，不提交生产数据）。

- [ ] P4-EXIT-03 空间过滤、索引切换和回滚测试通过。
  - 证据：跨空间过滤测试、candidate 发布/回滚测试、旧索引保留与清理测试。

- [ ] P4-EXIT-04 人工 override 的源更新冲突不会静默覆盖。
  - 证据：override 冲突集成测试（源更新 -> NEEDS_REVIEW -> 人工决定 ACTIVE/DISCARDED）。

## 三、质量与评估门禁

- 每次 chunker/embedding/retrieval/rerank 变更必须运行离线评估对比并记录测试配置版本（AGENTS.md 质量门禁、[RAG_EVALUATION](../04-quality/RAG_EVALUATION.md) §7）。
- 零容忍：跨空间泄漏、Evidence 外引用、未授权云端调用；未达门槛的候选不得成为 active profile。
- 合并门禁沿用 [DEFINITION_OF_DONE](DEFINITION_OF_DONE.md)：CI 全绿、评审无未解决意见、DoD 满足；安全敏感变更（权限/数据边界/出境）需安全评审。
- 环境门禁沿用 Phase 1–3：格式、架构、secret、Markdown link、依赖清单、contract、SBOM/SCA 检查必须通过。
