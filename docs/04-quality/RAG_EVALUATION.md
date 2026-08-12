# RAG 评估方案

## 1. 原则

- 评估集、配置、模型和结果全部版本化。
- 检索与生成分开评估，避免用“回答看起来不错”掩盖召回失败。
- 自动指标必须配合人工抽样；LLM-as-judge 记录 judge 模型/Prompt 并校准偏差。
- 核心事实存 RAGForge；第三方工具可以执行或可视化，不能成为唯一历史库。

## 2. Dataset Schema

每条 Case 建议包含：

- `case_id`, `dataset_version`, `space_fixture`。
- question、question category、language、difficulty。
- expected document revisions/chunks/claims。
- reference answer 或关键事实列表。
- answerability：ANSWERABLE / UNANSWERABLE / CONFLICTING。
- required/forbidden citations。
- tags：markdown/table/pdf/ocr/multi-hop/security/freshness 等。
- 人工标注者、复核状态和变更原因。

## 3. 首版 120 条分布

| 类别 | 数量建议 |
|---|---:|
| 单文档事实 | 25 |
| 标题/列表/代码/表格结构 | 20 |
| 多段或多文档综合 | 20 |
| 扫描 PDF / OCR | 10 |
| 同名/相似语义干扰 | 10 |
| 时间/版本冲突 | 10 |
| 无答案应拒答 | 15 |
| 权限/提示注入/恶意文档 | 10 |

## 4. 指标

### 4.1 Retrieval

- Recall@5/10/20。
- MRR@10。
- nDCG@10（存在分级相关性时）。
- first relevant rank、zero-result rate。
- filter correctness 和 cross-space leakage。

MVP 门槛：`Recall@10 >= 0.90`、`MRR@10 >= 0.75`。

### 4.2 Generation

- Claim faithfulness：回答 claim 是否由 evidence 支持。
- Citation precision：引用是否真正支持对应 claim。
- Citation coverage：需要引用的 claim 是否有引用。
- Answer correctness/completeness。
- Abstention accuracy：无答案、权限不足或证据冲突时是否正确拒答。

MVP 门槛：faithfulness、citation precision、abstention accuracy 各 `>= 0.90`。

### 4.3 运行代价

- end-to-end、retrieval、rerank、TTFT、generation latency。
- input/output/embedding tokens、调用数、估算/报告成本。
- timeout、retry、degraded route 和 cancel rate。

## 5. 对照实验

每次 Run 保存：dataset version、code commit、pipeline/index/retrieval/prompt/model versions、hardware、concurrency、seed/temperature、judge version 和运行时间。

比较报告至少包括：

- 聚合指标与置信区间/样本量。
- case-level win/loss/tie。
- 质量、延迟、成本三者 trade-off。
- 按类型切片，避免总分掩盖 OCR 或拒答退化。
- 必须人工复核的最大退化样本。

## 6. 工具边界

[Promptfoo](https://github.com/promptfoo/promptfoo) 用于 CI 中 prompt/model matrix、断言和 red-team；核心结果导入本项目 Evaluation Run。[Langfuse](https://langfuse.com/integrations/frameworks/spring-ai) 可通过 OpenTelemetry 观察线上 Trace，但不替代离线基准。RAGFlow 的 [Retrieval Test](https://github.com/infiniflow/ragflow/blob/main/docs/guides/dataset/run_retrieval_test.md)用于产品交互参考。

## 7. 发布门禁

- parser/chunker/embedding 变更必须重建候选索引并跑 retrieval 全集。
- prompt/chat/rerank 变更运行 generation 全集。
- 能力声明变化运行 provider contract + 相关评估切片。
- 零容忍：跨空间泄漏、Evidence 外引用、未授权云端调用。
- 未达门槛的候选不得成为 active；接受质量退化必须有明确业务收益和 ADR。
