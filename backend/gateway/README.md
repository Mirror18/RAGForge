# gateway

**负责**：浏览器唯一 HTTP 入口，把 `/api/v1` 路由到 space / knowledge / answer。

**不负责**：业务规则、权限判定、引用校验。

**当前状态**：尚未独立进程；浏览器仍直连 `backend/server`。接入见 RF-9 / T006。
