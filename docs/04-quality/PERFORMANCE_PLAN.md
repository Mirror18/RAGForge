# 性能与容量计划

## 1. 目标负载

- 200 用户。
- 20 条并发云/Mock 推理链。
- 100,000 文档或 1,000,000 可检索 child chunks。
- 本地 `qwen3.5:9b` 只做单用户功能验收，不承担 20 并发 SLA。

## 2. 服务目标

| 路径 | 目标 |
|---|---|
| 非 AI API | 服务端 p95 < 300 ms |
| Retrieval | 1M child chunks 下服务端 p95 < 1.5 s |
| SSE first event | p95 < 500 ms（不含排队模型首 token） |
| Ingestion | 不设虚假统一吞吐；按格式分别建立基线 |
| 错误率 | 稳态非预期 5xx < 1% |

生成端到端时延受模型和上下文影响，先记录 TTFT、tokens/s 和总时延分布，再在选定云 Provider 后设 SLO。

## 3. 场景

### 3.1 在线

- 20 并发持续问答，query 长短和命中/拒答比例接近评估集。
- Provider 正常、慢响应、429、timeout、流中断。
- 同时执行索引构建，观察在线检索资源隔离。
- SSE 重连/取消风暴，验证无资源和 usage 泄漏。

### 3.2 摄取

- 10 万小 Markdown。
- 少量超大 PDF/DOCX。
- 10% 文档修改、移动和删除的增量同步。
- OCR 密集任务与普通解析混合。
- RabbitMQ backlog 后恢复，验证背压和数据库/对象/Qdrant 上限。

### 3.3 检索

- 1M child points，不同空间大小和 metadata filter 选择性。
- dense、BM25、RRF、rerank 分阶段计时。
- cold/warm cache、索引发布期间和旧版本回滚。

## 4. 资源观测

记录 CPU、RAM、GC、连接池、线程池、队列、磁盘 IOPS、网络、Qdrant latency、Valkey hit、Provider latency、token 和 GPU/VRAM（本地模型）。报告注明主机：i7-13620H、32GB RAM、RTX 4060 Laptop 8GB，以及容器资源限制。

## 5. 方法

1. 固定版本、数据集和环境，预热后测量。
2. 先单用户分解各阶段，再逐步增加并发找到拐点。
3. 同时报告 p50/p95/p99、吞吐、错误和资源，不只给平均值。
4. 稳态、突发和 soak test 分开。
5. 将测试定义和摘要提交仓库，大体积原始结果放 artifact storage 并保存 hash。

## 6. 容量保护

- API rate limit、Provider concurrency semaphore 和 per-space quota。
- 有界线程/连接池和 RabbitMQ prefetch。
- OCR 与普通任务不同队列/worker concurrency。
- context/token 上限和文档/网页/上传大小限制。
- backpressure 优先于无界排队；向用户返回可解释的 retry-after。
