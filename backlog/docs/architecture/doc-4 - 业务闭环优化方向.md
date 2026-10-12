---
id: doc-4
title: 业务闭环优化方向
type: guide
created_date: '2026-10-12 16:20'
updated_date: '2026-10-12 16:20'
---
# 业务闭环优化方向

本文记录对现行业务实现的判断。事实来自代码；建议是执行顺序，不是重写单体。

## 事实

回答路径在 `RAGAnswerService`：授权 → 向量 → 检索 → 版本化 prompt → 生成 → 用 Evidence Bundle 投影引用。无证据、非法引用或出境不匹配一律拒绝。`object-storage.enabled=false` 时 Spring 装配 fail-closed 端口，真正生产图要打开对象存储。

检索是 Dense + BM25 + RRF + rerank。生产 BM25 每次 search 全量 scroll Qdrant 再在内存算分。Worker 种子 profile 上下文约 512 token、最多 5 个 child。服务端 `ChunkingStrategy.p4DefaultV1()` 与 Worker 按字符 `MAX_CHILD_CHARS=2000` 切开，两套默认不一致。

产品面仍要求提问者带齐 route / profile / prompt / index 的 UUID 或 hash。`application.yml` 和对象前缀仍出现 `phase3`、`phase6`。云端 Chat/Embedding 按空间授权 fail-closed，这一点应保留。

## 对使用者的影响

要得到一条带引用的回答，需要开空间、开 MinIO/outbox/worker、配连接与出境、导入、等索引发布、再填技术 ID。任一步失败多是英文泛化拒绝。检索即便跑通，双分块与全量 BM25 也会让证据不稳。

## 决策

先做 **空间已就绪后只提问就能得到结构化引用答案**。不先拆 Maven 四进程，也不先重写检索内核。

| 优先级 | 方向 | 原因 |
|---|---|---|
| P0 | 自动绑定当前空间的 active index / profile / prompt / route；UI 只问问题；拒绝映射成可操作下一步；产品配置名去掉 phase 字样 | 决定能不能用起来 |
| P1 | 对齐摄取分块与 `ChunkingEngine`；BM25 不再每请求全量 scroll；按中文资料调默认上下文与权重 | 决定跑通后像不像有证据 |
| P2 | Playground/Studio 预填活跃版本；citation JSON 对模型容错但仍校验 evidence ID | 降低排障成本 |

## 不在本轮做

- 把 parser、OCR、rerank 拆成独立服务
- 未授权空间静默走云端
- 把引用改成模型自由文本
- 在没有离线评估的情况下改 chunk / retrieve / prompt / rerank 的默认分数逻辑

对应卡片在里程碑 `m-1`。ADR-0014 的模块拆分在 `m-2`，排在产品闭环之后。
