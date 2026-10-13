# space

**负责**：账号、Session、空间、成员角色、云端出境策略、模型绑定与 Provider 连接。

**不负责**：解析文件、写 Qdrant、生成回答。

**当前代码（四层）**：`backend/server/src/main/java/com/ragforge/server/space/{api,application,domain,infrastructure}`

**入口（目标）**：`api` 层 HTTP；浏览器经 gateway 访问。

**依赖**：`shared`；PostgreSQL；Valkey 仅存 Session。
