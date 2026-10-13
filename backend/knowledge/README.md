# knowledge

**负责**：数据源、摄取任务、分块、embedding、索引验证与发布、按 `space_id` + 已发布索引版本返回候选片段、试检索/看来源。

**不负责**：登录、对话生成、浏览器入口。批量解析仍可由 `backend/ingestion-worker` 运行。

**当前代码（四层）**：`backend/server/src/main/java/com/ragforge/server/knowledge/{api,application,domain,infrastructure}`，以及整个 `backend/ingestion-worker`。

**依赖**：`shared`、space 的授权结果；PostgreSQL；Qdrant；对象存储；已授权云端 embedding API。
