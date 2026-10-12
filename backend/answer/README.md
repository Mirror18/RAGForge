# answer

**负责**：检索编排、提示词版本、生成、结构化 Evidence Bundle、run/step、只读工具。

**不负责**：解析原文、发布索引、持有对象存储或 Qdrant 写凭据。rerank 留在本服务的 `infrastructure`。

**当前代码**：`backend/server` 的 answer、retrieval、prompt、run、agent、studio。

**依赖**：`shared`；knowledge 的候选检索接口；space 的授权上下文；已授权云端 Chat API。
