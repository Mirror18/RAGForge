# Provider 超时或不可用

1. 症状与用户影响

`RAGForgeProviderTimeoutRateHigh`、问答错误率或 SSE first event 告警触发；用户
可能看到 degraded、拒答、超时或 SSE 断开。登录错误率同时升高时先按平台依赖
故障处理。

2. 安全边界和禁止动作

只允许读取 dashboard、Prometheus、Loki、Tempo 和应用健康端点。不得打印或复制
Authorization、Cookie、provider key、prompt、document、response；不得把本地
route 静默切换成 cloud route，不得为排障打开 cloud egress。

3. Dashboard、查询和只读诊断

打开 `RAGForge Phase 6 On-call` 的 generation、SSE、provider 面板，并查看：

```promql
sum(rate(ragforge_provider_timeouts_total[5m])) by (provider, route)
sum(rate(ragforge_provider_rate_limit_total[5m])) by (provider, route)
histogram_quantile(0.95, sum by (le) (rate(ragforge_generation_duration_seconds_bucket[10m])))
```

按 `trace_id`、`correlation_id`、`run_id` 和受控 `space_id` 跳转 Tempo/Loki；只看
`event`、`error_code`、状态和耗时。检查 provider health endpoint 与 route binding，
不读取凭据值。

4. 缓解步骤

- 确认故障是否只影响一个 route/provider 或所有空间。
- 对受影响空间保持原有 `LOCAL_ONLY`/授权策略；必要时暂停新 Run，避免重试风暴。
- 仅切换到已经评估、已发布且符合空间出境授权的 route；否则维持 fail-closed。
- 记录告警时间窗、受影响 route、错误码和 Run/trace opaque id。

5. 恢复与验证

健康检查恢复后，验证 provider timeout/rate-limit 回落、SSE first event 恢复、
新 Run 的 citation/provenance 仍完整，并观察至少两个告警评估周期。不要用单次
成功请求关闭 incident。

6. 回滚

回到上一个已评估 route/profile binding；若无合规 route，回滚到 fail-closed，不
回滚到未授权云端。变更通过正常发布与审计流程完成。

7. 升级联系人/SLA

P1：5 分钟内通知平台 on-call、RAG on-call 和 provider owner；15 分钟无缓解
升级 incident commander。P2 延迟问题在一个工作小时内升级检索/模型 owner。

8. 证据和复盘记录位置

记录 `tests/evidence/phase6-observability-fault-drill.v1.json` 格式的时间窗、
告警、dashboard UID、runbook 版本、只读查询和影响摘要；不得附原始请求/答案。
