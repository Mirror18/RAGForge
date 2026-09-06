# Phase 4 Retrieval Contracts

本目录保存检索侧机器可读合同。当前合同状态为 `planned`，`implementationStatus=contract` 表示接口与语义已冻结，不表示运行时已经实现。

## Retrieval Profile v1

[`retrieval-profile.v1.schema.json`](./retrieval-profile.v1.schema.json) 固定不可变、版本化的检索配置：

- dense top-k 与 BM25 top-k 并行召回。
- RRF 合并去重（k 与权重可配置）。
- rerank 后按 `maxContextChildren` 裁剪。
- parent/neighbor 扩展受 `maxContextTokens` 预算约束。
- 空间过滤是硬性边界：检索请求缺少 `spaceId` 直接失败，不提供“全局默认”。

默认值不是硬编码真理；只有通过评估阈值和人工抽样的配置才能发布为 active profile（见 [RAG_EVALUATION](../../docs/04-交付路线与质量门禁.md)）。

## Retrieval Execution Snapshot v1

[`retrieval-execution-snapshot.v1.schema.json`](./retrieval-execution-snapshot.v1.schema.json) 固定一次检索执行使用的空间、索引、profile、有效召回参数、route/executor 版本、降级策略和 Evidence Bundle identity。快照只保存受控引用、参数和 hash，不保存查询原文、向量、凭据或可搜索正文。

快照是 immutable、space-scoped 的执行记录。同一 `executionKey` 重试必须返回同一身份；内容或空间不一致时 fail-closed。重放前仍须执行当前空间授权、墓碑和出境策略检查，旧快照不能授予新权限。
