# Phase 6 容量演练记录规范

## 目的

`capacity_benchmark.py` 先调用 loopback Ollama `/api/embed`，从已安装的 embedding route 读取真实维度，再在该维度创建隔离 Qdrant collection。1M child chunk 的向量值是确定性公共合成数据，不能表述为真实客户 embedding 或生产容量承诺；禁止回退到 8 维代理。

## 运行

```powershell
python scripts/phase6/capacity_benchmark.py --output tests/evidence/phase6-capacity-retrieval.v1.json
```

脚本使用 Compose project `ragforge-p6-capacity-a2`、Qdrant `26347/26348`、独立 volume/network，并在退出时执行 `docker compose ... down -v`。如中断，先确认仅删除该 project 的资源，再执行同一命令重试。默认 1,000,000 points、20 并发、400 次混合过滤查询和 256 点写入批次；写入请求使用 point ID 幂等重试（最多 6 次，退避上限 30 秒），失败证据记录 `phase/type/message/retry_command` 以及 Qdrant compose 状态、容器 stats 和日志尾部。结果包含 p50/p95/p99、吞吐、错误率、Recall@10、Ollama 响应 hash、配置 hash、脚本 hash、代码 commit 和机器信息。

较小批次只用于降低单次 HTTP body 和 Qdrant 写入压力，不改变 768 维、1,000,000 child chunks、4-space filter、20 并发的门槛。`--child-count` 小于 1,000,000 的运行只能作为诊断/冒烟，脚本必须保持 `FAILED`，不能写成容量通过。

## 在线门槛

非 AI API 和 SSE 首事件使用独立探针，不能把模型完整响应时间冒充 TTFT：

```powershell
python scripts/phase6/online_latency_probe.py --server-url http://127.0.0.1:8080 --output tests/evidence/phase6-capacity-online.v1.json
```

SSE 探针需要由已认证 harness 先创建 run 并传入 `--sse-url`；没有运行中的 server 或没有合法 run URL 时，证据必须为 `BLOCKED`，并保留重试命令。探针不会创建生产数据、发送 cloud provider 请求或输出认证 header。
