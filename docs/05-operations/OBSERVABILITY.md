# 可观测性设计

## 1. 目标

从用户看到的一个错误或答案，能追到 HTTP/SSE、授权、Run/Step、检索、索引、消息、Worker、工具、模型和成本。日志、指标和 Trace 使用相同 correlation/run/job 标识。

## 2. 信号

### 2.1 Traces

- HTTP request、database、Rabbit publish/consume、object/Qdrant calls。
- ingestion job/step、chat run/step、provider/tool invocation。
- attributes 使用 ID、版本、状态、耗时、token；不默认记录完整 prompt/document。
- baggage/headers 跨 server、worker、AI runtime 传播 correlation 和 trace context。

### 2.2 Metrics

- RED：request/run rate、error、duration。
- USE：CPU/GPU/内存/磁盘/连接池/队列 utilization、saturation、errors。
- 摄取：queue depth/age、jobs、step duration、parser/OCR failures、DLQ。
- 检索：dense/BM25/rerank latency、candidate count、zero result、index version。
- 模型：TTFT、total latency、tokens、cost、429/timeout、route/failover。
- 质量：feedback、citation validation fail、abstention、评估趋势。

高基数字段如 user ID、document ID 不作 Prometheus label，放日志/Trace。

### 2.3 Logs

结构字段至少：timestamp、level、service、environment、traceId、correlationId、spaceId（可哈希/受控）、runId/jobId、event、errorCode。禁止 Authorization、Cookie、API key、数据库 DSN 和默认完整 prompt/document。

## 3. Dashboard

- Platform overview：可用性、错误、延迟、资源、依赖健康。
- Ingestion：queue、throughput、failure、DLQ、parser/OCR、index publish。
- RAG online：retrieval、TTFT、tokens、route、errors、cancel、cost。
- Data safety：cloud egress、denied calls、cross-space probes、audit pipeline。
- Capacity：connection/worker saturation、Qdrant size、object growth、disk forecast。

## 4. 告警原则

以用户影响和耗尽风险告警，不对每个瞬时错误报警。P1 候选：登录/问答高错误率、active index 不可用、未经授权出境、DLQ/queue age 持续增长、数据库容量/备份失败。每个告警必须链接 owner、影响、Dashboard 和 Runbook。

## 5. LLMOps

核心统一发 OpenTelemetry。Langfuse 是可选消费者，通过官方 [Spring AI OpenTelemetry 集成说明](https://langfuse.com/integrations/frameworks/spring-ai)接入；自建时注意其附加依赖和仓库中 EE 目录的许可证边界。即使 Langfuse 不运行，核心 Trace 与 Evaluation Run 仍完整。

## 6. 数据保留

- 业务会话：90 天。
- 原始调试 prompts/responses：默认 7 天并可关闭。
- 审计：建议 365 天。
- 普通日志/Trace：按容量和安全要求设较短周期；聚合指标保留更久。
- 删除工作流覆盖 secondary telemetry，或记录因法律/安全要求不能删除的依据。
