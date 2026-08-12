# RAGForge Web

计划基线：Vue 3、TypeScript、Vite、组件/状态/测试工具在 Phase 1 锁定精确版本。

一个 SPA 覆盖：

- Viewer：空间、搜索、对话、引用、反馈。
- Editor：数据源、任务、Parse Report、Chunk Studio、Retrieval Playground。
- Space Admin：成员、空间模型/提示词/出境配置。
- Platform Admin：用户、Provider、审计、评估和系统健康。

前端角色只控制导航和交互，不构成安全边界。OpenAPI 生成的 TypeScript client 放 `libs/typescript/`，手写请求必须有明确理由。

