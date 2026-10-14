---
id: doc-2
title: 运行与安全
type: guide
created_date: '2026-10-12 03:09'
updated_date: '2026-10-13 10:00'
---
# 运行与安全

现行进程是 `gateway`、`server`、`ingestion-worker`、`frontend/ragforge-web`。可选的本地 rerank 辅助代码在 `backend/knowledge/ai-runtime`，不是 Compose 业务服务。模型只走空间已授权的 OpenAI 兼容或 MiMo 云端 API；未授权调用 fail-closed。

本地启动：`scripts/dev/start-local.bat`。它会拉起 `deploy/compose/compose.yaml` 里的 PostgreSQL、Qdrant、RabbitMQ、Valkey 和 MinIO，再在本机启动 Server、Worker 和 Web。凭据放在 gitignore 的 `.env.local`。

第三方组件以 Maven、uv、npm lock 中的精确版本/Commit 为准。许可证用 SPDX 标识。未批准的第三方源码不进仓库。

<a id="platform-admin-bootstrap"></a>

## 平台管理员初始化

库中尚无活跃 `PLATFORM_ADMIN` 时，必须先完成一次平台管理员初始化。推荐路径如下（任选其一即可；完成后启动码与 bootstrap 窗口关闭）。

| 路径 | 适用场景 | 做法 |
| --- | --- | --- |
| 启动日志一次性码 | 自托管 / 空库首次安装 | Server 启动时在日志打印 `STARTUP_BOOTSTRAP_CODE`（仅哈希入库）。在登录页「初始化平台管理员」填入该码与邮箱/密码。重启会换码，直到 bootstrap 完成。 |
| CLI | 运维、脚本、无浏览器 | 设置 `RAGFORGE_ADMIN_CLI_PASSWORD`（≥12 位），再执行 `scripts/dev/create-platform-admin.bat -Email you@example.com`。不走 HTTP。 |
| dev seed | 仅本地开发 | `SPRING_PROFILES_ACTIVE=dev`（`start-local` / `restart-local` 已启用）自动 seed `dev-admin@ragforge.local` / `dev-admin-change-me`。**禁止用于生产。** |

可选 break-glass：配置 `RAGFORGE_BOOTSTRAP_ADMIN_TOKEN`（≥32 字符）后，登录页可用同一 header/表单完成初始化；用完应移除。不要把启动码、CLI 密码或 bootstrap token 写入仓库、浏览器存储或公共日志聚合。

审计事件：`platform.admin.bootstrapped.v1`（payload 含 `mode`、`credentialSource`，不含明文密码或 token）。迁移：`V26__platform_admin_startup_bootstrap.sql`。

告警仍指向下面四个 Runbook。

<a id="runbook-provider-outage"></a>

## 云端模型超时或不可用

1. 症状与用户影响

`RAGForgeProviderTimeoutRateHigh`、问答错误率或 SSE first event 告警触发；用户可能看到拒答、超时或 SSE 断开。登录错误率同时升高时先按平台依赖故障处理。

2. 安全边界和禁止动作

只读 dashboard、Prometheus、Loki、Tempo 和应用健康端点。不得打印 Authorization、Cookie、provider key、prompt、document、response。不得为未授权空间打开 cloud egress，不得把失败调用改打到另一个未绑定的端点。

3. Dashboard、查询和只读诊断

打开 `RAGForge Phase 6 On-call` 的 generation、SSE、provider 面板，并查看：

```promql
sum(rate(ragforge_provider_timeouts_total[5m])) by (provider, route)
sum(rate(ragforge_provider_rate_limit_total[5m])) by (provider, route)
histogram_quantile(0.95, sum by (le) (rate(ragforge_generation_duration_seconds_bucket[10m])))
```

按 `trace_id`、`correlation_id`、`run_id` 和受控 `space_id` 跳转 Tempo/Loki；只看 `event`、`error_code`、状态和耗时。检查 provider health 与该空间的云端 route binding，不读取凭据值。

4. 缓解步骤

- 确认故障是一个 route/provider 还是所有已授权空间。
- 保持各空间原有出境授权；必要时暂停新 Run，避免重试风暴。
- 只能切到该空间已经授权且已发布的云端 route；否则维持 fail-closed。
- 记录告警时间窗、受影响 route、错误码和 Run/trace opaque id。

5. 恢复与验证

健康检查恢复后，确认 timeout/rate-limit 回落、SSE first event 恢复，新 Run 的 citation/provenance 仍完整，并观察至少两个告警评估周期。

6. 回滚

回到上一个已授权的 route binding。没有合规 route 时保持 fail-closed。

7. 升级联系人/SLA

P1：5 分钟内通知平台 on-call 和 provider owner；15 分钟无缓解升级 incident commander。

8. 证据和复盘记录位置

记录 `tests/evidence/phase6-observability-fault-drill.v1.json` 格式的时间窗、告警、dashboard UID 和影响摘要；不得附原始请求或答案。

<a id="runbook-ingestion-backlog"></a>

## 摄取队列积压、DLQ 或 active index 异常

1. 症状与用户影响

`RAGForgeQueueAgeHigh`、`RAGForgeDeadLetterQueueNotEmpty` 或 `RAGForgeActiveIndexUnavailable` 触发；新上传内容不能及时检索，已有 active index 可能继续服务或进入拒答。

