# knowledge

**负责**：数据源、摄取任务、解析、OCR、分块、embedding、索引验证与发布、按 `space_id` + 已发布索引版本返回候选片段。

**不负责**：登录、对话生成、浏览器入口。批量解析可以同一套代码的 job 进程运行，Nacos 里仍叫 knowledge。

**当前代码**：`backend/server` 的 ingestion/chunk/index，以及整个 `backend/ingestion-worker`。

**依赖**：`shared`、space 的授权结果；PostgreSQL schema `knowledge`；Qdrant；对象存储；已授权云端 embedding API。
