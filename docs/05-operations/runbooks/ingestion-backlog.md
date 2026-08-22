# Ingestion 队列积压、DLQ 或 active index 异常

1. 症状与用户影响

`RAGForgeQueueAgeHigh`、`RAGForgeDeadLetterQueueNotEmpty` 或
`RAGForgeActiveIndexUnavailable` 触发；新上传内容不能及时检索，已有 active
index 可能继续服务或进入 degraded/拒答。

2. 安全边界和禁止动作

只读检查 queue、DLQ、worker health、active index pointer 和指标。不得直接删除
消息、重放未知空间消息、绕过 space filter、把未验证 index 标为 active，或把
文档正文复制到日志/工单。

3. Dashboard、查询和只读诊断

```promql
max(ragforge_queue_depth)
max(ragforge_queue_oldest_age_seconds)
sum(ragforge_dlq_messages) by (queue)
sum(rate(ragforge_ingestion_failures_total[10m])) by (stage, error_code)
min(ragforge_active_index_healthy) by (space_id)
```

在 Loki 中按 `job_id`、`run_id`、`space_id` 的安全投影关联失败 stage；在 Tempo
检查 publish/consume/index-publish span 的状态与耗时，不读取 payload。

4. 缓解步骤

- 确认积压是全局 transport、单 worker、单 parser/OCR stage 还是单空间。
- 暂停导致重试风暴的 source；保留消息和 DLQ 作为恢复输入。
- 修复或隔离坏消息后，使用带 space 授权和幂等 key 的 replay 工具小批量重放。
- active index 异常时保持上一 READY index；禁止直接指向 BUILDING/未知版本。

5. 恢复与验证

确认 queue age、DLQ 和 failure rate 连续两个评估周期下降；检查新 revision、
chunk、index publish 和 query 的 `space_id` 一致性。对重放消息确认不重复、不跨
空间，并验证 active index 可回滚。

6. 回滚

停止 replay，恢复上一 READY/RETIRED index pointer；保留 DLQ 和 audit 记录，按
备份恢复 Runbook 处理持久化损坏。

7. 升级联系人/SLA

P1 active index 不可用或队列 age 超过 15 分钟时 5 分钟内通知 ingestion、retrieval
和平台 on-call；30 分钟不能恢复升级 incident commander。

8. 证据和复盘记录位置

记录 dashboard 时间窗、queue/DLQ/index 指标快照、opaque job/run/space id、重放
批次和结果。证据放在 `tests/evidence/phase6-observability-*.json`，不附消息正文。
