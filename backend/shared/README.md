# shared

**负责**：跨业务通用类型、安全配置、Outbox/审计、运维入口、Idempotency。

**当前代码（四层）**：`backend/server/src/main/java/com/ragforge/server/shared/{api,application,domain,infrastructure}`

**不负责**：租户业务规则；不得绕过 `space_id` 边界。