2. 安全边界和禁止动作

只读检查 queue、DLQ、worker health、active index pointer 和指标。不得直接删除消息、重放未知空间消息、绕过 space filter、把未验证 index 标为 active，或把文档正文复制到日志。

3. Dashboard、查询和只读诊断

```promql
max(ragforge_queue_depth)
max(ragforge_queue_oldest_age_seconds)
sum(ragforge_dlq_messages) by (queue)
sum(rate(ragforge_ingestion_failures_total[10m])) by (stage, error_code)
min(ragforge_active_index_healthy) by (space_id)
```

在 Loki 中按 `job_id`、`run_id`、`space_id` 关联失败 stage；在 Tempo 看 publish/consume/index-publish span，不读取 payload。摄取代码在 `backend/ingestion-worker`。

4. 缓解步骤

- 确认积压是全局 transport、单个 worker、单个 parser stage 还是单个空间。
- 暂停导致重试风暴的 source；保留消息和 DLQ。
- 修复坏消息后，用带 space 授权和幂等 key 的方式小批量重放。
- active index 异常时保持上一 READY index。

5. 恢复与验证

确认 queue age、DLQ 和 failure rate 连续两个评估周期下降；检查新 revision、chunk、index publish 和 query 的 `space_id` 一致。

6. 回滚

停止 replay，恢复上一 READY index pointer；保留 DLQ 和 audit。

7. 升级联系人/SLA

P1 active index 不可用或队列 age 超过 15 分钟时，5 分钟内通知摄取和平台 on-call；30 分钟不能恢复升级 incident commander。

8. 证据和复盘记录位置

记录 dashboard 时间窗、queue/DLQ/index 指标、opaque job/run/space id 和重放结果。证据放在 `tests/evidence/phase6-observability-*.json`，不附消息正文。

<a id="runbook-unauthorized-egress"></a>

## 未授权云端出境拒绝

1. 症状与用户影响

`RAGForgeUnauthorizedEgress` 触发或 `ragforge_egress_denied_total` 增加。当前请求应被 fail-closed 拒绝。用户可能看到拒答。

2. 安全边界和禁止动作

不得重试云端调用、打开未授权空间的 cloud egress、复制 provider URL/key、读取 prompt 或 document。不得把拒绝事件当成已经发生的出境。

3. Dashboard、查询和只读诊断

```promql
increase(ragforge_egress_denied_total[5m])
sum by (decision, route, provider) (increase(ragforge_provider_calls_total[5m]))
```

只检查 `trace_id`、`correlation_id`、`run_id`、受控 `space_id`、`decision`、`route_class` 和 `error_code`。核对 route binding 与空间授权。

4. 缓解步骤

- 确认没有未经授权的 cloud route 调用；若有，按安全事件冻结该 route。
- 保持 fail-closed。
- 检查是否为过期 binding、空间策略漂移或伪造引用。
- 在空间负责人确认前不改变出境配置。

5. 恢复与验证

确认拒绝计数停止增长、未授权 cloud call 为 0。只在该空间明确授权后恢复对应云端 route。

6. 回滚

撤回最近的 route、policy、binding 变更。不得删除审计记录或清空指标来消除告警。

7. 升级联系人/SLA

P1，立即通知 security 和平台 on-call；5 分钟内完成初始分流。

8. 证据和复盘记录位置

记录告警、route class、decision 和 opaque id，不记录原始请求。证据格式见 `tests/evidence/phase6-observability-fault-drill.v1.json`。

<a id="runbook-database-capacity"></a>

## 数据库容量、连接池或恢复点异常

1. 症状与用户影响

`RAGForgeDatabaseCapacityHigh` 或 `RAGForgeRecoveryPointStale` 触发；API 可能排队或失败，故障时恢复点可能超过 24 小时。

2. 安全边界和禁止动作

只读查看连接池、磁盘、备份状态和指标。不得在生产删除表或卷、执行未批准 migration，或复制数据库内容。

3. Dashboard、查询和只读诊断

```promql
max(ragforge_db_connections_used / clamp_min(ragforge_db_connections_max, 1))
time() - ragforge_backup_last_success_timestamp_seconds
ragforge_object_storage_bytes
ragforge_qdrant_storage_bytes
```

只使用连接池统计、卷大小和 opaque backup id，不导出 SQL 或正文。

4. 缓解步骤

- 判断是连接泄漏、慢查询、磁盘、备份失败，还是对象存储或 Qdrant 增长。
- 限制非必要摄取和重试。不得靠无限增大连接池掩盖泄漏。
- 核对最近成功恢复点和备份 hash。清理动作需要人工批准。

5. 恢复与验证

确认连接池、磁盘和恢复点年龄回到阈值内。在隔离环境验证 PostgreSQL、对象存储、Qdrant、active index 和引用。

6. 回滚

回滚最近应用或配置到 schema 兼容版本；active index 可切回上一 READY 版本。生产 migration 必须走审批。

7. 升级联系人/SLA

P1 容量超过 80% 或恢复点超过 24 小时，5 分钟内通知平台和数据库 on-call；15 分钟无缓解升级 incident commander。

8. 证据和复盘记录位置

记录指标时间窗、backup id、schema version 和实际 RPO/RTO。证据放在 `tests/evidence/phase6-observability-*.json`。
