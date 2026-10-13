---
id: doc-4
title: 业务闭环优化方向
type: guide
created_date: '2026-10-12 16:20'
updated_date: '2026-10-13 14:20'
---
# 业务闭环优化方向

本文记录对现行业务实现的判断。事实来自代码；建议是执行顺序，不是重写单体。

## 事实

回答路径在 `RAGAnswerService`：授权 → 向量 → 检索 → 版本化 prompt → 生成 → 用 Evidence Bundle 投影引用。无证据、非法引用或出境不匹配一律拒绝。`object-storage.enabled=false` 时 Spring 装配 fail-closed 端口，真正生产图要打开对象存储。

检索是 Dense + BM25 + RRF + rerank。BM25 按 `space_id + index_version_id` 缓存（RF-19）。新空间 Worker 种子 profile 为 `m1-zh-default-v1`（dense/BM25 top-k 8/8、权重 0.8/0.4、最多 6 child、768 token）；已有 active profile 不自动改写。摄取分块对齐 `p4-default-v1`（RF-18）。

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

## 执行顺序

里程碑 `m-1` 按下面顺序做。同一波里没有依赖的卡可以并行，但检索相关卡必须串行。ADR-0014 模块拆分在 `m-2`，排在本表全部完成之后。

| 顺序 | 卡片 | 做什么 | 依赖 |
|---|---|---|---|
| 1 | RF-11 | 创建回答可省略技术 ID，服务端按空间解析 | 无 |
| 1 | RF-15 | 回答就绪检查，列出缺口和下一步 | 无 |
| 2 | RF-16 | 问答页只收集问题 | RF-11、RF-15 |
| 2 | RF-17 | 资料页按缺口引导，单套绑定不再手填 ID | RF-15 |
| 3 | RF-12 | 拒绝和错误码变成中文下一步 | RF-11、RF-16 |
| 4 | RF-6 | 浏览器走通登录到带引用回答 | RF-12、RF-16、RF-17 |
| 5 | RF-18 | 摄取分块对齐 `p4-default-v1`，并做离线评估 | RF-16 |
| 6 | RF-19 | BM25 按 index version 查，停止全量 scroll | RF-18 |
| 7 | RF-13 | 按评估结果调整新空间默认检索档 | RF-19 |
| — | RF-14 | 产品配置去掉 phase 字样，可与 5 之后并行 | 无 |
| — | RF-20 | 检索台和分块台预填活跃版本 | RF-15 |
| — | RF-21 | 引用 JSON 多接受一种形状，仍校验 evidence ID | RF-11 |

RF-14、RF-20、RF-21 不阻塞 RF-6。RF-18 起会改变检索行为，必须带 `tests/evidence/` 摘要。
