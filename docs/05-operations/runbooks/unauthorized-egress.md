# 未授权云端出境拒绝

1. 症状与用户影响

`RAGForgeUnauthorizedEgress` 触发或 dashboard 的 `ragforge_egress_denied_total`
增加。当前请求应被 fail-closed 拒绝；这表示安全控制阻止了调用，不表示可以
忽略事件。用户可能看到拒答或 degraded。

2. 安全边界和禁止动作

不得重试云端调用、打开全局 cloud flag、复制 provider URL/key、读取 prompt 或
document、按客户端提供的 `space_id` 放宽授权。不得把拒绝事件当作已发生出境。

3. Dashboard、查询和只读诊断

```promql
increase(ragforge_egress_denied_total[5m])
sum by (decision, route, provider) (increase(ragforge_provider_calls_total[5m]))
```

在日志/Trace 中只检查 `trace_id`、`correlation_id`、`run_id`、受控 `space_id`、
`decision`、`route_class` 和 `error_code`。核对 route binding、space policy、
authorization context 和审计事件的关联，不输出凭据或正文。

4. 缓解步骤

- 立即确认 `provider_calls_total` 中没有未经授权 cloud route；若有，升级为安全
  incident 并冻结相关 route。
- 保持 `LOCAL_ONLY` 或 fail-closed；不能用云端绕过错误。
- 检查是否为过期 binding、空间策略漂移、伪造 document/evidence reference 或
  tool schema 绕过；保存 opaque 关联 id。
- 在授权 owner 确认前不改变 cloud egress 配置。

5. 恢复与验证

安全 owner 确认拒绝计数停止增长、未授权 cloud call 为 0、跨空间/证据外引用为
0，并验证授权请求仍能正确工作。重跑相关 security regression 和一条本地 route
smoke；只在明确授权后恢复合规 cloud route。

6. 回滚

撤回最近 route/policy/binding 变更，恢复上一版已验证策略；必要时全局维持
LOCAL_ONLY。不得删除审计记录或清空指标卷来“消除”告警。

7. 升级联系人/SLA

P1，立即通知 security、provider/platform on-call 和 incident commander；5 分钟
内完成初始分流，按安全事件流程进行后续处置。

8. 证据和复盘记录位置

使用 `tests/evidence/phase6-observability-fault-drill.v1.json` 的安全投影格式，
记录告警、route class、decision、opaque ids、查询结果和授权结论，不记录原始请求。
