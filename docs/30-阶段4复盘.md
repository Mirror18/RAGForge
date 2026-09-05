# Phase 4 Chunk Studio、索引与检索阶段复盘

- 日期：2026-08-21
- 状态：`accepted`
- 主干基线：`e27ae75f085e3a4fe3d39fa0b00b5ceecb931bd2`
- 阶段闭环：待本记录与全局门禁提交合入后，以最终中文阶段闭环 commit 为准

## 已完成

- 父子分块引擎固定 parent/child token 区间、标题/表格/代码/列表边界、overlap、provenance anchor 和确定性输出。
- embedding cache key 固定 space、normalized text hash、model profile version 和维度；同配置命中，配置变化隔离旧缓存。
- candidate index 采用 BUILDING/VALIDATING/READY/ACTIVE/RETIRED/FAILED 生命周期；验证通过后原子切换 PostgreSQL active pointer，旧索引至少保留 24 小时。
- 检索链路完成 dense、BM25、RRF、确定性 lexical rerank、parent/neighbor bounded expansion；trace 只返回 provenance 和阶段元数据，不返回 query vector 或 searchable text。
- Chunk Studio 和 Retrieval Playground 完成 space-scoped RBAC、敏感字段裁剪、override 审计、NEEDS_REVIEW 流转和候选 profile A/B 展示；前端 format/build 通过。

## 阶段门禁结论

- P4-EXIT-01：30 问固定切片 `q-001..q-030`，Recall@10 `0.965517`、MRR@10 `0.827586`，forbidden source leak `0`。29 个有允许 evidence reference 的 case 用于数值指标，abstention/security probe 单独保留在逐例结果中。
- P4-EXIT-02：Qdrant `v1.11.5` 真实装载 1,000,000 个 synthetic child points，4 个 space，`space_id + index_version` filter；Recall@10 `1.0`、p95 `1101.3382 ms`、p99 `1206.9538 ms`。容器运行后自动清理，报告不含生产数据。
- P4-EXIT-03：PostgreSQL/Qdrant Testcontainers 覆盖跨空间 chunk/retrieval、candidate validation、active pointer 切换、失败不污染 active、24 小时 retention 和旧索引退役；targeted Maven 17/17。
- P4-EXIT-04：源 revision 更新强制旧 override 为 `NEEDS_REVIEW`；只能通过审计流转到 `ACTIVE` 或 `DISCARDED`，非法直接跳转被拒绝，未发生静默覆盖。

## 做得好的地方

- 把合同、migration、repository、检索和 UI 分成单一 owner，避免了共享 schema 与 V9/V10 migration 的并行冲突。
- 早期加入 Qdrant scope filter、provenance matching 和敏感字段测试，随后 1M 探针可以直接复用同一 `space_id + index_version` 边界。
- trace 从“每个阶段独立重跑”收敛为单次检索执行，避免 Playground A/B 展示产生重复检索和不一致结果。
- 评测报告显式保留指标分母和 abstention/security case，避免把无允许证据的 case 偷换成普通命中率。

## 需要改进

- BM25 当前是进程内确定性 provider，重启后的 lexical index 需要重建；Phase 5/6 应在选型后增加 durable provider、重启恢复和容量证据。
- 1M Qdrant 探针使用 8 维合成向量、100 次查询和单机 Docker；它满足当前阶段的合成规模门槛，但不能外推生产 embedding 维度、20 并发、索引重建和混合负载。
- 30 问检索切片已经达标，但全量 120+ generation/citation/abstention 评估尚未进入本阶段；Phase 5/6 必须继续使用相同 dataset/config/version 记录。
- 本地 SBOM dependency scan 仍为 placeholder；正式发布前必须执行 required 模式并保存 Syft/Grype/许可证证据。

## 下一阶段入口

Phase 5 进入 Evidence Bundle、citation validator、版本化 RAG prompt、带引用回答和只读安全工具。保留 `space_id`、revision/artifact immutable、provenance、Evidence 外引用零容忍及禁止未授权云端调用等不变量；同时跟踪 R-003、R-005、R-006、R-012、R-021、R-023、R-024。
