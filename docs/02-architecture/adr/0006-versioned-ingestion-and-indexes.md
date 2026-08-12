# ADR-0006：版本化摄取与索引原子发布

- Status: Accepted
- Date: 2026-08-12

## Context

解析器、分块、embedding 和源文档都会变化。若原地覆盖线上向量，失败和半更新会导致不可解释回答，也无法复现实验。

## Decision

Document Revision、Pipeline Version、Artifact、Chunk、Embedding 和 Index Version 均可追溯。新索引在隔离状态构建并验证，通过 PostgreSQL active pointer 原子发布。旧索引至少保留 24 小时并按引用和保留策略清理。

## Consequences

- 可回滚、A/B 比较和复现回答。
- 构建期需要额外存储容量。
- Schema 和任务必须显式携带版本，不能依赖“当前配置”。
