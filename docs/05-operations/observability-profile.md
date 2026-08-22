# Phase 6 可观测性 Profile 与验收

## 1. 范围与边界

本 profile 以 `deploy/compose/compose.yaml` 为 core 基础，以
`deploy/compose/observability.yaml` 为 overlay，提供 OTel Collector、
Prometheus、Grafana、Loki、Tempo 五个服务。它是隔离的本地/验收拓扑，不代表
生产容量，也不自动启用云端 provider 或 Langfuse。Langfuse 仍可作为独立可选
消费者；核心 Trace、Metrics、Logs 和本项目 Evaluation Run 不依赖 Langfuse。

所有镜像使用版本化 tag，并可在环境中替换为已批准的 immutable digest。数据
卷使用 `RAGFORGE_OBSERVABILITY_VOLUME_PREFIX` 隔离，不能与其他 Compose project
共享。Grafana 管理员密码必须由进程环境或 Secret Store 注入；仓库中不存在默认
密码。

## 2. 启动与验证

```powershell
$env:GRAFANA_ADMIN_PASSWORD = "<从本机 Secret Store 读取>"
docker compose --project-name ragforge-p6-observability `
  --file deploy/compose/compose.yaml `
  --file deploy/compose/observability.yaml `
  --profile observability up -d
docker compose --project-name ragforge-p6-observability `
  --file deploy/compose/compose.yaml `
  --file deploy/compose/observability.yaml `
  --profile observability ps
```

配置校验必须使用与启动相同的两个 Compose 文件：

```text
docker compose --project-name ragforge-p6-observability \
  --file deploy/compose/compose.yaml \
  --file deploy/compose/observability.yaml \
  --profile observability config --quiet
```

服务健康检查使用以下只读端点：Grafana `/api/health`、Prometheus `/-/ready`、
Loki `/ready`、Tempo `/status`、Collector `/`（health_check extension）。Tempo
2.6.x 的 `/ready` 在单体本地配置下可能持续返回 503，即使 HTTP/API 与 Trace
接收模块已经启动；本 profile 因此使用 `/status` 作为可重复的 liveness/readiness
探针，并另行验证 OTLP trace 接收。
Dashboard 只有在 profile 实际启动并能从 Grafana API 读取到 UID
`ragforge-phase6-oncall` 后，才可称为 runtime 已验证；只通过 JSON/Compose 校验
时只能称为资产校验。

## 3. 信号和安全投影契约

应用或 worker 向 Collector 发送 OTLP。受控关联字段为：

| 字段 | 用途 | 约束 |
| --- | --- | --- |
| `trace_id` | Tempo trace 与日志跳转 | OTel trace id，不含正文 |
| `correlation_id` | 请求/事件关联 | opaque correlation id |
| `run_id` | Answer Run 关联 | opaque run id |
| `space_id` | 空间边界诊断 | 受控/哈希投影，不作高基数指标 label |
| `event`、`error_code`、`status` | 诊断分类 | 版本化枚举/短值 |
| duration、count、queue age | SLO/容量 | Prometheus 数值，不含内容 |

Collector 在三个 pipeline 中执行以下保护：

- Trace 删除 Authorization、Cookie、HTTP body、数据库语句、prompt/document
  内容、LLM 输入输出和异常原文。
- Log 删除同类敏感属性并清空 body，只导出结构化关联字段和短状态字段。
- Metric 删除 user/document/prompt/response 等高基数字段；dashboard 不使用
  `user_id`、`document_id` 或原始 prompt 作 label。

如果上游把正文放入未列出的自定义属性，必须先修正上游 instrumentation 或在
Collector 增加删除规则；不得以“Grafana 未展示”为安全证明。

## 4. Dashboard 与告警

Grafana provision 自动加载 `RAGForge Phase 6 On-call`，UID 为
`ragforge-phase6-oncall`，数据源为 Prometheus、Loki、Tempo。面板覆盖：

- 登录/问答错误率、拒答和 citation 校验失败；
- retrieval/generation latency、SSE first event、断连、provider timeout/rate-limit；
- queue depth/age、DLQ、ingestion failure/retry、active index；
- 未授权出境拒绝；
- PostgreSQL 连接池、对象存储、Qdrant 容量；
- 最近备份与恢复点年龄；
- 通过 `trace_id`、`correlation_id`、`run_id`、`space_id` 的安全日志关联。

Prometheus rules 中每条 P1 告警都带 `owner`、`user_impact`、dashboard 名称和
runbook 路径。告警只是信号，不代替空间授权、出境审计或恢复演练。

## 5. 验收证据

执行以下命令生成资产校验证据（不启动服务）：

```text
python scripts/phase6/observability_check.py \
  --evidence tests/evidence/phase6-observability-assets.v1.json
```

在实际 profile 启动后执行 fault drill：

```text
python scripts/phase6/observability_drill.py \
  --prometheus-url http://127.0.0.1:29090 \
  --otel-url http://127.0.0.1:24318 \
  --grafana-url http://127.0.0.1:23000 \
  --loki-url http://127.0.0.1:23100 \
  --tempo-url http://127.0.0.1:23200 \
  --evidence tests/evidence/phase6-observability-fault-drill.v1.json
```

演练注入的只是合成 `ragforge_egress_denied_total` 指标，不包含用户内容、凭据
或原始 prompt。脚本必须从运行中的 Prometheus 查询到该信号，并确认 Grafana
实际已 provision dashboard、Loki/Tempo/Collector 端点可达，再将
`unauthorized-egress.md` 作为定位路径写入证据。若服务未运行，脚本失败，不能
生成“已验证”结论。
