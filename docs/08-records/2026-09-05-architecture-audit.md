# 架构演进前置审计

版本：`architecture-audit.v1`；日期：2026-09-05；角色：一次性只读 Audit Agent；代码基线：`2ad59b9ff2552cece954ce875e6c153f54536f1d`。

## 事实

- 审计开始时主工作区干净，`main` 相对本地缓存 `origin/main` ahead 6；未刷新远程状态，也未核验本 SHA 的 GitHub CI。
- [PROJECT_STATUS](PROJECT_STATUS.md) 第 235–247 行与[状态卡](AGENT_STATE_CARD.md) §6 一致：P7D-02R 完成；P7D-03 因独立 Ubuntu 24.04 缺失而阻塞。Phase 7 未闭环。本轮文档工作独立于部署验收。
- 状态卡 §1 的功能基线和 §5 的 CI 链接落后于当前代码；旧链接只证明其对应历史提交，不能证明当前 SHA。

| 审计点 | 基线证据 | 结论与设计影响 |
|---|---|---|
| 分块 | [BusinessIngestionSideEffectHandler](../../apps/ingestion-worker/src/main/java/com/ragforge/ingestion/pipeline/BusinessIngestionSideEffectHandler.java) 第 179–228 行；[摄取文档](../02-architecture/INGESTION_PIPELINE.md) | 该 handler 每文档一个 parent，child 按字符上限和换行切分，无 overlap；语义父块与 token overlap 属于文档目标，不能视为生产 handler 已实现 |
| 检索执行 | [RetrievalService](../../apps/server/src/main/java/com/ragforge/server/retrieval/RetrievalService.java) 第 107–120 行 | 当前 dense 调用完成后才 BM25；文档并行表述是目标。有界并行需独立基线/候选评估 |
| 解析报告 | [ParseReport](../../apps/ingestion-worker/src/main/java/com/ragforge/ingestion/parser/ParseReport.java) 第 7–18 行 | 已有空间、revision、artifact、版本、状态与页数等信息；应细化执行版本关联和质量判断，不重建同名领域模型 |
| 索引验证 | [SpaceCandidateIndexBuilder](../../apps/ingestion-worker/src/main/java/com/ragforge/ingestion/pipeline/SpaceCandidateIndexBuilder.java) 第 57–87 行 | 已有向量维度与 candidate 验证、VALIDATING/READY 状态；增量是解析质量策略与可追溯判定结果 |
| BM25 持久化 | [ADR-0012](../02-architecture/adr/0012-durable-bm25.md) | 已持久化 Qdrant lexical facts；查询 scroll 后重建 BM25，不等于持久倒排索引。保留该决策及其已承认容量限制 |
| 执行组合 | BusinessIngestionSideEffectHandler 第 51–96 行；[领域模型](../02-architecture/DOMAIN_MODEL.md) | 文档已有 PipelineDefinition/PipelineVersion；该 handler 直接串接处理流程。可执行计划与阶段契约是细化方向 |

## 建议与限制

建议以现有版本模型和模块为基础，细化受控阶段执行、解析产物 lineage、质量策略和检索执行快照。继续空间级 RBAC、结构化 Evidence Bundle、显式云出境授权及模块化 Server + Worker；不引入文档 ACL 或第二业务后端。

本审计没有执行运行时测试或全量追踪来源删除/撤权到历史读取链；相关内容是待设计和验证的安全契约，不是已确认漏洞。没有接受 ADR、许可证或发布决策。以上行号对应所记录代码基线，后续变更须重新定位。
