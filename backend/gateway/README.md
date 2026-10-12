# gateway

**负责**：唯一对外 HTTP 入口，按 Nacos 实例把请求转到 space / knowledge / answer。

**不负责**：判断某用户是否属于某空间，也不解释引用是否合法。

**当前状态**：尚未建模块。ADR-0014 接受前，浏览器仍直接访问 `backend/server` 的 8080/25082。
