# ADR-0013：受控知识执行与版本化检索快照

- Status: Accepted
- Date: 2026-09-05
- Proposal Version: `knowledge-execution-proposal.v1`

## Context

RAGForge 已有版本化摄取、Pipeline/Job/阶段记录、解析产物、索引验证与原子发布，以及混合检索、Evidence Bundle 和引用校验。本提案不是重新引入这些基础能力。

本轮局部审计发现生产摄取 handler 的父子分块比架构文档目标简化，检索服务中的 dense 与 BM25 当前顺序执行。进一步演进需要先固定可验证的执行身份、质量判定与线上离线路径，避免把目标文档当作运行事实。

开源知识库的解析检查、来源与处理边界、同步生命周期，以及 Haystack 的组件契约提供设计参考；没有采用上游源码或平台整体架构。

## Decision

**Decision accepted on 2026-09-05 by the project owner.** 本 ADR 已成为架构约束；实施仍须按看板拆成独立卡片，完成契约、迁移、安全审查和验证后才可修改运行架构、可执行契约或数据库。

1. 保留模块化单体加独立 ingestion worker；在现有 PipelineVersion/阶段记录上表达固定有限依赖、版本输入、幂等重试、租约 fencing 与显式重放。
2. 复用 ParsedArtifact/ParseReport，补可验证 manifest 和版本化质量判定，纳入已有索引验证及原子发布边界。
3. 提出 RetrievalExecutionSnapshot，使 Chat、Playground、Evaluation 通过同一 application port 执行，固定 index/profile/模型及有效参数，记录实际降级结果。
4. 保留 `space_id`、显式出境批准和结构化 provenance；当前授权和墓碑检查覆盖历史派生内容、缓存及重放，沿用已有 Deletion Job。
5. 沿用 ADR-0012 的 Qdrant durable lexical payload 与查询时统计重建，不新增倒排服务；分块策略或召回并行优化另行评估立卡。

精确命名、不变量、失败模型、迁移顺序和验收矩阵统一定义于[演进总设计](../ARCHITECTURE_EVOLUTION.md)，各架构文档只保留提案入口，避免复制多份规则。

## Consequences

- 增加 manifest、质量证据和执行快照的存储与版本兼容成本；具体表结构尚未决定。
- 重放可解释性增强是设计目标，外部模型输出仍可能不确定；没有质量、时延或成本收益实测。
- 当前权限优先于历史可读性；已删除或撤权内容的历史回答可能只显示不可用状态，不能靠旧快照恢复正文。
- 应用、索引与备份回滚必须保留删除记录及再授权边界；新功能开关不能绕过安全约束。
- 后续实施需单卡预算、契约/迁移 owner、安全审查、失败恢复测试；涉及 RAG 行为变化必须提供离线对比。

## Alternatives

- 维持现状，只补文档：成本最低，但不能形成跨入口执行快照与产物质量的一致约定。
- 引入完整通用工作流平台：配置自由度更高，但增加运行及权限复杂度，当前没有必要性证据。
- 直接替换检索/索引后端：不能解决 lineage 和历史授权，且扩大本次文档任务范围。
- 仅保存请求时权限：有利于机械重放，却不能满足撤权和删除后的当前访问边界，因此不采用。

## References

- [ADR-0006：版本化摄取和索引原子发布](0006-versioned-ingestion-and-indexes.md)
- [ADR-0012：持久化 BM25](0012-durable-bm25.md)
- [演进总设计与验收计划](../ARCHITECTURE_EVOLUTION.md)
- [2026-09-05 开源对标记录](../../07-research/2026-09-05-knowledge-architecture-benchmark.md)
