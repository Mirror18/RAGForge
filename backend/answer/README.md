# answer

**负责**：带引用问答、Run/SSE、Prompt 管理、工具调用审计投影。

**不负责**：对象存储写入、Qdrant 发布、浏览器入口。

**当前代码（四层）**：`backend/server/src/main/java/com/ragforge/server/answer/{api,application,domain,infrastructure}`

**依赖**：`shared`、knowledge 检索端口、space 授权的 Chat 路线。
