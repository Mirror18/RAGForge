# space

**负责**：账号、Session、空间、成员角色、云端出境策略、模型绑定。

**不负责**：解析文件、写 Qdrant、生成回答。

**当前代码**：`backend/server/src/main/java/com/ragforge/server/identity/`、`space/`、`provider/` 中的绑定与出境。

**入口（目标）**：`api` 层 HTTP；浏览器经 gateway 访问。

**依赖**：`shared`；PostgreSQL schema `space`；Valkey 仅存 Session。
